package com.rmobile.console.data.execution

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns a single long-lived, offscreen WebView that runs the bundled WebR runtime.
 * The one live WebR instance IS the local session, so workspace objects persist
 * across runs for the life of the app process. All WebView interaction is
 * marshaled to the main thread. Android-bound: verified on-device, not by JVM tests.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebRController(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(0)

    // Cross-origin isolation is required for WebR's SharedArrayBuffer channel.
    private val coiHeaders = mapOf(
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "require-corp",
        "Cross-Origin-Resource-Policy" to "same-origin",
    )

    private val webView: WebView by lazy { buildWebView() }

    private fun buildWebView(): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        return WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val res = assetLoader.shouldInterceptRequest(request.url) ?: return null
                    val headers = HashMap(res.responseHeaders ?: emptyMap())
                    headers.putAll(coiHeaders)
                    res.responseHeaders = headers
                    return res
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/webr/index.html")
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun onReady() { ready.complete(Unit) }
        @JavascriptInterface fun onError(message: String) {
            if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message))
        }
        @JavascriptInterface fun onResult(id: Int, json: String) { pending.remove(id)?.complete(json) }
    }

    /** Runs a full ExecuteRequest (as JSON) and returns the bridge's ExecuteResponse JSON. */
    suspend fun execute(requestJson: String): String =
        callBridge("window.webrRun", org.json.JSONObject.quote(requestJson))

    /** Clears the WebR global env and returns the bridge's reset JSON. */
    suspend fun reset(): String = callBridge("window.webrReset", null)

    /**
     * Invokes a bridge function that takes the result id as its first argument and
     * (optionally) [jsArg] as its second, and awaits the JSON it posts back.
     */
    private suspend fun callBridge(fn: String, jsArg: String?): String {
        ready.await()
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val call = if (jsArg != null) "$fn($id, $jsArg)" else "$fn($id)"
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(call, null)
        }
        return deferred.await()
    }
}
