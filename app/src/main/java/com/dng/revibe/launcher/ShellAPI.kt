package com.dng.revibe.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Shell API — 检测 root/shell 权限并提供命令执行能力
 *
 * 检测策略（按优先级）：
 * 1. su 二进制是否存在且可执行
 * 2. 能否通过 su 执行命令 (id 返回 uid=0)
 * 3. 回退到普通 shell (sh)
 *
 * 返回的 CommandResult 定义在 ShizukuAPI 中
 */
object ShellAPI {

    private const val TAG = "VibeShell"

    // 常见的 su 路径
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/su",
        "/data/local/tmp/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    /** su 二进制是否存在 */
    fun hasSuBinary(): Boolean {
        return SU_PATHS.any { path ->
            try {
                val file = java.io.File(path)
                file.exists() && file.canExecute()
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 能否通过 su 获取 root shell（执行 id 检查 uid=0） */
    fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val dos = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            dos.writeBytes("id\n")
            dos.writeBytes("exit\n")
            dos.flush()

            val output = reader.readLine()
            process.waitFor()

            output?.contains("uid=0") == true
        } catch (e: Exception) {
            Log.w(TAG, "checkRootAccess failed: ${e.message}")
            false
        }
    }

    /** 是否有 root 权限（su 存在 + 可执行 + uid=0） */
    fun isRootAvailable(): Boolean {
        return hasSuBinary() && checkRootAccess()
    }

    /** 普通 shell 是否可用 */
    fun isShellAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("sh")
            process.destroy()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过 root/su 执行命令
     */
    fun execViaRoot(command: String): ShizukuAPI.CommandResult {
        if (command.isBlank()) return ShizukuAPI.CommandResult("", "Command is empty", -1)
        if (!isRootAvailable()) return ShizukuAPI.CommandResult("", "Root is not available", -1)

        return try {
            val process = Runtime.getRuntime().exec("su")
            val dos = DataOutputStream(process.outputStream)
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            dos.writeBytes("$command\n")
            dos.writeBytes("exit\n")
            dos.flush()

            val stdout = stdoutReader.readText().trim()
            val stderr = stderrReader.readText().trim()
            val statusCode = process.waitFor()

            ShizukuAPI.CommandResult(stdout, stderr, statusCode)
        } catch (e: Exception) {
            ShizukuAPI.CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 通过普通 shell 执行命令（无需 root）
     */
    fun execViaShell(command: String): ShizukuAPI.CommandResult {
        if (command.isBlank()) return ShizukuAPI.CommandResult("", "Command is empty", -1)

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            val statusCode = process.waitFor()

            val stdout = stdoutReader.readText().trim()
            val stderr = stderrReader.readText().trim()

            ShizukuAPI.CommandResult(stdout, stderr, statusCode)
        } catch (e: Exception) {
            ShizukuAPI.CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 优先用 root，回退到普通 shell
     */
    fun exec(command: String): ShizukuAPI.CommandResult {
        return if (isRootAvailable()) {
            execViaRoot(command)
        } else {
            execViaShell(command)
        }
    }
}
