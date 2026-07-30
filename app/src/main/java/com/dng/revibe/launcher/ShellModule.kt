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
     * 检测 root 是否可用
     */
    @JavascriptInterface
    fun isRootAvailable(): String {
        val result = ShellAPI.isRootAvailable()
        return gson.toJson(mapOf("available" to result))
    }

    /**
     * 检测普通 shell 是否可用
     */
    @JavascriptInterface
    fun isShellAvailable(): String {
        val result = ShellAPI.isShellAvailable()
        return gson.toJson(mapOf("available" to result))
    }

    /**
     * 执行命令（自动选择 root → shell 回退）
     * @param command   要执行的命令
     * @param callbackId 回调 ID，结果通过 _onShellResult 返回
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
