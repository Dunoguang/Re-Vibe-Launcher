package com.dng.revibe.launcher

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.JavascriptInterface
import com.google.gson.Gson

class ShellModule(private val bridge: JsBridge) {
    private val gson = Gson()

    @JavascriptInterface
    fun execShell(command: String, callbackId: String) {
        Shell.execute(command) { result ->
            val data = mapOf(
                "callbackId" to callbackId,
                "stdout" to result.stdout,
                "stderr" to result.stderr,
                "statusCode" to result.statusCode
            )
            bridge.callback("_onShellResult", gson.toJson(data))
        }
    }

    /**
     * 设备能力检测（升级版，参考原项目 detectCapabilities + AppOps/Shizuku 授权状态）
     * 同步部分：uid / root / shell / Shizuku 连接与授权 / AppOps PROJECT_MEDIA 状态
     * 异步部分：Android API level / su 二进制是否存在
     */
    @JavascriptInterface
    fun getDeviceCapabilities(callbackId: String) {
        val ctx = bridge.getContext()
        val uid = Process.myUid()
        val pkg = ctx?.packageName ?: ""

        val base = mapOf(
            "uid" to uid,
            "isRoot" to (uid == 0),
            "isShell" to (uid == 2000),
            "shizukuConnected" to ShizukuAPI.isConnected(),
            "shizukuGranted" to ShizukuAPI.isPermissionGranted(),
            "projectMedia" to queryProjectMediaMode(ctx, uid, pkg)
        )

        // 异步：API level + su 二进制（仅探测存在性，不实际提权，避免触发 Magisk 授权弹窗挂起）
        Shell.execute("getprop ro.build.version.sdk; echo '---'; command -v su 2>/dev/null || echo NO_SU") { result ->
            val lines = result.stdout.trim().split("\n").map { it.trim() }
            val apiLevel = lines.getOrNull(0)?.toIntOrNull() ?: -1
            val suBin = lines.getOrNull(1)
            val hasSu = !suBin.isNullOrEmpty() && suBin != "NO_SU"

            val data = base + mapOf(
                "callbackId" to callbackId,
                "apiLevel" to apiLevel,
                "hasSu" to hasSu
            )
            bridge.callback("_onDeviceCapabilities", gson.toJson(data))
        }
    }

    /** 应用内查询自身 AppOps PROJECT_MEDIA 状态（无需 shell 权限） */
    private fun queryProjectMediaMode(ctx: Context?, uid: Int, pkg: String): String {
        if (ctx == null || pkg.isEmpty()) return "unknown"
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PROJECT_MEDIA, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OP_PROJECT_MEDIA, uid, pkg)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_DENIED -> "deny"
                AppOpsManager.MODE_DEFAULT -> "default"
                else -> "ignore"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
}
