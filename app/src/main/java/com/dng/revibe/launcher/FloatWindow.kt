package com.dng.revibe.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 悬浮窗管理
 *
 * 高 = 屏幕高度的 10%，宽 = 100%
 * 点击悬浮窗内部 → 关闭并回调 JS
 */
class FloatWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var onTapCallback: (() -> Unit)? = null

    /**
     * 显示悬浮窗
     * @param message 显示的文字
     * @param onTap   点击时的回调
     */
    fun show(message: String = "Re-Vibe Launcher", onTap: (() -> Unit)? = null) {
        hide() // 确保没有旧实例

        this.onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val barHeight = (screenHeight * 0.1f).toInt()

        // 创建内容视图
        val contentView = FrameLayout(context).apply {
            setBackgroundColor(0xCC1A1A2E.toInt()) // 深色半透明

            val label = TextView(context).apply {
                text = message
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnClickListener {
                hide()
                onTapCallback?.invoke()
            }
        }

        // 布局参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,  // 宽 100%
            barHeight,                                 // 高 10%
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
            // 确保在状态栏下方
            y = 0
            // 设置半透明状态栏区域的 flags
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        }

        try {
            windowManager?.addView(contentView, params)
            floatView = contentView
        } catch (e: SecurityException) {
            // 没有悬浮窗权限
            onTapCallback = null
        } catch (e: Exception) {
            onTapCallback = null
        }
    }

    /**
     * 隐藏悬浮窗
     */
    fun hide() {
        floatView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
            floatView = null
        }
        onTapCallback = null
    }

    /**
     * 悬浮窗是否正在显示
     */
    val isShowing: Boolean get() = floatView != null
}
