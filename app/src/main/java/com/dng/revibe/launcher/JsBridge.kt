package com.dng.revibe.launcher

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

class JsBridge(context: Context, webView: WebView) {
    val contextRef = WeakReference(context)
    val webViewRef = WeakReference(webView)

    // 模块实例
    val permissionModule = PermissionModule(this)
    private val shellModule = ShellModule(this)
    private val shizukuModule = ShizukuModule(this)
    private val adminModule = AdminModule(this)

    // ============ PermissionModule 委托 ============
    @JavascriptInterface
    fun getPermissionStatus(): String = permissionModule.getAllStatus()

    @JavascriptInterface
    fun requestPermission(permKey: String, callbackId: String) =
        permissionModule.requestPermission(permKey, callbackId)

    // ============ ShellModule 委托 ============
    @JavascriptInterface
    fun execShell(command: String, callbackId: String) = shellModule.execShell(command, callbackId)

    // ============ ShizukuModule 委托 ============
    @JavascriptInterface
    fun shizukuIsConnected(): String = shizukuModule.isConnected()

    @JavascriptInterface
    fun shizukuExecShell(command: String, callbackId: String) = shizukuModule.execShell(command, callbackId)

    // ============ AdminModule 委托 ============
    @JavascriptInterface
    fun lockScreen(callbackId: String) = adminModule.lockScreen(callbackId)

    @JavascriptInterface
    fun isAdminActive(callbackId: String) = adminModule.isAdminActive(callbackId)

    // ============ Android 身份检测 ============
    @JavascriptInterface

    // ============ JS 回调 ============
    fun callback(funcName: String, jsonArg: String) {
        webViewRef.get()?.let { wv ->
            wv.post { wv.evaluateJavascript("window.$funcName($jsonArg);", null) }
        }
    }

    fun getContext(): Context? = contextRef.get()
}
