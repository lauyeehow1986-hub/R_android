package com.rmobile.console.data.execution

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
            // All assets are bundled and served locally by WebViewAssetLoader, so
            // there's nothing to gain from HTTP caching — and LOAD_DEFAULT lets the
            // WebView serve a stale bridge.js/harness.R from its on-disk cache across
            // update installs (which keep app data), silently masking asset changes.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
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
                    // WebViewAssetLoader leaves the status line empty, which a
                    // synchronous XHR reports as status 0. WebR fetches its filesystem
                    // image via a sync XHR and requires a 2xx status, so it aborts with
                    // "Can't download filesystem image data" without this. Stamp 200 OK
                    // on found assets (data present) so those XHRs succeed.
                    if (res.data != null) res.setStatusCodeAndReasonPhrase(200, "OK")
                    return res
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/webr/index.html")
        }
    }

    // Persisted snapshots under filesDir, streamed to/from the WebR VFS in base64
    // chunks (the bridge marshals strings), so a large payload doesn't hit a
    // single-call size limit. Two kinds: the package library tarball and the
    // workspace (save.image) blob. This deliberately avoids WebR's IDBFS FS.mount,
    // which destabilised the eval channel.
    private val libraryStore = SnapshotStore(java.io.File(appContext.filesDir, "webr-library.tar.gz"))
    private val workspaceStore = SnapshotStore(java.io.File(appContext.filesDir, "webr-workspace.RData"))
    private fun snapshotStore(kind: String) = if (kind == "workspace") workspaceStore else libraryStore

    // On-device data files live at filesDir/localdata/<sanitized-session>/ (written
    // by LocalSessionDataStore); the bridge pulls them into WebR on demand.
    private fun localDataDir(sessionId: String): java.io.File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }
        return java.io.File(java.io.File(appContext.filesDir, "localdata"), safe)
    }

    private inner class Bridge {
        @JavascriptInterface fun onReady() { ready.complete(Unit) }
        @JavascriptInterface fun onError(message: String) {
            if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message))
        }
        @JavascriptInterface fun onResult(id: Int, json: String) { pending.remove(id)?.complete(json) }

        @JavascriptInterface fun snapshotSize(kind: String): Int =
            try { snapshotStore(kind).size() } catch (e: Exception) { 0 }

        @JavascriptInterface fun snapshotRead(kind: String, offset: Int, length: Int): String = try {
            val buf = snapshotStore(kind).read(offset, length)
            if (buf.isEmpty()) "" else android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        @JavascriptInterface fun snapshotBegin(kind: String) {
            try { snapshotStore(kind).begin() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotAppend(kind: String, b64: String) {
            try { snapshotStore(kind).append(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)) } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotCommit(kind: String) {
            try { snapshotStore(kind).commit() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotDelete(kind: String) {
            try { snapshotStore(kind).delete() } catch (e: Exception) {}
        }

        @JavascriptInterface fun dataList(sessionId: String): String {
            val files = (localDataDir(sessionId).listFiles() ?: emptyArray()).filter { it.isFile }
            return org.json.JSONArray().apply {
                files.forEach { put(org.json.JSONObject().put("name", it.name).put("size", it.length())) }
            }.toString()
        }
        @JavascriptInterface fun dataChunk(sessionId: String, name: String, offset: Int, length: Int): String = try {
            val f = java.io.File(localDataDir(sessionId), name)
            if (!f.exists() || offset >= f.length()) "" else {
                val end = minOf(offset + length, f.length().toInt())
                val buf = ByteArray(end - offset)
                java.io.RandomAccessFile(f, "r").use { it.seek(offset.toLong()); it.readFully(buf) }
                android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) { "" }
    }

    /** Runs a full ExecuteRequest (as JSON) and returns the bridge's ExecuteResponse JSON. */
    suspend fun execute(requestJson: String): String =
        callBridge("window.webrRun", org.json.JSONObject.quote(requestJson))

    /** Clears the WebR global env and returns the bridge's reset JSON. */
    suspend fun reset(): String = callBridge("window.webrReset", null)

    /** Installs a package from the bundled mini-repo and returns the bridge's InstallResponse JSON. */
    suspend fun installPackage(pkg: String): String =
        callBridge("window.webrInstall", org.json.JSONObject.quote(pkg))

    /** Removes an installed package and returns the bridge's UninstallResponse JSON. */
    suspend fun uninstallPackage(pkg: String): String =
        callBridge("window.webrUninstall", org.json.JSONObject.quote(pkg))

    /** Lists installed packages and returns the bridge's PackagesResponse JSON. */
    suspend fun listPackages(): String = callBridge("window.webrListPackages", null)

    /** Runs a read-only preview of a data file/workspace object and returns the bridge's PreviewResponse JSON. */
    suspend fun preview(requestJson: String): String = callBridge("window.webrPreview", org.json.JSONObject.quote(requestJson))

    /**
     * Invokes a bridge function that takes the result id as its first argument and
     * (optionally) [jsArg] as its second, and awaits the JSON it posts back.
     */
    private suspend fun callBridge(fn: String, jsArg: String?): String {
        // Build the WebView (which starts loading the WebR page, and is what
        // eventually fires onReady) on the main thread BEFORE awaiting readiness —
        // otherwise `ready` can never complete and the first call deadlocks.
        withContext(Dispatchers.Main) { webView }
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
