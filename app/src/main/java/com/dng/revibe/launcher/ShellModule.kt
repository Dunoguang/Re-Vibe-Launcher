package com.dng.revibe.launcher

import android.app.AppOpsManager
import android.content.Context
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

            // 最优特权通道（按 root → shell → su → shizuku 优先级）
            val channel = when {
                uid == 0 -> "root"
                uid == 2000 -> "shell"
                hasSu -> "su"
                ShizukuAPI.isConnected() && ShizukuAPI.isPermissionGranted() -> "shizuku"
                ShizukuAPI.isConnected() -> "shizuku_pending"
                else -> "none"
            }
            val data = base + mapOf(
                "callbackId" to callbackId,
                "apiLevel" to apiLevel,
                "hasSu" to hasSu,
                "channel" to channel
            )
            bridge.callback("_onDeviceCapabilities", gson.toJson(data))
        }
    }

    /**
     * 以最高可用权限执行命令（自动选择通道）：
     *   1. Root（uid=0）直连 Shell
     *   2. Shell（uid=2000）直连 Shell
     *   3. Shizuku（已授权）
     *   4. SU（su -c 提权）
     * 返回结果包含实际使用的 channel 字段。
     */
    @JavascriptInterface
    fun execPrivileged(command: String, callbackId: String) {
        val uid = Process.myUid()

        // 1. Root 直连
        if (uid == 0) {
            Shell.execute(command) { r -> callbackPrivileged(callbackId, "root", r.stdout, r.stderr, r.statusCode) }
            return
        }
        // 2. Shell 直连
        if (uid == 2000) {
            Shell.execute(command) { r -> callbackPrivileged(callbackId, "shell", r.stdout, r.stderr, r.statusCode) }
            return
        }
        // 3. Shizuku 已授权
        if (ShizukuAPI.isConnected() && ShizukuAPI.isPermissionGranted()) {
            ShizukuAPI.execute(command) { r -> callbackPrivileged(callbackId, "shizuku", r.stdout, r.stderr, r.statusCode) }
            return
        }
        // 4. SU 提权（先探测 su 二进制，避免直接 su -c 在无 root 设备上报错/挂起）
        Shell.execute("command -v su 2>/dev/null || echo NO_SU") { r ->
            val hasSu = r.stdout.trim().isNotEmpty() && r.stdout.trim() != "NO_SU"
            if (hasSu) {
                Shell.execute("su -c \"$command\" 2>&1") { rr ->
                    callbackPrivileged(callbackId, "su", rr.stdout, rr.stderr, rr.statusCode)
                }
            } else {
                callbackPrivileged(callbackId, "none", "", "无可用特权通道（非 root/shell uid，无 su，Shizuku 未授权）", -1)
            }
        }
    }

    private fun callbackPrivileged(callbackId: String, channel: String, stdout: String, stderr: String, statusCode: Int) {
        val data = mapOf(
            "callbackId" to callbackId,
            "channel" to channel,
            "stdout" to stdout,
            "stderr" to stderr,
            "statusCode" to statusCode
        )
        bridge.callback("_onPrivilegedResult", gson.toJson(data))
    }

    /**
     * 应用内查询自身 AppOps PROJECT_MEDIA 状态（无需 shell 权限）。
     * 注意：OP_PROJECT_MEDIA / OPSTR_PROJECT_MEDIA / MODE_* 多为 @hide 常量，编译不可见，
     * 因此使用字面值：op="android:project_media"，MODE_ALLOWED=0 / IGNORED=1 / DENIED=2 / DEFAULT=3。
     */
    private fun queryProjectMediaMode(ctx: Context?, uid: Int, pkg: String): String {
        if (ctx == null || pkg.isEmpty()) return "unknown"
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow("android:project_media", uid, pkg)
            when (mode) {
                0 -> "allow"    // MODE_ALLOWED
                2 -> "deny"     // MODE_DENIED
                3 -> "default"  // MODE_DEFAULT
                else -> "ignore" // MODE_IGNORED 等
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
}
