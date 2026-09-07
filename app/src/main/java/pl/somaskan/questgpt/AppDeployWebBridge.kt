package pl.somaskan.questgpt

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * AppDeploy's public stage URL returns the frontend shell to raw native HTTP clients.
 * The official @appdeploy/client transport works inside the hosted page, so QuestGPT
 * opens that page off-screen and receives a short-lived Realtime credential through
 * a custom questgpt:// callback. No long-lived OpenAI key enters the APK.
 */
object AppDeployWebBridge {
    private const val TIMEOUT_MS = 20_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchRealtimeCredential(baseUrl: String, mode: String): JSONObject = withTimeout(TIMEOUT_MS) {
        val result = CompletableDeferred<JSONObject>()
        val main = Handler(Looper.getMainLooper())
        val cleanBase = baseUrl.trimEnd('/')
        var webView: WebView? = null

        fun cleanup() {
            main.post {
                runCatching { webView?.stopLoading() }
                runCatching { webView?.loadUrl("about:blank") }
                runCatching { webView?.destroy() }
                webView = null
            }
        }

        main.post {
            try {
                val view = WebView(QuestApp.appContext)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.databaseEnabled = true
                view.settings.userAgentString = view.settings.userAgentString + " QuestGPTNativeBridge/1"
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        if (!uri.scheme.equals("questgpt", ignoreCase = true)) return false
                        if (!uri.host.equals("realtime", ignoreCase = true)) return true
                        val payload = uri.getQueryParameter("payload").orEmpty()
                        if (payload.isBlank()) {
                            result.completeExceptionally(IllegalStateException("Most AppDeploy nie zwrócił danych."))
                        } else {
                            runCatching { JSONObject(payload) }
                                .onSuccess { json ->
                                    val error = json.optString("error")
                                    if (error.isNotBlank()) result.completeExceptionally(IllegalStateException(error))
                                    else result.complete(json)
                                }
                                .onFailure { result.completeExceptionally(it) }
                        }
                        cleanup()
                        return true
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val uri = url?.let(Uri::parse) ?: return false
                        if (!uri.scheme.equals("questgpt", ignoreCase = true)) return false
                        if (!uri.host.equals("realtime", ignoreCase = true)) return true
                        val payload = uri.getQueryParameter("payload").orEmpty()
                        if (payload.isBlank()) result.completeExceptionally(IllegalStateException("Most AppDeploy nie zwrócił danych."))
                        else runCatching { JSONObject(payload) }
                            .onSuccess { json ->
                                val error = json.optString("error")
                                if (error.isNotBlank()) result.completeExceptionally(IllegalStateException(error)) else result.complete(json)
                            }
                            .onFailure { result.completeExceptionally(it) }
                        cleanup()
                        return true
                    }
                }
                val safeMode = if (mode == "text") "text" else "voice"
                view.loadUrl("$cleanBase/?questgpt_bridge=realtime&mode=$safeMode&t=${System.currentTimeMillis()}")
            } catch (t: Throwable) {
                result.completeExceptionally(t)
                cleanup()
            }
        }

        try {
            result.await()
        } finally {
            cleanup()
        }
    }
}
