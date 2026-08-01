package com.dng.revibe.launcher

import android.webkit.JavascriptInterface
import com.google.gson.Gson

/**
 * 悬浮窗模块 — 单实例，由前端触发尺寸更新
 */
class FloatWindowModule(private val bridge: JsBridge) {

    companion object {
        private var floatWindow: FloatWindow? = null
    }

    private val gson = Gson()

    @JavascriptInterface
    fun show(callbackId: String) {
        val context = bridge.getContext() ?: return

        if (!android.provider.Settings.canDrawOverlays(context)) {
            callbackResult(callbackId, false, "需要悬浮窗权限")
            return
        }

        if (floatWindow == null) {
            floatWindow = FloatWindow(context)
        }

        floatWindow?.show(
            onTap = { callbackResult(callbackId, true, "用户点击关闭") },
            onResult = { success ->
                if (!success) {
                    callbackResult(callbackId, false, "悬浮窗显示失败")
                }
            }
        )
    }

    @JavascriptInterface
    fun hide() {
        floatWindow?.hide()
        floatWindow = null
    }

    @JavascriptInterface
    fun isShowing(): String {
        return gson.toJson(mapOf("showing" to (floatWindow?.isShowing == true)))
    }

    /** 前端调用：直接关闭再重新开启悬浮窗（彻底重建 WebView，横竖屏切换等场景使用） */
    @JavascriptInterface
    fun recreate(callbackId: String) {
        val context = bridge.getContext() ?: return

        // 先关闭
        floatWindow?.hide()
        floatWindow = null

        // 再重新开启（复用 show 逻辑）
        show(callbackId)
    }

    private fun callbackResult(callbackId: String, success: Boolean, message: String) {
        val result = mapOf(
            "callbackId" to callbackId,
            "success" to success,
            "message" to message
        )
        bridge.callback("_onFloatWindowTap", gson.toJson(result))
    }
}
