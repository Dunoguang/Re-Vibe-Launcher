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
 * 面板比屏幕高 10%，默认只露出底部 10% 的「拉手」在屏幕顶部。
 *
 * 实现方式：
 * - 窗口 = MATCH_PARENT × MATCH_PARENT（全屏），位置在屏幕内
 * - 窗口根布局内嵌一个总高 = 屏幕高 + 10% 的容器
 * - 该容器 translationY = -屏幕高 → 仅底部拉手区可见
 *
 * 横屏竖屏每次 show() 重新计算尺寸。
 */
class FloatWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var onTapCallback: (() -> Unit)? = null

    fun show(onTap: (() -> Unit)? = null) {
        hide()

        this.onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = context.resources.displayMetrics
        val screenH = displayMetrics.heightPixels

        // 面板总高 = 屏幕高 + 10%
        val totalHeight = (screenH * 1.1f).toInt()
        // 拉手高 = 10%
        val tabHeight = (screenH * 0.1f).toInt()
        // 内容区高 = 100%
        val contentHeight = screenH

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

        // ---- 总容器（内容区在上，拉手区在下，整体上移） ----
        val container = FrameLayout(context).apply {
            addView(contentArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                contentHeight
            ).apply { gravity = Gravity.TOP })

            addView(tab, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                tabHeight
            ).apply { gravity = Gravity.BOTTOM })

            // 上移整个容器，只露出底部拉手在屏幕可见区域
            translationY = -contentHeight.toFloat()
        }

        // ---- 窗口根布局（全屏，不做偏移） ----
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // 容器比窗口高，超出部分被窗口裁剪
            addView(container, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                totalHeight
            ))
        }

        // ---- 窗口参数 ----
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        }

        try {
            windowManager?.addView(root, params)
            floatView = root
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
