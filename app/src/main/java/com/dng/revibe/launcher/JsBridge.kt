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
    private val floatWindowModule = FloatWindowModule(this)
    private val wifiModule = WifiModule(this)
    private val systemModule = SystemModule(this)
    private val mediaModule = MediaModule(this)
    private val infoModule = InfoModule(this)

    // ============ PermissionModule 委托 ============
    @JavascriptInterface
    fun getPermissionStatus(): String = permissionModule.getAllStatus()

    @JavascriptInterface
    fun requestPermission(permKey: String, callbackId: String) =
        permissionModule.requestPermission(permKey, callbackId)

    // ============ ShellModule 委托 ============
    @JavascriptInterface
    fun execShell(command: String, callbackId: String) = shellModule.execShell(command, callbackId)

    @JavascriptInterface
    fun getDeviceCapabilities(callbackId: String) = shellModule.getDeviceCapabilities(callbackId)

    @JavascriptInterface
    fun execPrivileged(command: String, callbackId: String) = shellModule.execPrivileged(command, callbackId)

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

    // ============ FloatWindowModule 委托 ============
    @JavascriptInterface
    fun showFloatWindow(callbackId: String) = floatWindowModule.show(callbackId)

    @JavascriptInterface
    fun hideFloatWindow() = floatWindowModule.hide()

    @JavascriptInterface
    fun recreateFloatWindow(callbackId: String) = floatWindowModule.recreate(callbackId)

    @JavascriptInterface
    fun isFloatWindowShowing(): String = floatWindowModule.isShowing()

    // ============ WifiModule 委托（控制中心） ============
    @JavascriptInterface
    fun getWifiState() = wifiModule.getWifiState()

    @JavascriptInterface
    fun setWifiEnabled(enable: Boolean) = wifiModule.setWifiEnabled(enable)

    @JavascriptInterface
    fun openWifiSettings() = wifiModule.openWifiSettings()

    @JavascriptInterface
    fun getCurrentWifiInfo() = wifiModule.getCurrentWifiInfo()

    // ============ SystemModule 委托（控制中心） ============
    @JavascriptInterface
    fun getBatteryLevel() = systemModule.getBatteryLevel()

    @JavascriptInterface
    fun isCharging() = systemModule.isCharging()

    @JavascriptInterface
    fun getMobileDataEnabled() = systemModule.getMobileDataEnabled()

    @JavascriptInterface
    fun setMobileDataEnabled(enabled: Boolean) = systemModule.setMobileDataEnabled(enabled)

    @JavascriptInterface
    fun getBrightness() = systemModule.getBrightness()

    @JavascriptInterface
    fun setBrightness(brightness: Int) = systemModule.setBrightness(brightness)

    @JavascriptInterface
    fun getVolume() = systemModule.getVolume()

    @JavascriptInterface
    fun setVolume(volume: Int) = systemModule.setVolume(volume)

    @JavascriptInterface
    fun toggleFlashlight() = systemModule.toggleFlashlight()

    @JavascriptInterface
    fun getFlashlightState() = systemModule.getFlashlightState()

    @JavascriptInterface
    fun setFlashlight(enabled: Boolean) = systemModule.setFlashlight(enabled)

    @JavascriptInterface
    fun lockScreen() = systemModule.lockScreen()

    @JavascriptInterface
    fun openSettings() = systemModule.openSettings()

    @JavascriptInterface
    fun openAirplaneModeSettings() = systemModule.openAirplaneModeSettings()

    @JavascriptInterface
    fun shareText(text: String) = systemModule.shareText(text)

    @JavascriptInterface
    fun getVolumeInfo() = systemModule.getVolumeInfo()

    @JavascriptInterface
    fun requestSettingsPermission() = systemModule.requestSettingsPermission()

    @JavascriptInterface
    fun canWriteSettings() = systemModule.canWriteSettings()

    @JavascriptInterface
    fun getSimInfo() = systemModule.getSimInfo()

    @JavascriptInterface
    fun hotspotEnabled() = systemModule.hotspotEnabled()

    // ============ MediaModule 委托（控制中心） ============
    @JavascriptInterface
    fun getMusicInfo() = mediaModule.getMusicInfo()

    @JavascriptInterface
    fun getMusicCoverUrl() = mediaModule.getMusicCoverUrl()

    @JavascriptInterface
    fun mediaPlayPause() = mediaModule.mediaPlayPause()

    @JavascriptInterface
    fun mediaNext() = mediaModule.mediaNext()

    @JavascriptInterface
    fun mediaPrevious() = mediaModule.mediaPrevious()

    // ============ InfoModule 委托（控制中心） ============
    @JavascriptInterface
    fun getSystemInfo() = infoModule.getSystemInfo()

    @JavascriptInterface
    fun getNetworkInfo() = infoModule.getNetworkInfo()

    // ============ JS 回调 ============
    fun callback(funcName: String, jsonArg: String) {
        webViewRef.get()?.let { wv ->
            wv.post { wv.evaluateJavascript("window.$funcName($jsonArg);", null) }
        }
    }

    fun getContext(): Context? = contextRef.get()
}
