package com.dng.revibe.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class PermissionModule(private val bridge: JsBridge) {
    private val gson = Gson()

    @JavascriptInterface
    fun getAllStatus(): String {
        val context = bridge.getContext() ?: return "{}"

        val status = mapOf(
            // 运行时权限
            "locationFine" to checkPerm(context, Manifest.permission.ACCESS_FINE_LOCATION),
            "locationCoarse" to checkPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            "phoneState" to checkPerm(context, Manifest.permission.READ_PHONE_STATE),
            "camera" to checkPerm(context, Manifest.permission.CAMERA),
            "notifications" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                checkPerm(context, Manifest.permission.POST_NOTIFICATIONS) else true,

            // 特殊权限
            "writeSettings" to Settings.System.canWrite(context),
            "overlay" to Settings.canDrawOverlays(context),

            // 设备管理员
            "deviceAdmin" to Lock.isAdminActive(context),

            // Shizuku
            "shizuku" to ShizukuAPI.isConnected(),

            // 通知监听
            "notificationListener" to isNotificationListenerEnabled(context),

            // WiFi 状态
            "wifiState" to checkPerm(context, Manifest.permission.ACCESS_WIFI_STATE),
            "wifiChange" to checkPerm(context, Manifest.permission.CHANGE_WIFI_STATE),
            "networkState" to checkPerm(context, Manifest.permission.ACCESS_NETWORK_STATE)
        )

        return gson.toJson(status)
    }

    private fun checkPerm(context: Context, perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        val component = "${context.packageName}/${MusicNotificationListener::class.java.name}"
        return enabled?.contains(component) == true
    }
}
