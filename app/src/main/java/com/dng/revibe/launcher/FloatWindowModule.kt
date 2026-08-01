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

    /** 前端调用：重新计算并更新窗口尺寸（横竖屏切换时） */
    @JavascriptInterface
    fun updateSize() {
        floatWindow?.updateSize()
    }

    /** 前端调用：捕获控制中心画面为 base64，通过 _onCCPreviewImage 回调给主界面 */
    @JavascriptInterface
    fun captureControlCenter(callbackId: String) {
        val fw = floatWindow ?: run {
            callbackPreview(callbackId, false, null, "控制中心未打开")
            return
        }
        try {
            val b64 = fw.capture()
            if (b64 != null) {
                callbackPreview(callbackId, true, b64, null)
            } else {
                callbackPreview(callbackId, false, null, "WebView 尺寸无效或未就绪")
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                // capture 需主线程，post 到主线程重试
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val b = fw.capture()
                        if (b != null) callbackPreview(callbackId, true, b, null)
                        else callbackPreview(callbackId, false, null, "WebView 尺寸无效")
                    } catch (e2: Exception) {
                        callbackPreview(callbackId, false, null, "捕获失败: ${e2.message}")
                    }
                }
            } else {
                callbackPreview(callbackId, false, null, "捕获异常: ${e.message}")
            }
        }
    }

    private fun callbackPreview(callbackId: String, success: Boolean, b64: String?, msg: String?) {
        val data = mapOf(
            "callbackId" to callbackId,
            "success" to success,
            "image" to (b64 ?: ""),
            "message" to (msg ?: "")
        )
        // 推到主界面前端
        bridge.callback("_onCCPreviewImage", gson.toJson(data))
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
