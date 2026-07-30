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
 * 悬浮窗管理
 *
 * 整个面板比屏幕高 10%，默认只露出底部 10% 的「拉手」在屏幕顶部。
 * 其余 100% 内容隐藏在屏幕上方，后续可通过下拉手势展示完整面板。
 *
 * 宽 = 100% 屏幕宽，高 = 屏幕高 + 10%
 * 横屏竖屏均正常工作（每次 show 时重新计算）
 */
class FloatWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var onTapCallback: (() -> Unit)? = null

    /**
     * 显示悬浮窗
     * @param onTap 点击可见区域时的回调（后续会改为下拉手势）
     */
    fun show(onTap: (() -> Unit)? = null) {
        hide()

        this.onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = context.resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        // 总高度 = 屏幕高度 + 10% (= 多出 10% 作为可见拉手)
        val totalHeight = (screenH * 1.1f).toInt()
        // 可见拉手区域的高度 = 10% 屏幕高
        val tabHeight = (screenH * 0.1f).toInt()
        // 隐藏的内容区高度 = 100% 屏幕高
        val contentHeight = screenH

        // 创建总面板
        val panel = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT) // 整体透明

            // ---- 内容区（占 100% 屏幕高，默认在屏幕上方隐藏） ----
            val contentArea = FrameLayout(context).apply {
                setBackgroundColor(0xE61A1A2E.toInt()) // 深色背景

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
            addView(contentArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                contentHeight
            ))

            // ---- 拉手区（占 10% 屏幕高，默认可见） ----
            val tab = FrameLayout(context).apply {
                setBackgroundColor(0xCC2A1E3C.toInt()) // 半透明深紫

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

                // 点击关闭
                setOnClickListener {
                    hide()
                    onTapCallback?.invoke()
                }
            }
            // 拉手区定位在面板底部
            val tabLayoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                tabHeight
            ).apply {
                gravity = Gravity.BOTTOM
            }
            addView(tab, tabLayoutParams)
        }

        // ---- 窗口参数 ----
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,  // 宽 100%
            totalHeight,                               // 高 = 屏幕高 + 10%
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
            // y = -屏幕高度 → 面板上移，只露出底部拉手
            y = -screenH
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        }

        try {
            windowManager?.addView(panel, params)
            floatView = panel
        } catch (e: SecurityException) {
            onTapCallback = null
        } catch (e: Exception) {
            onTapCallback = null
        }
    }

    fun hide() {
        floatView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
            floatView = null
        }
        onTapCallback = null
    }

    val isShowing: Boolean get() = floatView != null
}
