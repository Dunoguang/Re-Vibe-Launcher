package com.dng.revibe.launcher

import android.webkit.JavascriptInterface
import com.google.gson.Gson

/**
 * 悬浮窗模块
 *
 * 供前端调用显示/隐藏悬浮窗。
 * 需要 SYSTEM_ALERT_WINDOW 权限（前端可先请求 overlay 权限）。
 */
class FloatWindowModule(private val bridge: JsBridge) {
    private val gson = Gson()
    private var floatWindow: FloatWindow? = null

    /**
     * 显示悬浮窗
     * @param callbackId 回调 ID，点击拉手关闭时通过 _onFloatWindowTap 返回
     */
    @JavascriptInterface
    fun show(callbackId: String) {
        val context = bridge.getContext() ?: return

        if (!android.provider.Settings.canDrawOverlays(context)) {
            val result = mapOf(
                "callbackId" to callbackId,
                "success" to false,
                "message" to "需要悬浮窗权限"
            )
            bridge.callback("_onFloatWindowTap", gson.toJson(result))
            return
        }

        floatWindow?.hide()
        floatWindow = FloatWindow(context)

        floatWindow?.show {
            val result = mapOf(
                "callbackId" to callbackId,
                "success" to true,
                "message" to "用户点击关闭"
            )
            bridge.callback("_onFloatWindowTap", gson.toJson(result))
        }

        if (floatWindow?.isShowing != true) {
            val result = mapOf(
                "callbackId" to callbackId,
                "success" to false,
                "message" to "悬浮窗显示失败"
            )
            bridge.callback("_onFloatWindowTap", gson.toJson(result))
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
}
