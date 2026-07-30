package com.dng.revibe.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color

/**
 * 悬浮窗管理 — 控制中心拉手
 *
 * 窗口本身只有 10% 屏幕高，放在屏幕顶部。
 * 内部容器总高 = 屏幕高 + 10%，通过 translationY 上移，
 * 只露出底部 10% 拉手。超出窗口部分被裁剪，不遮挡触摸。
 *
 * 后续下拉展开时，通过 updateLayout 动态改变窗口大小。
 */
class FloatWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var container: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var onTapCallback: (() -> Unit)? = null

    // 记录尺寸供后续动态调整
    private var screenH = 0
    private var contentHeight = 0
    private var tabHeight = 0

    fun show(onTap: (() -> Unit)? = null) {
        hide()

        this.onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dm = context.resources.displayMetrics
        screenH = dm.heightPixels
        contentHeight = screenH
        tabHeight = (screenH * 0.1f).toInt()

        // ---- 拉手区 ----
        val tab = FrameLayout(context).apply {
            setBackgroundColor(0xCC2A1E3C.toInt())
            val handleLabel = TextView(context).apply {
                text = "⋮ 点击关闭 ⋮"
                textSize = 13f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            addView(handleLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            setOnClickListener {
                hide()
                onTapCallback?.invoke()
            }
        }

        // ---- 内容区 ----
        val contentArea = FrameLayout(context).apply {
            setBackgroundColor(0xE61A1A2E.toInt())
            val label = TextView(context).apply {
                text = "下拉查看更多内容"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // ---- 内部容器（总高 110%，上移 100%） ----
        val totalHeight = (screenH * 1.1f).toInt()
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

        // ---- 根布局（和窗口一样大，仅 10%） ----
        rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(container!!, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                totalHeight
            ))
            // 根布局不设点击监听，触摸由内部拉手处理
            // 容器内容超出窗口的部分自然被裁剪，不拦截触摸
        }

        // ---- 窗口参数：窗口 = 10% 高 ----
        currentParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,  // 宽 100%
            tabHeight,                                  // 高 = 10%（窗口本身只有这么大）
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
    }

    val isShowing: Boolean get() = rootView != null

    /**
     * 动态调整窗口大小（供后续下拉手势使用）
     * @param newHeight 新的窗口高度（像素）
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

    /**
     * 获取容器引用（供动画使用）
     */
    fun getContainer(): View? = container
}
