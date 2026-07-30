package com.dng.revibe.launcher

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Shell API — 检测 Android shell 身份 / root 权限并提供命令执行能力
 *
 * Android 权限层级（从低到高）：
 * 1. 普通 App 进程        — uid ≥ 10000
 * 2. Shell 用户           — uid = 2000 (Process.SHELL_UID)，ADB 或 shell 提权
 * 3. Root 用户            — uid = 0，完整系统权限
 *
 * "Shell 权限" 在 Android 语境中特指 uid == 2000，
 * 此时进程拥有 shell 级别的系统 API 调用权（如 dump、statusbar 等）。
 */
object ShellAPI {

    private const val TAG = "VibeShell"

    // ==================== 身份检测 ====================

    /**
     * 当前进程是否运行在 Shell 用户下 (uid 2000)
     * 这是 Android 定义的 "shell 权限" 标准。
     */
    fun isShellUid(): Boolean {
        return Process.myUid() == Process.SHELL_UID
    }

    /**
     * 当前进程是否运行在 Root 用户下 (uid 0)
     */
    fun isRootUid(): Boolean {
        return Process.myUid() == 0
    }

    /** 是否有 Shell 或更高级别身份 */
    fun isShellOrHigher(): Boolean {
        val uid = Process.myUid()
        return uid == Process.SHELL_UID || uid == 0
    }

    // ==================== Root 检测 ====================

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

    // ==================== Shell 执行环境检测 ====================

    /** sh 二进制是否可执行（不一定需要 root） */
    fun hasShBinary(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("sh")
            process.destroy()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 命令执行 ====================

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
