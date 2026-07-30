package com.dng.revibe.launcher

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class PermissionModule(private val bridge: JsBridge) {
    private val gson = Gson()

    // 存储待回调的 callbackId: requestCode -> callbackId
    private val pendingCallbacks = mutableMapOf<Int, String>()
    private var nextRequestCode = 5000

    // ==================== 查询状态 ====================

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

            // Shell / Root 权限
            "shell" to ShellAPI.isShellAvailable(),
            "root" to ShellAPI.isRootAvailable(),

            // 通知监听
            "notificationListener" to isNotificationListenerEnabled(context),

            // WiFi 状态
            "wifiState" to checkPerm(context, Manifest.permission.ACCESS_WIFI_STATE),
            "wifiChange" to checkPerm(context, Manifest.permission.CHANGE_WIFI_STATE),
            "networkState" to checkPerm(context, Manifest.permission.ACCESS_NETWORK_STATE)
        )

        return gson.toJson(status)
    }

    // ==================== 请求权限（给 JS 调用） ====================

    /**
     * 前端点击权限项时调用此方法。
     * @param permKey  权限标识（如 "locationFine", "writeSettings", "deviceAdmin" 等）
     * @param callbackId 前端回调 ID，结果通过 _onPermissionResult 返回
     */
    @JavascriptInterface
    fun requestPermission(permKey: String, callbackId: String) {
        val ctx = bridge.getContext() ?: return
        val activity = ctx as? Activity ?: run {
            callbackError(callbackId, "Context is not an Activity")
            return
        }

        when (permKey) {
            // ---- 运行时权限 ----
            "locationFine"    -> requestRuntime(activity, Manifest.permission.ACCESS_FINE_LOCATION, callbackId)
            "locationCoarse"  -> requestRuntime(activity, Manifest.permission.ACCESS_COARSE_LOCATION, callbackId)
            "phoneState"      -> requestRuntime(activity, Manifest.permission.READ_PHONE_STATE, callbackId)
            "camera"          -> requestRuntime(activity, Manifest.permission.CAMERA, callbackId)
            "notifications"   -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestRuntime(activity, Manifest.permission.POST_NOTIFICATIONS, callbackId)
                } else {
                    callbackSuccess(callbackId, "当前系统版本无需此权限")
                }
            }

            // ---- 特殊权限 ----
            "writeSettings"   -> requestWriteSettings(activity, callbackId)
            "overlay"         -> requestOverlay(activity, callbackId)

            // ---- 系统功能 ----
            "deviceAdmin"           -> requestDeviceAdmin(activity, callbackId)
            "notificationListener"  -> requestNotificationListener(activity, callbackId)
            "shizuku"               -> {
                callbackResult(callbackId, ShizukuAPI.isConnected(),
                    if (ShizukuAPI.isConnected()) "Shizuku 已连接" else "Shizuku 未连接，请打开 Shizuku App")
            }

            // ---- Shell / Root 权限 ----
            "shell" -> {
                val avail = ShellAPI.isShellAvailable()
                callbackResult(callbackId, avail,
                    if (avail) "Shell 可用" else "Shell 不可用（异常）")
            }
            "root" -> {
                val avail = ShellAPI.isRootAvailable()
                callbackResult(callbackId, avail,
                    if (avail) "Root 权限可用" else "无 Root 权限，可尝试 Shizuku")
            }

            // ---- WiFi 权限（通常已授予，直接返回状态） ----
            "wifiState"    -> returnPermStatus(activity, Manifest.permission.ACCESS_WIFI_STATE, callbackId)
            "wifiChange"   -> returnPermStatus(activity, Manifest.permission.CHANGE_WIFI_STATE, callbackId)
            "networkState" -> returnPermStatus(activity, Manifest.permission.ACCESS_NETWORK_STATE, callbackId)

            else -> callbackError(callbackId, "Unknown permission key: $permKey")
        }
    }

    // ==================== 内部请求方法 ====================

    private fun requestRuntime(activity: Activity, perm: String, callbackId: String) {
        if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) {
            callbackSuccess(callbackId, "已授权")
            return
        }
        val rc = nextRequestCode++
        pendingCallbacks[rc] = callbackId
        ActivityCompat.requestPermissions(activity, arrayOf(perm), rc)
    }

    private fun requestWriteSettings(activity: Activity, callbackId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)) {
            val rc = nextRequestCode++
            pendingCallbacks[rc] = callbackId
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, rc)
        } else {
            callbackSuccess(callbackId, "WRITE_SETTINGS 已授权")
        }
    }

    private fun requestOverlay(activity: Activity, callbackId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            val rc = nextRequestCode++
            pendingCallbacks[rc] = callbackId
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, rc)
        } else {
            callbackSuccess(callbackId, "悬浮窗权限已授权")
        }
    }

    private fun requestDeviceAdmin(activity: Activity, callbackId: String) {
        if (Lock.isAdminActive(activity)) {
            callbackSuccess(callbackId, "设备管理员已激活")
            return
        }
        val rc = nextRequestCode++
        pendingCallbacks[rc] = callbackId
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(activity, Admin::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活后 Re-Vibe Launcher 可锁定屏幕")
        }
        activity.startActivityForResult(intent, rc)
    }

    private fun requestNotificationListener(activity: Activity, callbackId: String) {
        if (isNotificationListenerEnabled(activity)) {
            callbackSuccess(callbackId, "通知监听已授权")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            callbackResult(callbackId, false,
                "请在设置中开启「Re-Vibe Launcher」的通知监听权限，然后返回刷新")
        } catch (e: Exception) {
            callbackError(callbackId, "无法打开通知监听设置: ${e.message}")
        }
    }

    // ==================== 结果处理（由 MainActivity 调用） ====================

    /**
     * 处理运行时权限请求结果
     */
    fun handleRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val callbackId = pendingCallbacks.remove(requestCode) ?: return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            callbackResult(callbackId, true, "✅ 授权成功")
        } else {
            val activity = bridge.getContext() as? Activity
            val shouldShowRationale = activity != null &&
                permissions.isNotEmpty() &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permissions[0])
            val msg = if (shouldShowRationale) "⛔ 已拒绝且不再询问，请在系统设置中手动授权"
                      else "❌ 授权被拒绝"
            callbackResult(callbackId, false, msg)
        }
    }

    /**
     * 处理 Activity 返回结果（WRITE_SETTINGS / OVERLAY / DEVICE_ADMIN）
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val callbackId = pendingCallbacks.remove(requestCode) ?: return
        val context = bridge.getContext() ?: run {
            callbackError(callbackId, "Context is null")
            return
        }

        when {
            Settings.System.canWrite(context) ->
                callbackResult(callbackId, true, "✅ WRITE_SETTINGS 已授权")
            Settings.canDrawOverlays(context) ->
                callbackResult(callbackId, true, "✅ 悬浮窗权限已授权")
            Lock.isAdminActive(context) ->
                callbackResult(callbackId, true, "✅ 设备管理员已激活")
            else ->
                callbackResult(callbackId, false, "❌ 授权未完成")
        }
    }

    // ==================== 工具方法 ====================

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

    private fun returnPermStatus(activity: Activity, perm: String, callbackId: String) {
        val granted = checkPerm(activity, perm)
        callbackResult(callbackId, granted,
            if (granted) "✅ 已授权" else "ℹ️ 未授权（此权限通常默认授予）")
    }

    // ==================== JS 回调 ====================

    private fun callbackResult(callbackId: String, success: Boolean, message: String) {
        val data = mapOf(
            "callbackId" to callbackId,
            "success" to success,
            "message" to message
        )
        bridge.callback("_onPermissionResult", gson.toJson(data))
    }

    private fun callbackSuccess(callbackId: String, message: String) {
        callbackResult(callbackId, true, message)
    }

    private fun callbackError(callbackId: String, message: String) {
        callbackResult(callbackId, false, message)
    }
}
