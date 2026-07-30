package com.dng.revibe.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ReVibeLauncher"
    }

    private var webView: WebView? = null
    private lateinit var permissions: Permissions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化权限管理
        permissions = Permissions(this)

        // 沉浸模式
        try {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Throwable) {}

        webView = findViewById(R.id.webView)
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            val bridge = JsBridge(this@MainActivity, this)
            addJavascriptInterface(bridge, "NativeBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "页面加载完成: $url")
                }
            }

            // 尝试加载热更新文件
            val updateFile = File(filesDir, "index.html")
            if (updateFile.exists()) {
                loadUrl("file://" + updateFile.absolutePath)
                Log.i(TAG, "加载热更新: " + updateFile.absolutePath)
            } else {
                loadUrl("file:///android_asset/index.html")
            }
        }

        Log.i(TAG, "Re-Vibe-Launcher started!")
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    // ============ 权限回调 ============

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.handleRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @Deprecated("Use registerForActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        this.permissions.handleActivityResult(requestCode, resultCode, data)
    }
}
