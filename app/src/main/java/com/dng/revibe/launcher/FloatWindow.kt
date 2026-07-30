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
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 悬浮窗 — 下拉展开控制中心 / 上拉收回
 *
 * 窗口初始仅 10% 高（拉手），下拉时窗口升高、内容滑入。
 * 拖动超过 30% 阈值自动展开/收回。
 */
class FloatWindow(context: Context) {

    private val appContext = context.applicationContext

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var container: FrameLayout? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var onTapCallback: (() -> Unit)? = null

    // 尺寸缓存（横竖屏切换后重新计算）
    private var screenH = 0
    private var contentH = 0
    private var tabH = 0
    private var totalH = 0

    // 手势
    private var isDragging = false
    private var lastDragDirection = 0  // 1=向下(展开)  -1=向上(收回)
    private var dragStartY = 0f
    private var dragStartTransY = 0f
    private var dragStartWindowH = 0

    companion object {
        private const val EXPAND_THRESHOLD = 0.15f
        private const val COLLAPSE_THRESHOLD = 0.85f
        private val CONTROL_CENTER_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
<title>Control Center</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,Segoe UI,PingFang SC,sans-serif;background:linear-gradient(145deg,#1a1a2e,#16213e);color:#fff;height:100vh;padding:20px 16px 40px;display:flex;flex-direction:column}
h1{font-size:22px;font-weight:300;letter-spacing:4px;text-align:center;margin-bottom:20px;background:linear-gradient(90deg,#ff6fd8,#ffb86c);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}
.grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin-bottom:16px}
.card{background:rgba(255,255,255,0.06);border-radius:14px;padding:16px 12px;text-align:center;backdrop-filter:blur(8px);border:1px solid rgba(255,255,255,0.06)}
.card .icon{font-size:24px;margin-bottom:6px}
.card .label{font-size:12px;opacity:0.6}
.info{background:rgba(255,255,255,0.04);border-radius:14px;padding:14px 16px;margin-bottom:12px;font-size:13px;opacity:0.7;text-align:center;border:1px solid rgba(255,255,255,0.04)}
</style>
</head>
<body>
<h1>CONTROL CENTER</h1>
<div class="info">Re-Vibe Launcher</div>
<div class="grid">
<div class="card"><div class="icon">[W]</div><div class="label">WiFi</div></div>
<div class="card"><div class="icon">[V]</div><div class="label">Volume</div></div>
<div class="card"><div class="icon">[B]</div><div class="label">Bright</div></div>
<div class="card"><div class="icon">[R]</div><div class="label">Rotate</div></div>
<div class="card"><div class="icon">[A]</div><div class="label">Airplane</div></div>
<div class="card"><div class="icon">[F]</div><div class="label">Flash</div></div>
</div>
<div class="info">Swipe up to close</div>
</body>
</html>"""
    }

    // ==================== 公开接口 ====================

    fun show(onTap: (() -> Unit)? = null) {
        this.onTapCallback = onTap
        if (rootView != null) { updateSize(); return }

        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        recalcDimensions()

        // 拉手
        val tab = FrameLayout(appContext).apply {
            setBackgroundColor(0xCC2A1E3C.toInt())
            val label = TextView(appContext).apply {
                text = "⋮ 下拉展开 ⋮"
                textSize = 13f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // 内容区 — WebView 显示网页
        val contentArea = FrameLayout(appContext).apply {
            setBackgroundColor(0xE61A1A2E.toInt())
            val webView = android.webkit.WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(0xFF1A1A2E.toInt())
                loadDataWithBaseURL(null, CONTROL_CENTER_HTML, "text/html", "UTF-8", null)
            }
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // 容器（总高 110%，内容在上、拉手在下，整体上移）
        container = FrameLayout(appContext).apply {
            addView(contentArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, contentH
            ).apply { gravity = Gravity.TOP })
            addView(tab, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, tabH
            ).apply { gravity = Gravity.BOTTOM })
            translationY = -contentH.toFloat()
        }

        // 根布局（接收触摸）
        rootView = FrameLayout(appContext).apply {
            setBackgroundColor(0x00000000.toInt())
            addView(container!!, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, totalH
            ))
            setOnTouchListener(touchListener)
        }

        currentParams = buildParams(tabH)

        try {
            windowManager?.addView(rootView, currentParams!!)
        } catch (e: SecurityException) {
            cleanup()
        } catch (e: Exception) {
            cleanup()
        }
    }

    fun hide() {
        rootView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        cleanup()
    }

    val isShowing: Boolean get() = rootView != null

    fun updateSize() {
        recalcDimensions()
        val p = currentParams ?: return
        val v = rootView ?: return
        val wm = windowManager ?: return

        val newP = buildParams(if (p.height > tabH) screenH else tabH)
        p.width = newP.width
        p.height = newP.height
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    fun expand() {
        animateTo(0f, screenH, null)
    }

    fun collapse() {
        animateTo(-contentH.toFloat(), tabH, null)
    }

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
                val dy = event.rawY - dragStartY
                applyDrag(dy)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return@OnTouchListener false
                isDragging = false
                snapDrag()
                true
            }
            else -> false
        }
    }

    private fun applyDrag(dy: Float) {
        val c = container ?: return
        val p = currentParams ?: return
        val v = rootView ?: return
        val wm = windowManager ?: return

        // 记录拖动方向
        lastDragDirection = if (dy > 0) 1 else -1

        // translationY 范围: -contentH ~ 0
        val transY = (dragStartTransY + dy).coerceIn(-contentH.toFloat(), 0f)
        val progress = 1f - (transY / -contentH) // 0~1

        // 窗口高度从 tabH 到 screenH 线性变化
        val winH = (tabH + progress * (screenH - tabH)).toInt()

        c.translationY = transY
        p.height = winH
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun snapDrag() {
        val c = container ?: return
        val p = currentParams ?: return

        // 判断是否为点击（移动距离很小）
        val dragDistance = kotlin.math.abs(c.translationY - dragStartTransY)

        if (dragDistance < 20f && p.height <= tabH + 20) {
            hide()
            onTapCallback?.invoke()
            return
        }

        val progress = 1f - (c.translationY / -contentH)

        if (lastDragDirection > 0) {
            // 展开方向：只要拉了超过 15% 就展开
            if (progress > 0.15f) expand()
            else animateTo(-contentH.toFloat(), tabH, null)
        } else {
            // 收回方向：只要隐藏了超过 15% (progress < 85%) 就收回
            if (progress < 0.85f) animateTo(-contentH.toFloat(), tabH, null)
            else expand()
        }
    }

    // ==================== 动画 ====================

    private fun animateTo(targetTransY: Float, targetWindowH: Int, onEnd: (() -> Unit)? = null) {
        val c = container ?: return
        val p = currentParams ?: return
        val v = rootView ?: return
        val wm = windowManager ?: return
        val startTransY = c.translationY
        val startWinH = p.height

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                c.translationY = startTransY + (targetTransY - startTransY) * f
                p.height = (startWinH + (targetWindowH - startWinH) * f).toInt()
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
        }.start()
    }

    // ==================== 工具 ====================

    private fun recalcDimensions() {
        screenH = getScreenHeight()
        tabH = (screenH * 0.1f).toInt()
        contentH = screenH
        totalH = (screenH * 1.1f).toInt()
    }

    private fun buildParams(height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenHeight(): Int {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            m.heightPixels
        } else {
            val pt = android.graphics.Point()
            wm.defaultDisplay.getRealSize(pt)
            pt.y
        }
    }



    private fun cleanup() {
        rootView = null
        container = null
        currentParams = null
        onTapCallback = null
        isDragging = false
    }
}
