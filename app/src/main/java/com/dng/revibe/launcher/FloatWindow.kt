package com.dng.revibe.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.animation.DecelerateInterpolator

/**
 * 悬浮窗管理 — 控制中心拉手
 *
 * 窗口本身只有 10% 屏幕高，放在屏幕顶部。
 * 内部容器总高 = 屏幕高 + 10%，通过 translationY 上移，
 * 只露出底部 10% 拉手。
 *
 * 下拉手势：拖动拉手展开全屏面板 → 上拉复位。
 */
class FloatWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var container: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var onTapCallback: (() -> Unit)? = null

    private var screenH = 0
    private var totalHeight = 0
    private var contentHeight = 0
    private var tabHeight = 0

    // 手势状态
    private var isDragging = false
    private var dragStartY = 0f
    private var dragStartTranslationY = 0f
    private var dragStartWindowH = 0

    companion object {
        private const val SNAP_THRESHOLD = 0.3f  // 滑动超过 30% 即展开/收起
    }

    fun show(onTap: (() -> Unit)? = null) {
        hide()

        this.onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = android.graphics.Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            screenH = metrics.heightPixels
        } else {
            @Suppress("DEPRECATION")
            display.getRealSize(realSize)
            screenH = realSize.y
        }
        contentHeight = screenH
        tabHeight = (screenH * 0.1f).toInt()
        totalHeight = (screenH * 1.1f).toInt()

        // ---- 拉手区 ----
        val tab = FrameLayout(context).apply {
            setBackgroundColor(0xCC2A1E3C.toInt())
            val handleLabel = TextView(context).apply {
                text = "⋮ 下拉展开 ⋮"
                textSize = 13f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            addView(handleLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // ---- 内容区 ----
        val contentArea = FrameLayout(context).apply {
            setBackgroundColor(0xE61A1A2E.toInt())
            val label = TextView(context).apply {
                text = "控制中心"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // ---- 内部容器 ----
        container = FrameLayout(context).apply {
            addView(contentArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                contentHeight
            ).apply { gravity = Gravity.TOP })

            addView(tab, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                tabHeight
            ).apply { gravity = Gravity.BOTTOM })

            translationY = -contentHeight.toFloat()
        }

        // ---- 根布局 ----
        rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(container!!, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                totalHeight
            ))

            // 触摸手势处理
            setOnTouchListener(handleTouch)
        }

        currentParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            tabHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
        }

        try {
            windowManager?.addView(rootView, currentParams!!)
        } catch (e: SecurityException) {
            onTapCallback = null
        } catch (e: Exception) {
            onTapCallback = null
        }
    }

    fun hide() {
        rootView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
            rootView = null
            container = null
            currentParams = null
        }
        onTapCallback = null
        isDragging = false
    }

    val isShowing: Boolean get() = rootView != null

    // ==================== 手势处理 ====================

    private val handleTouch = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStartY = event.rawY
                dragStartTranslationY = container?.translationY ?: 0f
                dragStartWindowH = currentParams?.height ?: tabHeight
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

    /**
     * 实时应用拖动偏移
     */
    private fun applyDrag(dy: Float) {
        val params = currentParams ?: return
        val c = container ?: return

        // 当前容器位置 (translationY 范围: -contentHeight ~ 0)
        val currentTransY = dragStartTranslationY + dy
        val clampedTransY = currentTransY.coerceIn(-contentHeight.toFloat(), 0f)

        // 窗口高度随拖动线性变化
        // translationY 从 -contentHeight → 0 时，窗口从 tabHeight → screenH
        val progress = 1f - (clampedTransY / -contentHeight) // 0~1
        val targetWindowH = (tabHeight + progress * (screenH - tabHeight)).toInt()

        c.translationY = clampedTransY
        params.height = targetWindowH
        windowManager?.updateViewLayout(rootView, params)
    }

    /**
     * 释放时自动吸附（展开或收起）
     */
    private fun snapDrag() {
        val c = container ?: return
        val currentTransY = c.translationY
        val progress = 1f - (currentTransY / -contentHeight) // 0~1

        val snapToExpand = progress > SNAP_THRESHOLD

        val targetTransY = if (snapToExpand) 0f else -contentHeight.toFloat()
        val targetWindowH = if (snapToExpand) screenH else tabHeight

        animateTo(targetTransY, targetWindowH, onTapCallback)

        if (!snapToExpand) {
            // 如果收起且在上面的回调中没有触发 onTap，这里不需要额外操作
        }
    }

    /**
     * 平滑动画到目标状态
     */
    private fun animateTo(targetTransY: Float, targetWindowH: Int, onDone: (() -> Unit)? = null) {
        val c = container ?: return
        val params = currentParams ?: return
        val startTransY = c.translationY
        val startWindowH = params.height

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                c.translationY = startTransY + (targetTransY - startTransY) * fraction
                params.height = (startWindowH + (targetWindowH - startWindowH) * fraction).toInt()
                windowManager?.updateViewLayout(rootView, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onDone?.invoke()
                }
            })
        }
        animator.start()
    }

    // ==================== 外部控制接口 ====================

    /**
     * 展开到全屏
     */
    fun expand() {
        animateTo(0f, screenH, null)
    }

    /**
     * 收起到手柄
     */
    fun collapse() {
        animateTo(-contentHeight.toFloat(), tabHeight, null)
    }

    /**
     * 动态更新窗口大小（供外部直接调用）
     */
    fun updateHeight(newHeight: Int) {
        val params = currentParams ?: return
        val view = rootView ?: return
        if (newHeight == params.height) return
        params.height = newHeight
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }
}
