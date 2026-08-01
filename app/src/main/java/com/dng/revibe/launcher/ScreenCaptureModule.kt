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
 * 关键设计：Android 14+ 的 MediaProjection token 是"单次使用"的——
 * 一旦调用 stop() 就必须重新授权。为了"授权一次、之后免打扰"：
 *   - 停止预览 = 暂停：只关闭 ImageReader 并 setSurface(null)，保留 MediaProjection + VirtualDisplay + 前台服务
 *   - 再次预览 = 恢复：重建 ImageReader 并 setSurface()，全程不再弹授权框
 *   - 只有 Activity 销毁 / token 被系统回收时才真正 stop() 回收 token
 * 代价：暂停期间前台服务保活（系统常驻"屏幕预览"通知），否则 token 失效需重新授权。
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

    /** MediaProjection token 是否仍然有效（未被 stop / 未被系统回收） */
    @Volatile
    private var projectionActive = false
    private var lastFrameTime = 0L

    private var pendingStartCallback: String? = null

    // ==================== JS 可调用接口 ====================

    /** 开始屏幕预览：优先复用已授权 token（不弹窗），否则走 MediaProjection 授权 */
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

        // 已有存活 token：直接恢复，无需再次授权
        val proj = mediaProjection
        if (projectionActive && proj != null) {
            resumeCapture(activity, proj, callbackId)
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

    /** 停止屏幕预览（暂停模式：保留 token，恢复无需再授权） */
    fun stopPreview(callbackId: String) {
        pauseCapture()
        callbackResult(callbackId, true, "预览已暂停，随时可恢复（无需再次授权）", false)
    }

    /** 查询是否正在预览 */
    fun isPreviewing(): String = gson.toJson(mapOf("capturing" to isCapturing))

    /**
     * 一键开启免授权（AppOps PROJECT_MEDIA=allow）
     *
     * 原理：系统授权对话框与 Android 14 前台服务检查都会查询 AppOps OP_PROJECT_MEDIA(46)。
     * 设为 allow 后：授权对话框自动通过（不弹窗）、无需前台服务，真正免授权。
     * 需要 Shizuku（shell 权限）或 Root 执行，普通 App 权限无法修改自身 AppOps。
     */
    fun enableNoAuth(callbackId: String) {
        val pkg = bridge.getContext()?.packageName ?: "com.dng.revibe.launcher"
        val cmd = "appops set $pkg android:project_media allow 2>&1; echo '---VERIFY---'; appops get $pkg android:project_media 2>&1"

        // 串行尝试：Shizuku → Root(su)，都失败则给出完整诊断
        tryShizuku(callbackId, cmd, pkg, StringBuilder())
    }

    private fun tryShizuku(callbackId: String, cmd: String, pkg: String, attempts: StringBuilder) {
        if (!ShizukuAPI.isConnected()) {
            Log.w(TAG, "Shizuku 未连接")
            attempts.append("• Shizuku：未连接\n")
            tryRoot(callbackId, cmd, pkg, attempts)
            return
        }
        if (!ShizukuAPI.isPermissionGranted()) {
            Log.w(TAG, "Shizuku 未授权，弹出授权界面等待反馈")
            attempts.append("• Shizuku：应用未授权，已请求授权\n")
            val started = ShizukuAPI.requestPermission(10086) { granted ->
                if (granted) {
                    // 授权成功 → 继续执行 appops
                    Log.i(TAG, "Shizuku 授权成功，继续执行")
                    ShizukuAPI.execute(cmd) { r ->
                        if (verifyNoAuthAllowed(r.stdout, r.stderr)) {
                            Log.i(TAG, "免授权开启成功（Shizuku）")
                            callbackResult(callbackId, true, "🔓 免授权已开启（PROJECT_MEDIA=allow），预览不再弹窗", false)
                        } else {
                            attempts.append("• Shizuku：退出码 ${r.statusCode} ${stderrBrief(r.stderr)}\n")
                            tryRoot(callbackId, cmd, pkg, attempts)
                        }
                    }
                } else {
                    // 用户拒绝 / 超时 → fallback Root
                    Log.w(TAG, "Shizuku 授权被拒绝或超时")
                    attempts.append("• Shizuku：授权被拒绝或超时\n")
                    tryRoot(callbackId, cmd, pkg, attempts)
                }
            }
            if (!started) {
                // 授权界面都弹不出来（异常）→ 直接 fallback Root
                attempts.append("• Shizuku：无法弹出授权界面\n")
                tryRoot(callbackId, cmd, pkg, attempts)
            }
            return
        }
        Log.i(TAG, "通过 Shizuku 尝试开启免授权")
        ShizukuAPI.execute(cmd) { r ->
            if (verifyNoAuthAllowed(r.stdout, r.stderr)) {
                Log.i(TAG, "免授权开启成功（Shizuku）")
                callbackResult(callbackId, true, "🔓 免授权已开启（PROJECT_MEDIA=allow），预览不再弹窗", false)
            } else {
                attempts.append("• Shizuku：退出码 ${r.statusCode} ${stderrBrief(r.stderr)}\n")
                tryRoot(callbackId, cmd, pkg, attempts)
            }
        }
    }

    private fun tryRoot(callbackId: String, cmd: String, pkg: String, attempts: StringBuilder) {
        Log.i(TAG, "尝试通过 Root(su) 开启免授权")
        Shell.execute("su -c \"$cmd\" 2>&1") { r ->
            if (verifyNoAuthAllowed(r.stdout, r.stderr)) {
                Log.i(TAG, "免授权开启成功（Root）")
                callbackResult(callbackId, true, "🔓 免授权已开启（PROJECT_MEDIA=allow），预览不再弹窗", false)
            } else {
                // ---VERIFY--- 之前的输出是 su/appops 自身的输出与错误（含被 2>&1 合并的 stderr）
                val cmdOut = r.stdout.substringBefore("---VERIFY---").trim()
                val errOut = r.stderr.trim()
                attempts.append("• Root(su)：退出码 ${r.statusCode}")
                if (cmdOut.isNotEmpty()) attempts.append(" 输出:${cmdOut.take(150).replace("\n", " ")}")
                if (errOut.isNotEmpty()) attempts.append(" 错误:${errOut.take(150).replace("\n", " ")}")
                Log.w(TAG, "免授权开启失败：\n$attempts")
                callbackResult(
                    callbackId, false,
                    "❌ 免授权开启失败\n${attempts}\n可手动 ADB：adb shell appops set $pkg android:project_media allow",
                    false
                )
            }
        }
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
            projectionActive = true
            startCaptureLoop(activity, projection, callbackId)
            callbackResult(callbackId, true, "屏幕预览已启动", true)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection 失败", e)
            stopForegroundService()
            callbackResult(callbackId, false, "启动失败: ${e.message}", false)
        }
    }

    // ==================== 截屏流核心 ====================

    /** 首次启动：创建 VirtualDisplay */
    private fun startCaptureLoop(activity: Activity, projection: MediaProjection, callbackId: String?) {
        val (pw, ph, density) = computePreviewSize(activity)

        captureThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 3).also { reader ->
            reader.setOnImageAvailableListener(imageListener, captureHandler)
        }

        // 监听投影停止（如系统回收 token / 用户从状态栏终止投屏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { projectionCallback?.let { projection.unregisterCallback(it) } } catch (_: Exception) {}
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    onProjectionStopped()
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
            releaseInternal()
            stopForegroundService()
            callbackResult(callbackId, false, "创建虚拟显示器失败: ${e.message}", false)
        }
    }

    /** 恢复：复用已有 token 与 VirtualDisplay，重建 ImageReader 并重新挂 surface */
    private fun resumeCapture(activity: Activity, projection: MediaProjection, callbackId: String) {
        val vd = virtualDisplay
        if (vd == null) {
            // token 还在但虚拟显示器没了，退化为首次启动（无需重新授权）
            startCaptureLoop(activity, projection, callbackId)
            callbackResult(callbackId, true, "屏幕预览已启动", true)
            return
        }
        val (pw, ph, _) = computePreviewSize(activity)

        captureThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 3).also { reader ->
            reader.setOnImageAvailableListener(imageListener, captureHandler)
        }

        try {
            vd.setSurface(imageReader?.surface)
        } catch (e: Exception) {
            Log.e(TAG, "恢复 surface 失败", e)
            pauseCapture()
            callbackResult(callbackId, false, "恢复预览失败: ${e.message}", false)
            return
        }
        isCapturing = true
        lastFrameTime = 0L
        Log.i(TAG, "屏幕预览已恢复（复用 token，未弹授权框）")
        callbackResult(callbackId, true, "屏幕预览已启动（免授权恢复）", true)
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

    // ==================== 暂停 / 停止 / 清理 ====================

    /** 暂停：保留 token + VirtualDisplay + 前台服务，仅停帧 */
    private fun pauseCapture() {
        if (!isCapturing && imageReader == null) return
        isCapturing = false

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        // 断开渲染 surface（虚拟显示器保留，token 保活）
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}

        captureHandler?.removeCallbacksAndMessages(null)
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null

        lastFrameTime = 0L
        Log.i(TAG, "屏幕预览已暂停（token 保活）")
    }

    /** token 被系统回收时调用 */
    private fun onProjectionStopped() {
        projectionActive = false
        pauseCapture()
        releaseInternal()
        stopForegroundService()
    }

    /** 彻底释放所有资源（Activity 销毁 / 授权失败 / token 回收） */
    private fun releaseInternal() {
        projectionActive = false
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
        Log.i(TAG, "屏幕预览资源已彻底释放")
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        bridge.getContext()?.let { ctx ->
            try { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) } catch (_: Exception) {}
        }
    }

    /** 供 MainActivity.onDestroy 调用，彻底清理 */
    fun release() {
        releaseInternal()
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
