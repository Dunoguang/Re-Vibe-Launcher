package com.dng.revibe.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * 屏幕实时预览模块（MediaProjection 截屏流 → WebView JS 帧回调）
 *
 * 免授权策略：通过 AppOps PROJECT_MEDIA=allow（enableNoAuth 一键开启，需 Root/Shizuku/ADB），
 * 系统授权对话框会自动通过（不弹窗），因此无需 token 复用/暂停保活等技巧，
 * 每次开始预览都走标准授权流程，stop 即彻底释放。
 */
class ScreenCaptureModule(private val bridge: JsBridge) {

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_SCREEN_CAPTURE = 9001

        /** 预览最大宽高（等比缩放，控制 base64 帧体积） */
        private const val MAX_PREVIEW_WIDTH = 480
        private const val MAX_PREVIEW_HEIGHT = 960

        /** 目标帧率（截屏帧推送上限，避免压垮 JS 桥） */
        private const val TARGET_FPS = 8
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
    }

    private val gson = Gson()

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var isCapturing = false
    private var lastFrameTime = 0L

    private var pendingStartCallback: String? = null

    // ==================== JS 可调用接口 ====================

    /** 开始屏幕预览：走 MediaProjection 授权（AppOps allow 时系统自动通过，不弹窗） */
    fun startPreview(callbackId: String) {
        if (isCapturing) {
            callbackResult(callbackId, true, "屏幕预览已在运行", true)
            return
        }
        val activity = bridge.getContext() as? Activity
        if (activity == null) {
            callbackResult(callbackId, false, "Context 不是 Activity", false)
            return
        }
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        pendingStartCallback = callbackId

        // Android 14+（API 34）：必须在 getMediaProjection() 之前启动 mediaProjection 类型前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ContextCompat.startForegroundService(activity, Intent(activity, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "启动前台服务失败", e)
            }
        }

        try {
            activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        } catch (e: Exception) {
            pendingStartCallback = null
            callbackResult(callbackId, false, "启动截屏意图失败: ${e.message}", false)
        }
    }

    /** 停止屏幕预览（彻底释放，下次启动走授权流程；AppOps allow 时不弹窗） */
    fun stopPreview(callbackId: String) {
        stopCaptureInternal()
        stopForegroundService()
        callbackResult(callbackId, true, "屏幕预览已停止", false)
    }

    /** 查询是否正在预览 */
    fun isPreviewing(): String = gson.toJson(mapOf("capturing" to isCapturing))

    /**
     * 一键开启免授权（AppOps PROJECT_MEDIA=allow）
     *
     * 命令执行按能力分层选择通道：
     *   1. Root（uid=0）→ 直连 Shell
     *   2. Shell（uid=2000）→ 直连 Shell
     *   3. SU（su 二进制可用）→ su -c 提权
     *   4. Shizuku（已授权）→ Shizuku；未授权则先请求授权、等待反馈
     * 原理：AppOps OP_PROJECT_MEDIA(46) 设为 allow 后，系统授权对话框自动通过、无需前台服务。
     */
    fun enableNoAuth(callbackId: String) {
        val pkg = bridge.getContext()?.packageName ?: "com.dng.revibe.launcher"
        val cmd = "appops set $pkg android:project_media allow 2>&1; echo '---VERIFY---'; appops get $pkg android:project_media 2>&1"
        val attempts = StringBuilder()

        val uid = Process.myUid()
        // 先探测 su 二进制（不实际提权，避免触发 Magisk 授权弹窗挂起）
        Shell.execute("command -v su 2>/dev/null || echo NO_SU") { r ->
            val hasSu = r.stdout.trim().isNotEmpty() && r.stdout.trim() != "NO_SU"
            val shizukuConnected = ShizukuAPI.isConnected()
            val shizukuGranted = ShizukuAPI.isPermissionGranted()

            when {
                // 1. Root 直连
                uid == 0 -> {
                    attempts.append("• 通道：Root(uid=0) 直连\n")
                    execAndVerify(callbackId, cmd, pkg, attempts, useSu = false)
                }
                // 2. Shell 直连
                uid == 2000 -> {
                    attempts.append("• 通道：Shell(uid=2000) 直连\n")
                    execAndVerify(callbackId, cmd, pkg, attempts, useSu = false)
                }
                // 3. SU 提权
                hasSu -> {
                    attempts.append("• 通道：SU(su -c) 提权\n")
                    execAndVerify(callbackId, cmd, pkg, attempts, useSu = true)
                }
                // 4a. Shizuku 已授权
                shizukuConnected && shizukuGranted -> {
                    attempts.append("• 通道：Shizuku\n")
                    execShizukuAndVerify(callbackId, cmd, pkg, attempts)
                }
                // 4b. Shizuku 未授权 → 请求授权并等待反馈
                shizukuConnected -> {
                    attempts.append("• 通道：Shizuku（未授权，已请求授权）\n")
                    val started = ShizukuAPI.requestPermission(10086) { granted ->
                        if (granted) {
                            execShizukuAndVerify(callbackId, cmd, pkg, attempts)
                        } else {
                            attempts.append("• Shizuku：授权被拒绝或超时\n")
                            failNoAuth(callbackId, pkg, attempts)
                        }
                    }
                    if (!started) {
                        attempts.append("• Shizuku：无法弹出授权界面\n")
                        failNoAuth(callbackId, pkg, attempts)
                    }
                }
                // 5. 无任何特权通道
                else -> {
                    attempts.append("• 无可用特权通道（非 root/shell 运行，无 su，Shizuku 未连接）\n")
                    failNoAuth(callbackId, pkg, attempts)
                }
            }
        }
    }

    /** Shell 直连或 SU 提权执行 appops，并校验结果 */
    private fun execAndVerify(callbackId: String, cmd: String, pkg: String, attempts: StringBuilder, useSu: Boolean) {
        val fullCmd = if (useSu) "su -c \"$cmd\" 2>&1" else cmd
        Shell.execute(fullCmd) { r ->
            if (verifyNoAuthAllowed(r.stdout, r.stderr)) {
                Log.i(TAG, "免授权开启成功")
                callbackResult(callbackId, true, "🔓 免授权已开启（PROJECT_MEDIA=allow），预览不再弹窗", false)
            } else {
                val cmdOut = r.stdout.substringBefore("---VERIFY---").trim()
                val errOut = r.stderr.trim()
                attempts.append("• 执行失败：退出码 ${r.statusCode}")
                if (cmdOut.isNotEmpty()) attempts.append(" 输出:${cmdOut.take(150).replace("\n", " ")}")
                if (errOut.isNotEmpty()) attempts.append(" 错误:${errOut.take(150).replace("\n", " ")}")
                failNoAuth(callbackId, pkg, attempts)
            }
        }
    }

    /** Shizuku 执行 appops，并校验结果 */
    private fun execShizukuAndVerify(callbackId: String, cmd: String, pkg: String, attempts: StringBuilder) {
        ShizukuAPI.execute(cmd) { r ->
            if (verifyNoAuthAllowed(r.stdout, r.stderr)) {
                Log.i(TAG, "免授权开启成功（Shizuku）")
                callbackResult(callbackId, true, "🔓 免授权已开启（PROJECT_MEDIA=allow），预览不再弹窗", false)
            } else {
                attempts.append("• Shizuku：退出码 ${r.statusCode} ${stderrBrief(r.stderr)}\n")
                failNoAuth(callbackId, pkg, attempts)
            }
        }
    }

    /** 汇总失败诊断并回调 */
    private fun failNoAuth(callbackId: String, pkg: String, attempts: StringBuilder) {
        Log.w(TAG, "免授权开启失败：\n$attempts")
        callbackResult(
            callbackId, false,
            "❌ 免授权开启失败\n${attempts}\n可手动 ADB：adb shell appops set $pkg android:project_media allow",
            false
        )
    }

    /** 取 stderr 关键信息，最多 120 字符，空则标注无错误输出 */
    private fun stderrBrief(stderr: String): String {
        val e = stderr.trim()
        return if (e.isEmpty()) "(无错误输出)" else e.take(120).replace("\n", " ")
    }

    /** 校验 appops get 输出是否已是 allow（且未被 ignore/deny） */
    private fun verifyNoAuthAllowed(stdout: String, stderr: String): Boolean {
        val output = stdout + "\n" + stderr
        val verify = if (output.contains("---VERIFY---")) {
            output.substringAfter("---VERIFY---")
        } else {
            output
        }
        return verify.contains("allow", ignoreCase = true) &&
                !verify.contains("ignore", ignoreCase = true) &&
                !verify.contains("denied", ignoreCase = true)
    }

    // ==================== Activity 结果处理（由 MainActivity 调用） ====================

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        val callbackId = pendingStartCallback
        pendingStartCallback = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            callbackResult(callbackId, false, "用户拒绝了屏幕捕捉授权", false)
            stopForegroundService()
            return
        }

        val activity = bridge.getContext() as? Activity
        if (activity == null) {
            callbackResult(callbackId, false, "Context 不是 Activity", false)
            stopForegroundService()
            return
        }

        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val projection = mpm.getMediaProjection(resultCode, data)
            if (projection == null) {
                callbackResult(callbackId, false, "获取 MediaProjection 失败", false)
                stopForegroundService()
                return
            }
            startCaptureLoop(activity, projection, callbackId)
            callbackResult(callbackId, true, "屏幕预览已启动", true)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection 失败", e)
            stopForegroundService()
            callbackResult(callbackId, false, "启动失败: ${e.message}", false)
        }
    }

    // ==================== 截屏流核心 ====================

    private fun startCaptureLoop(activity: Activity, projection: MediaProjection, callbackId: String?) {
        val (pw, ph, density) = computePreviewSize(activity)

        captureThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 3).also { reader ->
            reader.setOnImageAvailableListener(imageListener, captureHandler)
        }

        // 监听投影停止（如系统回收 / 用户从状态栏终止投屏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    stopCaptureInternal()
                    stopForegroundService()
                }
            }
            projectionCallback = cb
            try { projection.registerCallback(cb, captureHandler) } catch (_: Exception) {}
        }

        mediaProjection = projection
        isCapturing = true
        lastFrameTime = 0L

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ReVibeScreenPreview",
                pw, ph, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            Log.i(TAG, "虚拟显示器已创建: ${pw}x$ph @ $density")
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay 失败", e)
            stopCaptureInternal()
            stopForegroundService()
            callbackResult(callbackId, false, "创建虚拟显示器失败: ${e.message}", false)
        }
    }

    private fun computePreviewSize(activity: Activity): Triple<Int, Int, Int> {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenRect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            Rect(0, 0, pt.x, pt.y)
        }
        val density = activity.resources.displayMetrics.densityDpi

        var w = screenRect.width()
        var h = screenRect.height()
        if (w <= 0 || h <= 0) { w = 1080; h = 2400 }
        val scale = min(1f, min(MAX_PREVIEW_WIDTH.toFloat() / w, MAX_PREVIEW_HEIGHT.toFloat() / h))
        val pw = (w * scale).toInt().coerceAtLeast(1)
        val ph = (h * scale).toInt().coerceAtLeast(1)
        return Triple(pw, ph, density)
    }

    /** ImageReader 帧监听：限频推送 base64 JPEG 到 JS */
    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= FRAME_INTERVAL_MS) {
                lastFrameTime = now
                val bytes = imageToJpegBytes(image)
                if (bytes != null) {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    bridge.callback("_onScreenFrame", gson.toJson(mapOf("image" to b64)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧处理异常", e)
        } finally {
            image.close()
        }
    }

    private fun imageToJpegBytes(image: Image): ByteArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 直接按原始行填充创建位图，再裁剪掉行填充
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "图片转换失败", e)
            null
        }
    }

    // ==================== 停止 / 清理 ====================

    private fun stopCaptureInternal() {
        if (!isCapturing && mediaProjection == null) return
        isCapturing = false

        val proj = mediaProjection
        mediaProjection = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try { projectionCallback?.let { proj?.unregisterCallback(it) } } catch (_: Exception) {}
        projectionCallback = null

        captureHandler?.removeCallbacksAndMessages(null)
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null

        lastFrameTime = 0L
        try { proj?.stop() } catch (_: Exception) {}
        Log.i(TAG, "屏幕预览资源已释放")
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        bridge.getContext()?.let { ctx ->
            try { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) } catch (_: Exception) {}
        }
    }

    /** 供 MainActivity.onDestroy 调用，彻底清理 */
    fun release() {
        stopCaptureInternal()
        stopForegroundService()
    }

    // ==================== JS 回调 ====================

    private fun callbackResult(callbackId: String?, success: Boolean, message: String, running: Boolean) {
        if (callbackId.isNullOrEmpty()) return
        val data = mapOf(
            "callbackId" to callbackId,
            "success" to success,
            "message" to message,
            "running" to running
        )
        bridge.callback("_onScreenPreviewResult", gson.toJson(data))
    }
}
