package com.dng.revibe.launcher

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ReVibeLauncher"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            // Load hello world HTML from assets
            loadUrl("file:///android_asset/index.html")
        }

        Log.i(TAG, "Re-Vibe-Launcher started successfully!")
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}
