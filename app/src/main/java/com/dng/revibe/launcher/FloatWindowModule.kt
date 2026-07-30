package com.dng.revibe.launcher

import android.webkit.JavascriptInterface
import com.google.gson.Gson

/**
 * 悬浮窗模块 — 单实例管理，横竖屏自适应
 *
 * 使用 companion object 持有唯一的 FloatWindow 实例，
 * 即使 Activity 重建也不会创建新窗口。
 */
class FloatWindowModule(private val bridge: JsBridge) {

    companion object {
        private var floatWindow: FloatWindow? = null
    }

    private val gson = Gson()

    /**
     * 显示悬浮窗
     * 首次调用创建窗口，后续调用仅更新尺寸（横竖屏切换时）
     */
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

        floatWindow?.show {
            callbackResult(callbackId, true, "用户点击关闭")
        }

        if (floatWindow?.isShowing != true) {
            callbackResult(callbackId, false, "悬浮窗显示失败")
        }
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

    private fun callbackResult(callbackId: String, success: Boolean, message: String) {
        val result = mapOf(
            "callbackId" to callbackId,
            "success" to success,
            "message" to message
        )
        bridge.callback("_onFloatWindowTap", gson.toJson(result))
    }
}
