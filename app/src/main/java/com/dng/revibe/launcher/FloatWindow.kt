package com.dng.revibe.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.TextView

class FloatWindow(context: Context) {

    private val appContext = context.applicationContext
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var container: FrameLayout? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var onTapCallback: (() -> Unit)? = null
    private var screenH = 0; private var contentH = 0
    private var tabH = 0; private var totalH = 0
    private var isDragging = false
    private var dragStartY = 0f; private var dragStartTransY = 0f
    private var dragStartWindowH = 0; private var lastDragDirection = 0

    companion object {
        private const val EXPAND_THRESHOLD = 0.15f
        private const val COLLAPSE_THRESHOLD = 0.85f
    }

    /** WebView 的 JS 接口 */
    inner class ControlBridge {
        @JavascriptInterface
        fun collapse() { this@FloatWindow.collapse() }

        @JavascriptInterface
        fun exec(command: String) {
            when (command) {
                "wifi", "data", "airplane", "flashlight" -> {
                    // 通过 Shell 执行系统命令
                    val cmd = when (command) {
                        "wifi" -> "svc wifi toggle"
                        "data" -> "svc data toggle"
                        "airplane" -> "settings put global airplane_mode_on " +
                            if (android.provider.Settings.Global.getInt(
                                    appContext.contentResolver,
                                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
                                ) == 1) "0" else "1"
                        "flashlight" -> "cmd flashlight set " +
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) "1" else "0"
                        else -> ""
                    }
                    if (cmd.isNotBlank()) {
                        Shell.execute(cmd) {}
                    }
                    if (command == "airplane") {
                        // 需要发送广播让系统识别
                        android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED).also {
                            it.putExtra("state", 
                                android.provider.Settings.Global.getInt(
                                    appContext.contentResolver,
                                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
                                ) == 1)
                        }
                    }
                }
                "lock" -> {
                    // 锁屏
                    val km = appContext.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (km.isKeyguardSecure) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                val activity = appContext as? android.app.Activity
                                activity?.finishAndRemoveTask()
                            } catch (_: Exception) {}
                        }
                    }
                }
                "settings" -> {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { appContext.startActivity(intent) } catch (_: Exception) {}
                }
                "playpause", "prev", "next" -> {
                    // 模拟媒体键
                    val action = when (command) {
                        "playpause" -> android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY // 不精确
                        else -> null
                    }
                }
            }
        }

        @JavascriptInterface
        fun getBrightness(): Int {
            return try {
                android.provider.Settings.System.getInt(
                    appContext.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (_: Exception) { 128 }
        }

        @JavascriptInterface
        fun getVolume(): Int {
            return try {
                val am = appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (max > 0) (cur * 100 / max) else 50
            } catch (_: Exception) { 50 }
        }
    }

    fun show(onTap: (() -> Unit)? = null, onResult: ((Boolean) -> Unit)? = null) {
        this.onTapCallback = onTap
        if (rootView != null) { updateSize(); onResult?.invoke(true); return }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                showInternal(); onResult?.invoke(rootView != null)
            }
            return
        }
        showInternal(); onResult?.invoke(rootView != null)
    }

    private fun showInternal() {
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        recalcDimensions()

        // 拉手
        val tab = FrameLayout(appContext).apply {
            setBackgroundColor(0x00000000.toInt())
        }

        // 内容区 — WebView + JS 上滑检测
        val contentArea = FrameLayout(appContext).apply {
            setBackgroundColor(0x00000000.toInt())
            val webView = android.webkit.WebView(appContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    domStorageEnabled = true
                    allowUniversalAccessFromFileURLs = true
                    allowFileAccessFromFileURLs = true
                    // 与主 WebView 一致，不用 wide viewport（避免破坏 backdrop-filter 合成）
                    loadWithOverviewMode = false
                    useWideViewPort = false
                }
                setBackgroundColor(0x01FFFFFF.toInt())
                addJavascriptInterface(ControlBridge(), "FloatControl")
                loadUrl("file:///android_asset/control_center.html")
            }
            addView(webView, FrameLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        }

        container = FrameLayout(appContext).apply {
            addView(contentArea, FrameLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, contentH).apply { gravity = Gravity.TOP })
            addView(tab, FrameLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, tabH).apply { gravity = Gravity.BOTTOM })
            translationY = -contentH.toFloat()
        }

        rootView = FrameLayout(appContext).apply {
            setBackgroundColor(0x00000000.toInt())
            addView(container!!, FrameLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, totalH))
            setOnTouchListener(touchListener)
        }

        currentParams = buildParams(tabH)
        try { windowManager?.addView(rootView, currentParams!!) }
        catch (e: SecurityException) { cleanup() }
        catch (e: Exception) { cleanup() }
    }

    fun hide() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { hideInternal() }
            return
        }
        hideInternal()
    }

    private fun hideInternal() {
        rootView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        cleanup()
    }

    val isShowing: Boolean get() = rootView != null

    fun updateSize() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { updateSizeInternal() }
            return
        }
        updateSizeInternal()
    }

    private fun updateSizeInternal() {
        recalcDimensions()
        val p = currentParams ?: return; val v = rootView ?: return; val wm = windowManager ?: return
        val newP = buildParams(if (p.height > tabH) screenH else tabH)
        p.width = newP.width; p.height = newP.height
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    fun expand() { animateTo(0f, screenH, null) }
    fun collapse() { animateTo(-contentH.toFloat(), tabH, null) }

    // ==================== 手势 ====================

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStartY = event.rawY
                dragStartTransY = container?.translationY ?: 0f
                dragStartWindowH = currentParams?.height ?: tabH
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return@OnTouchListener false
                applyDrag(event.rawY - dragStartY); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return@OnTouchListener false
                isDragging = false; snapDrag(); true
            }
            else -> false
        }
    }

    private fun applyDrag(dy: Float) {
        val c = container ?: return; val p = currentParams ?: return
        val v = rootView ?: return; val wm = windowManager ?: return
        lastDragDirection = if (dy > 0) 1 else -1
        val transY = (dragStartTransY + dy).coerceIn(-contentH.toFloat(), 0f)
        val progress = 1f - (transY / -contentH)
        p.height = (tabH + progress * (screenH - tabH)).toInt()
        c.translationY = transY
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun snapDrag() {
        val c = container ?: return; val p = currentParams ?: return
        if (kotlin.math.abs(c.translationY - dragStartTransY) < 20f && p.height <= tabH + 20) {
            hide(); onTapCallback?.invoke(); return
        }
        val progress = 1f - (c.translationY / -contentH)
        if (lastDragDirection > 0) {
            if (progress > EXPAND_THRESHOLD) expand()
            else animateTo(-contentH.toFloat(), tabH, null)
        } else {
            if (progress < COLLAPSE_THRESHOLD) animateTo(-contentH.toFloat(), tabH, null)
            else expand()
        }
    }

    // ==================== 动画 ====================

    private fun animateTo(targetTransY: Float, targetWindowH: Int, onEnd: (() -> Unit)? = null) {
        val c = container ?: return; val p = currentParams ?: return
        val v = rootView ?: return; val wm = windowManager ?: return
        val startTransY = c.translationY; val startWinH = p.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                c.translationY = startTransY + (targetTransY - startTransY) * f
                p.height = (startWinH + (targetWindowH - startWinH) * f).toInt()
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onEnd?.invoke() }
            })
        }.start()
    }

    // ==================== 工具 ====================

    private fun recalcDimensions() {
        screenH = getScreenHeight()
        tabH = (screenH * 0.1f).toInt(); contentH = screenH; totalH = (screenH * 1.1f).toInt()
    }

    private fun buildParams(height: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, height,
        @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START; y = 0
        @Suppress("DEPRECATION")
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
    }

    @Suppress("DEPRECATION")
    private fun getScreenHeight(): Int {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = android.util.DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m); m.heightPixels
        } else {
            val pt = android.graphics.Point(); wm.defaultDisplay.getRealSize(pt); pt.y
        }
    }

    private fun cleanup() {
        rootView = null; container = null; currentParams = null
        onTapCallback = null; isDragging = false
    }

}
