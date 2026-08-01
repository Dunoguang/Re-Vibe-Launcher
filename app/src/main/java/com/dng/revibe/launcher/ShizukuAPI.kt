package com.dng.revibe.launcher

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

object ShizukuAPI {

    fun isConnected(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 应用是否已在 Shizuku 中授权（未授权时调用 newProcess 会被服务端拒绝） */
    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求 Shizuku 授权并等待用户反馈。
     * @param timeoutMs 等待超时（默认 60s），超时视为未授权
     * @param onResult 授权结果回调（true=已授权）
     * @return 是否成功弹出授权界面（若返回 false，则不会回调 onResult）
     */
    fun requestPermission(requestCode: Int, timeoutMs: Long = 60000, onResult: (Boolean) -> Unit): Boolean {
        val handler = Handler(Looper.getMainLooper())
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                handler.removeCallbacksAndMessages(null)
                try { Shizuku.removeRequestPermissionResultListener(this) } catch (_: Exception) {}
                onResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        }
        val timeout = Runnable {
            try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Exception) {}
            onResult(false)
        }
        handler.postDelayed(timeout, timeoutMs)
        return try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(requestCode)
            true
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Exception) {}
            false
        }
    }

    fun execute(command: String, callback: (CommandResult) -> Unit) {
        if (command.isBlank()) {
            callback(CommandResult("", "Command is empty", -1))
            return
        }

        if (!isConnected()) {
            callback(CommandResult("", "Shizuku is not connected", -1))
            return
        }

        Thread {
            try {
                val binder = Shizuku.getBinder()
                    ?: throw IllegalStateException("Binder is null")
                val service = IShizukuService.Stub.asInterface(binder)
                val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)

                val stdout = readFromPfd(remote.inputStream)
                val stderr = readFromPfd(remote.errorStream)
                val statusCode = remote.waitFor()

                callback(CommandResult(stdout, stderr, statusCode))
            } catch (e: Exception) {
                callback(CommandResult("", e.message ?: "Unknown error", -1))
            }
        }.start()
    }

    private fun readFromPfd(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText().trim()
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val statusCode: Int
    )
}
