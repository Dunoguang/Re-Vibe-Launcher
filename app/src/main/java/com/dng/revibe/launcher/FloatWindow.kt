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

    /** WebView 的 JS 接口：上滑检测后调用收回 */
    inner class ControlBridge {
        @JavascriptInterface
        fun collapse() { this@FloatWindow.collapse() }
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
            setBackgroundColor(0xCC2A1E3C.toInt())
            addView(TextView(appContext).apply {
                text = "\u22EE \u4E0B\u62C9\u5C55\u5F00 \u22EE"
                textSize = 13f; setTextColor(0xAAFFFFFF.toInt()); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // 内容区 — WebView + JS 上滑检测
        val contentArea = FrameLayout(appContext).apply {
            setBackgroundColor(0xE61A1A2E.toInt())
            val webView = android.webkit.WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(0xFF1A1A2E.toInt())
                addJavascriptInterface(ControlBridge(), "FloatControl")
                // 内嵌 HTML + 上滑检测 JS
                loadDataWithBaseURL(null, controlCenterHtml(), "text/html", "UTF-8", null)
            }
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        container = FrameLayout(appContext).apply {
            addView(contentArea, FrameLayout.LayoutParams(MATCH_PARENT, contentH).apply { gravity = Gravity.TOP })
            addView(tab, FrameLayout.LayoutParams(MATCH_PARENT, tabH).apply { gravity = Gravity.BOTTOM })
            translationY = -contentH.toFloat()
        }

        rootView = FrameLayout(appContext).apply {
            setBackgroundColor(0x00000000.toInt())
            addView(container!!, FrameLayout.LayoutParams(MATCH_PARENT, totalH))
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
        MATCH_PARENT, height,
        @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START; y = 0
        @Suppress("DEPRECATION") flags = flags or FLAG_LAYOUT_INSET_DECOR
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

    /** 生成控制中心 HTML + 上滑检测 JS */
    private fun controlCenterHtml(): String = """<!DOCTYPE html>
<html><head><meta charset=UTF-8>
<meta name=viewport content='width=device-width,initial-scale=1,user-scalable=no'>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,Segoe UI,sans-serif;background:linear-gradient(145deg,#1a1a2e,#16213e);color:#fff;height:100vh;padding:20px 16px;display:flex;flex-direction:column}
h1{font-size:22px;font-weight:300;text-align:center;margin-bottom:20px;background:linear-gradient(90deg,#ff6fd8,#ffb86c);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin-bottom:16px}
.card{background:rgba(255,255,255,.06);border-radius:14px;padding:16px 12px;text-align:center;backdrop-filter:blur(8px)}
.card .label{font-size:12px;opacity:.6}
.info{background:rgba(255,255,255,.04);border-radius:14px;padding:14px 16px;font-size:13px;opacity:.7;text-align:center}
</style></head>
<body>
<h1>CONTROL CENTER</h1>
<div class=info>Re-Vibe Launcher</div>
<div class=grid>
<div class=card><div class=label>WiFi</div></div>
<div class=card><div class=label>Volume</div></div>
<div class=card><div class=label>Bright</div></div>
<div class=card><div class=label>Rotate</div></div>
<div class=card><div class=label>Airplane</div></div>
<div class=card><div class=label>Flash</div></div>
</div>
<div class=info>Swipe up to close</div>
<script>
let touchStartY = 0;
let touched = false;
document.addEventListener('touchstart', function(e) {
    touchStartY = e.touches[0].clientY;
    touched = true;
}, {passive:true});
document.addEventListener('touchmove', function(e) {
    // Swallow to prevent page scroll
}, {passive:true});
document.addEventListener('touchend', function(e) {
    if (!touched) return;
    touched = false;
    var dy = e.changedTouches[0].clientY - touchStartY;
    if (dy < -window.innerHeight * 0.3) {
        FloatControl.collapse();
    }
}, {passive:true});
</script>
</body></html>"""
}
