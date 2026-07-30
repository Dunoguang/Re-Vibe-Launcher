package com.dng.revibe.launcher

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 悬浮窗 — 单个实例，自适应横竖屏
 *
 * 使用 applicationContext 避免 Activity 重建后失效。
 * 横竖屏切换时通过 ComponentCallbacks 自动重新计算尺寸。
 *
 * 高 = 屏幕高 × 10%，宽 = 100%
 */
class FloatWindow(context: Context) {

    // 使用 applicationContext 以跨 Activity 生命周期
    private val appContext: Context = context.applicationContext

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var onTapCallback: (() -> Unit)? = null
    private var configCallback: ComponentCallbacks? = null

    fun show(onTap: (() -> Unit)? = null) {
        this.onTapCallback = onTap

        if (rootView != null) {
            // 已有窗口 → 仅更新尺寸（横竖屏切换）
            updateLayout()
            return
        }

        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // ---- 拉手视图 ----
        val tab = FrameLayout(appContext).apply {
            setBackgroundColor(0xCC1A1A2E.toInt())

            val label = TextView(appContext).apply {
                text = "点击关闭悬浮窗"
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

        rootView = tab
        currentParams = createLayoutParams()

        try {
            windowManager?.addView(rootView, currentParams!!)
            registerConfigCallback()
        } catch (e: SecurityException) {
            cleanup()
        } catch (e: Exception) {
            cleanup()
        }
    }

    fun hide() {
        unregisterConfigCallback()
        rootView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        cleanup()
    }

    val isShowing: Boolean get() = rootView != null

    // ==================== 横竖屏自适应 ====================

    private fun registerConfigCallback() {
        unregisterConfigCallback()
        configCallback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                updateLayout()
            }
            override fun onLowMemory() {}
        }
        appContext.registerComponentCallbacks(configCallback!!)
    }

    private fun unregisterConfigCallback() {
        configCallback?.let { cb ->
            try { appContext.unregisterComponentCallbacks(cb) } catch (_: Exception) {}
            configCallback = null
        }
    }

    private fun updateLayout() {
        val params = currentParams ?: return
        val view = rootView ?: return
        val wm = windowManager ?: return

        val newP = createLayoutParams()
        params.width = newP.width
        params.height = newP.height

        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val realHeight = getScreenHeight()
        val barHeight = (realHeight * 0.1f).toInt()

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeight,
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
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = android.util.DisplayMetrics()
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        } else {
            @Suppress("DEPRECATION")
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = android.graphics.Point()
            wm.defaultDisplay.getRealSize(size)
            size.y
        }
    }

    private fun cleanup() {
        rootView = null
        currentParams = null
        onTapCallback = null
    }
}
