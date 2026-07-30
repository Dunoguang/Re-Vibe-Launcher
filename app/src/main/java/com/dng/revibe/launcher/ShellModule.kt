package com.dng.revibe.launcher

import android.webkit.JavascriptInterface
import com.google.gson.Gson

/**
 * Shell 执行模块 — 通过 root/su 或普通 sh 执行命令
 *
 * 与 ShizukuModule 互补：
 * - ShizukuModule: 通过 Shizuku API 执行（需要 Shizuku 服务）
 * - ShellModule:   直接通过 su/root 或 sh 执行（无需额外服务）
 */
class ShellModule(private val bridge: JsBridge) {
    private val gson = Gson()

    /**
     * 当前进程是否为 Shell 身份 (uid 2000)
     */
    @JavascriptInterface
    fun isShellUid(): String {
        return gson.toJson(mapOf("isShell" to ShellAPI.isShellUid()))
    }

    /**
     * 当前进程是否为 Root 身份 (uid 0)
     */
    @JavascriptInterface
    fun isRootUid(): String {
        return gson.toJson(mapOf("isRoot" to ShellAPI.isRootUid()))
    }

    /**
     * Root 是否可用（su 二进制 + 可执行）
     */
    @JavascriptInterface
    fun isRootAvailable(): String {
        return gson.toJson(mapOf("available" to ShellAPI.isRootAvailable()))
    }

    /**
     * sh 二进制是否可执行
     */
    @JavascriptInterface
    fun hasShBinary(): String {
        return gson.toJson(mapOf("available" to ShellAPI.hasShBinary()))
    }

    /**
     * 执行命令（自动选择 root → shell 回退）
     */
    @JavascriptInterface
    fun execShell(command: String, callbackId: String) {
        Thread {
            val result = ShellAPI.exec(command)
            val data = mapOf(
                "callbackId" to callbackId,
                "stdout" to result.stdout,
                "stderr" to result.stderr,
                "statusCode" to result.statusCode,
                "usedRoot" to ShellAPI.isRootAvailable()
            )
            bridge.callback("_onShellResult", gson.toJson(data))
        }.start()
    }
}
