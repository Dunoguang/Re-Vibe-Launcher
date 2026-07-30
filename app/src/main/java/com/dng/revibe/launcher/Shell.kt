package com.dng.revibe.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Shell 命令执行
 *
 * 通过 ProcessBuilder("sh") 启动标准 shell 执行命令。
 * 无需 root、无需 Shizuku，普通 App 即可使用。
 *
 * 如果需要更高权限的执行（root / Shizuku），分别使用：
 * - ShizukuAPI.execShell() — 通过 Shizuku 服务
 * - （root 执行暂未实现）
 */
object Shell {

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val statusCode: Int
    )

    fun execute(command: String, callback: (CommandResult) -> Unit) {
        if (command.isBlank()) {
            callback(CommandResult("", "Command is empty", -1))
            return
        }

        Thread {
            var process: Process? = null
            try {
                process = ProcessBuilder("sh").start()

                process!!.outputStream.use { out ->
                    out.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                    out.write("exit\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }

                val stdout = readAll(process!!.inputStream)
                val stderr = readAll(process!!.errorStream)
                val statusCode = process!!.waitFor()

                callback(CommandResult(stdout, stderr, statusCode))
            } catch (e: Exception) {
                callback(CommandResult("", e.message ?: "Unknown error", -1))
            } finally {
                process?.destroy()
            }
        }.start()
    }

    private fun readAll(inputStream: java.io.InputStream): String {
        val builder = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            var first = true
            while (reader.readLine().also { line = it } != null) {
                if (!first) builder.append('\n')
                builder.append(line)
                first = false
            }
        }
        return builder.toString()
    }
}
