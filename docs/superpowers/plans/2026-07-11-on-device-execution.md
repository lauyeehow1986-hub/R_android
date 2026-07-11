# On-Device R Execution (WebR local engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run R on-device via WebR (GNU R → WebAssembly) as a default, privacy-preserving local execution engine that returns the same `ExecuteResponse` contract as the backend, keeping the network backend as an opt-in engine.

**Architecture:** A swappable `ExecutionEngine` interface with two implementations — `RemoteExecutionEngine` (wraps today's `RExecutionRepository`) and `LocalExecutionEngine` (wraps a `WebRController` that owns one persistent offscreen WebView running bundled WebR). `EditorViewModel` routes runs through the currently-selected engine; a Settings toggle (Local default / Remote) chooses. The in-WebView harness mirrors the backend's `withVisible` + `TABLE_EMIT_HELPERS` loop so `stdout/stderr/plots/tables/workspaceObjects/timedOut` are produced identically. WebR runtime assets are bundled in the APK (offline from install).

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, `androidx.webkit` (`WebViewAssetLoader`), WebR 0.4.2 (vendored JS/WASM), Android WebView.

**Environment note:** The Android SDK is **not** available in the remote sandbox, so app builds and any device/emulator verification happen **locally or in CI**, not here (same constraint as every prior app feature). JVM unit tests (`app/src/test/`) run in CI. The WebR↔WebView round-trip **cannot** be JVM-unit-tested — it is verified by a **manual on-device/emulator step** flagged in Tasks 1 and 4. Those manual steps are hard gates: do not treat the feature as done until they pass on a device.

---

## File Structure

**New (app):**
- `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt` — interface + `RemoteExecutionEngine`.
- `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt` — wraps `WebRController`.
- `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt` — offscreen WebView + JS↔Kotlin bridge (Android-bound; compile-checked, manually verified).
- `app/src/main/java/com/rmobile/console/data/settings/ExecutionEngineChoice.kt` — `LOCAL`/`REMOTE` enum.
- `app/src/main/assets/webr/index.html` — bridge page.
- `app/src/main/assets/webr/bridge.js` — boots WebR, runs the harness, posts `ExecuteResponse` JSON.
- `app/src/main/assets/webr/harness.R` — the R harness (duplicates backend `TABLE_EMIT_HELPERS`).
- `app/src/main/assets/webr/dist/…` — vendored WebR runtime (WASM + JS glue).
- `app/src/main/assets/webr/README.md` + `scripts/fetch-webr.sh` — how the runtime was vendored.
- Tests: `app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt`, `.../execution/BridgeResponseParsingTest.kt`, `.../settings/ExecutionEngineChoiceTest.kt` (+ additions to existing VM tests).

**Modified (app):**
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — add `androidx.webkit`.
- `data/settings/AppSettings.kt`, `data/settings/SettingsStore.kt` — persist engine choice.
- `data/ServiceLocator.kt` — hold app context + provide current `ExecutionEngine`.
- `RMobileApplication.kt` — unchanged call site (ServiceLocator.init already takes context).
- `ui/editor/EditorViewModel.kt` — route `runCode`/`resetSession` through the engine provider.
- `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt` — engine toggle + caveat.
- `ui/data/DataScreen.kt`, `ui/packages/PackagesScreen.kt` — one-line "applies to Remote engine" note.
- `AndroidManifest.xml` — no new permission needed (assets bundled, no network for local).

**Modified (docs):** `CLAUDE.md`, `README.md`, `backend/README.md` (parity note).

---

## Task 1: SPIKE — WebR boots in an offscreen WebView and evaluates `1+1`

**This is a de-risking spike, not TDD.** The WebView + WASM + cross-origin-isolation + WebR-channel path is the feature's core unknown and cannot be unit-tested. Prove the round-trip on a device before building anything else. **Hard gate:** if the SharedArrayBuffer channel can't be brought up, stop and report — we switch to the PostMessage-channel fallback before continuing.

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/assets/webr/scripts/fetch-webr.sh`, `app/src/main/assets/webr/README.md`, `app/src/main/assets/webr/index.html`, `app/src/main/assets/webr/bridge.js`, `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- Vendor: `app/src/main/assets/webr/dist/…`

- [ ] **Step 1: Add the webkit dependency**

In `gradle/libs.versions.toml`, under `[versions]` add `webkit = "1.12.1"`, and under `[libraries]` add:

```toml
androidx-webkit = { group = "androidx.webkit", name = "webkit", version.ref = "webkit" }
```

In `app/build.gradle.kts` dependencies block add:

```kotlin
    implementation(libs.androidx.webkit)
```

- [ ] **Step 2: Vendor the WebR runtime into assets**

Create `app/src/main/assets/webr/scripts/fetch-webr.sh`:

```bash
#!/usr/bin/env bash
# Vendors the WebR runtime into app/src/main/assets/webr/dist so the app runs
# R fully offline. Re-run to update; commit the resulting dist/ directory.
set -euo pipefail
VERSION="0.4.2"
DEST="$(cd "$(dirname "$0")/.." && pwd)/dist"
TMP="$(mktemp -d)"
echo "Fetching webr@${VERSION} from npm..."
( cd "$TMP" && npm pack "webr@${VERSION}" >/dev/null )
TARBALL="$(ls "$TMP"/webr-*.tgz)"
tar -xzf "$TARBALL" -C "$TMP"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$TMP/package/dist/." "$DEST/"
rm -rf "$TMP"
echo "Vendored WebR ${VERSION} into $DEST"
```

Create `app/src/main/assets/webr/README.md` documenting: WebR 0.4.2, vendored via `scripts/fetch-webr.sh` (`npm pack webr@0.4.2` → copy `package/dist/`), the `dist/` directory is committed so the app is offline-from-install, and the license (WebR is GPL — note it here).

Run it locally: `bash app/src/main/assets/webr/scripts/fetch-webr.sh`. Verify `dist/webr.mjs`, `dist/R.bin.wasm`, the `dist/vfs/` directory (the base-R filesystem image in WebR 0.4.2 — there is no single `R.bin.data`), and the worker/service-worker JS exist. Commit `dist/` (mark it binary via `.gitattributes`: `app/src/main/assets/webr/dist/** -text`).

> If npm/internet is unavailable in the execution environment, create the script + README and note in the task result that `dist/` must be populated by running the script on a networked machine before the app will build a working local engine. The Kotlin/JS code below does not depend on `dist/` to compile.

- [ ] **Step 3: Bridge page**

Create `app/src/main/assets/webr/index.html`:

```html
<!doctype html>
<html>
  <head><meta charset="utf-8" /></head>
  <body>
    <script type="module" src="./bridge.js"></script>
  </body>
</html>
```

- [ ] **Step 4: Minimal bridge.js (spike scope — boot + eval)**

Create `app/src/main/assets/webr/bridge.js`. Spike version boots WebR (SharedArrayBuffer channel) against the vendored `./dist/` and exposes a `webrEval(id, code)` that evaluates R and hands the printed text back to Kotlin via the injected `AndroidBridge` interface:

```js
import { WebR } from './dist/webr.mjs';

const webR = new WebR({ baseUrl: './dist/' });
let ready = false;

async function boot() {
  await webR.init();
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));

// Called from Kotlin via evaluateJavascript.
window.webrEval = async (id, code) => {
  try {
    if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ error: 'WebR not ready' })); return; }
    const shelter = await new webR.Shelter();
    try {
      const cap = await shelter.captureR(code, { withAutoprint: true, captureStreams: true });
      const text = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
      AndroidBridge.onResult(id, JSON.stringify({ text }));
    } finally { shelter.purge(); }
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ error: String(e) }));
  }
};
```

- [ ] **Step 5: WebRController (spike scope)**

Create `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`. It owns one offscreen `WebView`, serves assets via `WebViewAssetLoader` under `https://appassets.androidplatform.net/assets/…` **with cross-origin-isolation headers injected** so `SharedArrayBuffer` is available, waits for `onReady`, and exposes `suspend fun evalRaw(code: String): String`.

```kotlin
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

    /** Spike: evaluate R and return the bridge's raw JSON ({text} or {error}). */
    suspend fun evalRaw(code: String): String {
        ready.await()
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            // JSON.stringify keeps arbitrary R source safe inside the JS call.
            val jsCode = org.json.JSONObject.quote(code)
            webView.evaluateJavascript("window.webrEval($id, $jsCode)", null)
        }
        return deferred.await()
    }
}
```

- [ ] **Step 6: Manual on-device verification (HARD GATE)**

Temporarily wire a smoke test (e.g. a debug button, or an instrumented test) that builds `WebRController(context)` and calls `evalRaw("1 + 1")`. Build + install on an emulator/device:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:installDebug
```

Expected: the bridge returns `{"text":"[1] 2"}`. Confirm in logcat.

- Gate A: WebR boots and returns `[1] 2` → proceed to Task 2.
- Gate B: SharedArrayBuffer channel fails to initialize → STOP, report; switch `bridge.js` to `new WebR({ baseUrl: './dist/', channelType: ChannelType.PostMessage })` (documented limitation: no mid-run interrupt) and re-verify before continuing.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/assets/webr app/src/main/java/com/rmobile/console/data/execution/WebRController.kt
git commit -m "spike: WebR boots in offscreen WebView, evaluates 1+1"
```

---

## Task 2: `ExecutionEngine` interface + `RemoteExecutionEngine` (TDD, JVM)

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecFile
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteExecutionEngineTest {

    private class RecordingApi : RExecutionApi {
        var lastRequest: ExecuteRequest? = null
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            return ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))
        }
        override suspend fun reset(request: com.rmobile.console.data.model.ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest) = com.rmobile.console.data.model.InstallResponse()
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest) = com.rmobile.console.data.model.UninstallResponse()
        override suspend fun packages(sessionId: String) = com.rmobile.console.data.model.PackagesResponse()
        override suspend fun importLegacy(request: com.rmobile.console.data.model.ImportLegacyRequest) = com.rmobile.console.data.model.ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) = com.rmobile.console.data.model.HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) = com.rmobile.console.data.model.PreviewResponse()
    }

    @Test
    fun `execute forwards the request to the repository and returns its response`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val request = ExecuteRequest(sessionId = "proj-1", files = listOf(ExecFile("main.R", "1")), entryFile = "main.R")

        val result = engine.execute(request)

        assertEquals(listOf(ExecFile("main.R", "1")), api.lastRequest!!.files)
        assertEquals("proj-1", api.lastRequest!!.sessionId)
        assertEquals("ok", result.getOrNull()!!.stdout)
    }

    @Test
    fun `reset delegates to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.reset("proj-1")
        assertTrue(result.isSuccess)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.RemoteExecutionEngineTest"`
Expected: FAIL (unresolved reference `ExecutionEngine`/`RemoteExecutionEngine`).

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`:

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse

/**
 * Where R code runs. Both implementations return the SAME [ExecuteResponse]
 * contract, so all downstream UI (output panel, plot decoding, RTableView,
 * workspace chips) is engine-agnostic.
 */
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse>
    /** Clears the session's workspace. */
    suspend fun reset(sessionId: String): Result<Unit>
}

/** The network backend engine — delegates to the existing repository. */
class RemoteExecutionEngine(
    private val repository: RExecutionRepository,
) : ExecutionEngine {
    override suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse> {
        val sessionId = request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID
        val files = request.files
        return if (files != null) {
            repository.run(files, request.entryFile.orEmpty(), sessionId)
        } else {
            repository.run(request.code.orEmpty(), sessionId)
        }
    }

    override suspend fun reset(sessionId: String): Result<Unit> =
        repository.reset(sessionId).map { }
}
```

- [ ] **Step 4: Run to verify it passes**

Run the same test command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt
git commit -m "feat: ExecutionEngine interface + RemoteExecutionEngine"
```

---

## Task 3: `ExecutionEngineChoice` + settings persistence (TDD + compile-checked)

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/settings/ExecutionEngineChoice.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/settings/AppSettings.kt`, `app/src/main/java/com/rmobile/console/data/settings/SettingsStore.kt`
- Test: `app/src/test/java/com/rmobile/console/data/settings/ExecutionEngineChoiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionEngineChoiceTest {
    @Test fun `fromStorage defaults to LOCAL for unknown or null`() {
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage(null))
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage("nonsense"))
    }
    @Test fun `round-trips through storage keys`() {
        assertEquals(ExecutionEngineChoice.REMOTE, ExecutionEngineChoice.fromStorage(ExecutionEngineChoice.REMOTE.storageKey))
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage(ExecutionEngineChoice.LOCAL.storageKey))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.settings.ExecutionEngineChoiceTest"`
Expected: FAIL (unresolved `ExecutionEngineChoice`).

- [ ] **Step 3: Implement the enum**

Create `app/src/main/java/com/rmobile/console/data/settings/ExecutionEngineChoice.kt`:

```kotlin
package com.rmobile.console.data.settings

/** Which engine runs R. Local (on-device WebR) is the privacy-preserving default. */
enum class ExecutionEngineChoice(val storageKey: String) {
    LOCAL("local"),
    REMOTE("remote");

    companion object {
        fun fromStorage(value: String?): ExecutionEngineChoice =
            entries.firstOrNull { it.storageKey == value } ?: LOCAL
    }
}
```

- [ ] **Step 4: Add to AppSettings + SettingsStore**

In `AppSettings.kt` add:

```kotlin
    var executionEngine: ExecutionEngineChoice
```

In `SettingsStore.kt` add the override (place beside `apiKey`):

```kotlin
    override var executionEngine: ExecutionEngineChoice
        get() = ExecutionEngineChoice.fromStorage(prefs.getString(KEY_ENGINE, null))
        set(value) = prefs.edit().putString(KEY_ENGINE, value.storageKey).apply()
```

And add the key to the companion object: `const val KEY_ENGINE = "execution_engine"`. Add the import `import com.rmobile.console.data.settings.ExecutionEngineChoice` is unnecessary (same package). Update any existing `AppSettings` test fake to implement the new property (search `: AppSettings` in `app/src/test/` — e.g. `SettingsViewModelTest`'s fake — add `override var executionEngine = ExecutionEngineChoice.LOCAL`).

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`
Expected: PASS (new test green, existing tests still compile/pass with the fake updated).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/settings app/src/test/java/com/rmobile/console/data/settings
git commit -m "feat: persist execution engine choice (default local)"
```

---

## Task 4: `LocalExecutionEngine` + full WebR harness (bridge.js + harness.R) + parsing test

The bridge now produces a full `ExecuteResponse`. Mirrors the backend `/execute` wrapper: a top-level `withVisible` loop + `TABLE_EMIT_HELPERS` writing `table%03d.json` into the WebR VFS, plots via `captureGraphics`, `ls(globalenv())` for workspace objects, and a JS timeout via `webR.interrupt()`.

**Files:**
- Create: `app/src/main/assets/webr/harness.R`, `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`
- Modify: `app/src/main/assets/webr/bridge.js`, `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/BridgeResponseParsingTest.kt`

- [ ] **Step 1: Write the failing test (bridge JSON → ExecuteResponse)**

This proves the contract shape without a WebView: a captured sample of the bridge's output JSON must deserialize into the existing `ExecuteResponse`.

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.model.ExecuteResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeResponseParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `bridge output json deserializes into ExecuteResponse`() {
        val sample = """
          {"stdout":"[1] 2\n","stderr":"","plots":["iVBORw0KGgo="],
           "tables":[{"columns":["a"],"columnTypes":["numeric"],"rows":[["1"]],"totalRows":1}],
           "workspaceObjects":["x"],"error":null,"timedOut":false}
        """.trimIndent()

        val response = json.decodeFromString<ExecuteResponse>(sample)

        assertEquals("[1] 2\n", response.stdout)
        assertEquals(listOf("iVBORw0KGgo="), response.plots)
        assertEquals(1, response.tables.single().totalRows)
        assertEquals(listOf("a"), response.tables.single().columns)
        assertEquals(listOf("x"), response.workspaceObjects)
        assertFalse(response.timedOut)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.BridgeResponseParsingTest"`

This test passes immediately (it only exercises existing `ExecuteResponse`), which is the point — it **locks the contract** the bridge must emit. If it fails, the model drifted; fix before shipping the bridge.

- [ ] **Step 3: harness.R (duplicate of backend TABLE_EMIT_HELPERS + exec loop)**

Create `app/src/main/assets/webr/harness.R`. This is sourced by the bridge with the run dir as CWD; `.RMOBILE_ENTRY` is set by the bridge to the entry file's basename. Keep the `.emit`/`.tabular` bodies **byte-identical** to `backend/plumber.R`'s `TABLE_EMIT_HELPERS`:

```r
local({
  .maxrows <- 200
  .emit <- function(x) {
    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)
    n <- nrow(df); sub <- utils::head(df, .maxrows)
    types <- vapply(df, function(cc) class(cc)[1], character(1))
    cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))
    cols <- names(df)
    rn <- rownames(sub)
    if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }
    rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))
    obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))
    idx <- length(list.files(".", pattern = "^table[0-9]+\\.json$")) + 1L
    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))
  }
  .tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)
  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")
  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if ((r$visible || pr) && .tabular(r$value)) try(.emit(r$value), silent = TRUE); if (r$visible) print(r$value) }
  .exec(parse(file = .RMOBILE_ENTRY))
})
```

> The R harness has no `png()`/`save.image()` — plots are captured by WebR's `captureGraphics`, and the local engine is not persisting to disk in v1. The `jsonlite` package is part of WebR's default image.

- [ ] **Step 4: Full bridge.js**

Replace `bridge.js` with the full implementation. `webrRun(id, requestJson)` receives `{ files, entryFile, code, timeoutSeconds }`, writes files into a fresh VFS run dir, sources `harness.R`, captures streams + graphics, reads back `table*.json`, lists globals, converts plots to base64 PNG, and posts an `ExecuteResponse`-shaped JSON. Runs in the **global env** so workspace persists across calls.

```js
import { WebR } from './dist/webr.mjs';

const webR = new WebR({ baseUrl: './dist/' });
let ready = false;

async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));

async function bitmapToPng(image) {
  // captureGraphics yields ImageBitmap; re-encode as PNG data (base64, no prefix).
  const canvas = new OffscreenCanvas(image.width, image.height);
  const ctx = canvas.getContext('2d');
  ctx.drawImage(image, 0, 0);
  const blob = await canvas.convertToBlob({ type: 'image/png' });
  const buf = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let i = 0; i < buf.length; i++) binary += String.fromCharCode(buf[i]);
  return btoa(binary);
}

async function readTables() {
  const tables = [];
  for (let i = 1; ; i++) {
    const name = `/rmobile/run/table${String(i).padStart(3, '0')}.json`;
    let bytes;
    try { bytes = await webR.FS.readFile(name); } catch { break; }
    tables.push(JSON.parse(new TextDecoder().decode(bytes)));
  }
  return tables;
}

async function resetRunDir() {
  await webR.evalRVoid('unlink("/rmobile/run", recursive = TRUE); dir.create("/rmobile/run"); setwd("/rmobile/run")');
}

async function runOnce(req) {
  await resetRunDir();
  const files = req.files && req.files.length ? req.files
    : [{ name: 'script.R', content: req.code || '' }];
  const entry = req.entryFile || files[0].name;
  for (const f of files) {
    await webR.FS.writeFile(`/rmobile/run/${f.name}`, new TextEncoder().encode(f.content));
  }
  // Load the harness template and inject the entry file path.
  const harnessBytes = await (await fetch('./harness.R')).arrayBuffer();
  const harness = new TextDecoder().decode(harnessBytes);
  await webR.objs.globalEnv.bind('.RMOBILE_ENTRY', entry);

  const shelter = await new webR.Shelter();
  try {
    const cap = await shelter.captureR(harness, {
      withAutoprint: false,
      captureStreams: true,
      captureGraphics: { width: 800, height: 600 },
      env: await webR.objs.globalEnv,
    });
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    const stderr = cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n');
    const plots = [];
    for (const img of cap.images || []) plots.push(await bitmapToPng(img));
    const tables = await readTables();
    const wsR = await webR.evalR('ls(globalenv())');
    const workspaceObjects = await wsR.toArray();
    webR.destroy(wsR);
    return { stdout, stderr, plots, tables, workspaceObjects, error: null, timedOut: false };
  } finally { shelter.purge(); }
}

window.webrRun = async (id, requestJson) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ error: 'WebR not ready', timedOut: false })); return; }
  const req = JSON.parse(requestJson);
  const timeoutMs = (req.timeoutSeconds || 20) * 1000;
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(async () => { try { await webR.interrupt(); } catch {} resolve({ stdout: '', stderr: '', plots: [], tables: [], workspaceObjects: null, error: `Execution timed out after ${req.timeoutSeconds || 20}s.`, timedOut: true }); }, timeoutMs);
  });
  try {
    const result = await Promise.race([runOnce(req), timeout]);
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify(result));
  } catch (e) {
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify({ stdout: '', stderr: String(e), plots: [], tables: [], workspaceObjects: null, error: String(e), timedOut: false }));
  }
};

window.webrReset = async (id) => {
  try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); AndroidBridge.onResult(id, JSON.stringify({ ok: true })); }
  catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};
```

> If Gate B (PostMessage fallback) was taken in Task 1, `webR.interrupt()` is unavailable; enforce timeout by tearing down and recreating the WebR instance instead, and note the degraded behavior.

- [ ] **Step 5: WebRController.execute/reset + LocalExecutionEngine**

Add to `WebRController` (replacing the spike `evalRaw`) a `suspend fun execute(requestJson: String): String` and `suspend fun reset(): String` that call `window.webrRun` / `window.webrReset` and await the bridge JSON (same `pending`/`CompletableDeferred` mechanism; pass `requestJson` through `JSONObject.quote`). Keep `evalRaw` if convenient for debugging, or remove it.

Create `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`:

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import kotlinx.serialization.json.Json

/** On-device WebR engine. Serializes the request to the bridge and parses its ExecuteResponse. */
class LocalExecutionEngine(
    private val controller: WebRController,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ExecutionEngine {
    override suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse> = runCatching {
        val requestJson = json.encodeToString(ExecuteRequest.serializer(), request)
        json.decodeFromString<ExecuteResponse>(controller.execute(requestJson))
    }

    override suspend fun reset(sessionId: String): Result<Unit> = runCatching {
        controller.reset(); Unit
    }
}
```

> `ExecuteRequest` has no `timeoutSeconds`; the bridge defaults to 20s. If a configurable local timeout is wanted later, add it — out of scope for v1.

- [ ] **Step 6: Run unit tests + build**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: unit tests PASS; app compiles.

- [ ] **Step 7: Manual on-device verification (HARD GATE)**

On an emulator/device, run (via the temporary smoke hook or by selecting the Local engine once Task 6 lands — if verifying now, drive `LocalExecutionEngine.execute` directly):
- `print(head(cars)); plot(cars); x <- 42` → response has a `tables` entry (the `head(cars)` data frame), one `plots` PNG, `stdout` with the printed frame, `workspaceObjects` containing `x`.
- Run `print(x)` next → prints `42` (workspace persisted).
- Run `while(TRUE){}` → `timedOut = true` after ~20s.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/assets/webr app/src/main/java/com/rmobile/console/data/execution
git commit -m "feat: LocalExecutionEngine + full WebR harness (stdout/plots/tables/workspace/timeout)"
```

---

## Task 5: Engine selection in ServiceLocator + route EditorViewModel through it (TDD)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/ServiceLocator.kt`, `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test: **modify** `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt` (do NOT create a new file — `InMemoryProjectStore`/`FakeApi` are `private` classes inside this file, and its 9 `runCode` tests must keep working).

**Why modify in place:** `EditorViewModelTest` calls `runCode()` in 9 tests and asserts on `api.lastRequest`. Once `runCode` routes through `engineProvider`, the default provider would call `ServiceLocator.currentExecutionEngine()` and crash (ServiceLocator isn't initialized in unit tests). Route the test's construction helper through a `RemoteExecutionEngine` wrapping the **same** `FakeApi`, so those 9 tests still exercise the fake api unchanged.

- [ ] **Step 1: Update the test construction helper + add the routing test**

Change the `viewModel(...)` helper (currently at line ~103) to inject an `engineProvider` built from the same fake api, and give it an overridable `engineProvider` param:

```kotlin
    private fun viewModel(
        api: FakeApi = FakeApi(),
        history: InMemoryHistoryStore = InMemoryHistoryStore(),
        scripts: InMemoryScriptStore = InMemoryScriptStore(),
        projects: InMemoryProjectStore = InMemoryProjectStore(),
        engineProvider: (() -> com.rmobile.console.data.execution.ExecutionEngine)? = null,
    ): EditorViewModel {
        val repo = RExecutionRepository(api)
        return EditorViewModel(
            repo, history, scripts, projects, now = { counter++ },
            engineProvider = engineProvider
                ?: { com.rmobile.console.data.execution.RemoteExecutionEngine(repo) },
        )
    }
```

Then add a `FakeEngine` private class and a routing test to the same file:

```kotlin
    private class FakeEngine(val response: com.rmobile.console.data.model.ExecuteResponse) :
        com.rmobile.console.data.execution.ExecutionEngine {
        var lastRequest: com.rmobile.console.data.model.ExecuteRequest? = null
        override suspend fun execute(request: com.rmobile.console.data.model.ExecuteRequest):
            Result<com.rmobile.console.data.model.ExecuteResponse> {
            lastRequest = request; return Result.success(response)
        }
        override suspend fun reset(sessionId: String) = Result.success(Unit)
    }

    @Test
    fun `runCode routes through the selected engine and maps its response`() = runTest {
        val engine = FakeEngine(ExecuteResponse(stdout = "hi", workspaceObjects = listOf("y")))
        val vm = viewModel(engineProvider = { engine })
        vm.onCodeChanged("y <- 1")
        vm.runCode()
        advanceUntilIdle()

        assertEquals("hi", vm.uiState.value.stdout)
        assertEquals(listOf("y"), vm.uiState.value.workspaceObjects)
        assertEquals("y <- 1", engine.lastRequest!!.files!!.first().content)
    }
```

(`ExecuteResponse` is already imported in this file; `runTest`/`advanceUntilIdle` are already used by the existing run tests.)

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: FAIL to compile (no `engineProvider` param on `EditorViewModel` yet).

- [ ] **Step 3: ServiceLocator provides the current engine**

Replace `ServiceLocator.kt` body to hold the app context and build engines lazily:

```kotlin
package com.rmobile.console.data

import android.content.Context
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.execution.LocalExecutionEngine
import com.rmobile.console.data.execution.RemoteExecutionEngine
import com.rmobile.console.data.execution.WebRController
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.data.settings.SettingsStore

object ServiceLocator {
    lateinit var settingsStore: SettingsStore
        private set
    private lateinit var appContext: Context

    private val remoteEngine: ExecutionEngine by lazy {
        RemoteExecutionEngine(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localEngine: ExecutionEngine by lazy {
        LocalExecutionEngine(WebRController(appContext))
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsStore = SettingsStore(context)
        NetworkModule.updateConfig(settingsStore.baseUrl, settingsStore.apiKey)
    }

    /** The engine for the currently-selected setting; read fresh each run so a toggle takes effect immediately. */
    fun currentExecutionEngine(): ExecutionEngine =
        when (settingsStore.executionEngine) {
            ExecutionEngineChoice.LOCAL -> localEngine
            ExecutionEngineChoice.REMOTE -> remoteEngine
        }
}
```

- [ ] **Step 4: Route EditorViewModel through the engine provider**

In `EditorViewModel.kt`:
- Add a constructor param (after `now`): `private val engineProvider: () -> ExecutionEngine = { ServiceLocator.currentExecutionEngine() }` (import `com.rmobile.console.data.execution.ExecutionEngine` and `com.rmobile.console.data.model.ExecuteRequest`).
- In `runCode()`, replace the `repository.run(files, project.entryFileName, session)` call with:

```kotlin
            val request = ExecuteRequest(sessionId = session, files = files, entryFile = project.entryFileName)
            engineProvider().execute(request)
```

  (keep the existing `.onSuccess { … }` / `.onFailure { … }` blocks unchanged).
- In `resetSession()`, replace `repository.reset(session)` with `engineProvider().reset(session)` and adjust the `onSuccess` (it now yields `Unit`): `.onSuccess { _uiState.update { it.copy(workspaceObjects = emptyList()) } }`.
- Leave `deleteProject`'s `repository.reset(...)` (backend purge of the project's remote library) as-is — that is a backend-lifecycle concern, independent of the active run engine. The `repository` param stays (still used by `refreshSymbols`, `help`, `deleteProject`).

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`
Expected: PASS — the new routing test is green AND all 9 existing `runCode` tests still pass (they now go through `RemoteExecutionEngine` → the same `FakeApi`, so `api.lastRequest` assertions are unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/ServiceLocator.kt app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "feat: route editor runs through the selected ExecutionEngine"
```

---

## Task 6: Settings toggle + data/packages caveat (compile-checked UI + VM test)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/settings/SettingsViewModel.kt`, `app/src/main/java/com/rmobile/console/ui/settings/SettingsScreen.kt`, `app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt`, `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt`
- Test: extend `app/src/test/java/com/rmobile/console/ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SettingsViewModelTest`:

```kotlin
    @Test fun `setEngine persists the choice and updates state`() {
        val store = FakeSettings() // existing fake in this test file; add executionEngine var
        val vm = SettingsViewModel(store = store, probeHealth = { _, _ -> Result.success(Unit) })
        vm.setEngine(ExecutionEngineChoice.REMOTE)
        assertEquals(ExecutionEngineChoice.REMOTE, store.executionEngine)
        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.executionEngine)
    }
```

(Add `import com.rmobile.console.data.settings.ExecutionEngineChoice` and give the test's `FakeSettings` an `override var executionEngine = ExecutionEngineChoice.LOCAL`.)

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.settings.SettingsViewModelTest"`
Expected: FAIL (`executionEngine` not in `SettingsUiState`; no `setEngine`).

- [ ] **Step 3: SettingsViewModel — state + setter**

- Add `val executionEngine: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL` to `SettingsUiState`.
- Initialize it from `store.executionEngine` in the `_uiState` initializer.
- Add:

```kotlin
    fun setEngine(choice: ExecutionEngineChoice) {
        store.executionEngine = choice
        _uiState.update { it.copy(executionEngine = choice) }
    }
```

- [ ] **Step 4: SettingsScreen — engine control**

At the top of the settings `Column` (above the "Backend" section), add an "Execution engine" section: a title, a short explanation, and two selectable options (Local / Remote) using `FilterChip` or a segmented row of `OutlinedButton`s bound to `uiState.executionEngine` / `viewModel::setEngine`, plus the caveat text:

```kotlin
            Text("Execution engine", style = MaterialTheme.typography.titleMedium)
            Text(
                "Local runs R on your device with WebR — your code and data never leave the phone. Remote sends code to the backend for full package compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.executionEngine == ExecutionEngineChoice.LOCAL,
                    onClick = { viewModel.setEngine(ExecutionEngineChoice.LOCAL) },
                    label = { Text("Local (on-device)") },
                )
                FilterChip(
                    selected = uiState.executionEngine == ExecutionEngineChoice.REMOTE,
                    onClick = { viewModel.setEngine(ExecutionEngineChoice.REMOTE) },
                    label = { Text("Remote (backend)") },
                )
            }
            Text(
                "Data files and installed packages apply to the Remote engine. Local runs use only WebR's built-in packages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
```

Add imports: `androidx.compose.material3.FilterChip` and `com.rmobile.console.data.settings.ExecutionEngineChoice`.

- [ ] **Step 5: Data & Packages screens — one-line note**

In `DataScreen.kt` and `PackagesScreen.kt`, add a single muted caption near the top of each screen's content (below the top bar), e.g.:

```kotlin
            Text(
                "These files apply to the Remote engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
```

(For Packages: "Installed packages apply to the Remote engine.") Keep it minimal — one `Text` each. Confirm the surrounding composable already imports `Text`/`MaterialTheme`.

- [ ] **Step 6: Run tests + build**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/settings app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt app/src/test/java/com/rmobile/console/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: execution-engine toggle in Settings + remote-engine caveats"
```

---

## Task 7: Docs + full verification

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `backend/README.md`

- [ ] **Step 1: Update docs**

- `CLAUDE.md`:
  - In "What this is" / the "Why there's a backend" note, add that the app now **also** runs R on-device via WebR (real GNU R → WASM) as the default local engine, with the backend as an opt-in engine — and that Renjin remains rejected (WebR is actual R, not a reimplementation).
  - Add a `data/execution/` architecture bullet: `ExecutionEngine` (interface) + `RemoteExecutionEngine`/`LocalExecutionEngine`, `WebRController` (offscreen WebView + bundled `assets/webr/` runtime, COOP/COEP via `WebViewAssetLoader`), engine selection via `SettingsStore.executionEngine` + `ServiceLocator.currentExecutionEngine()`, and the harness parity coupling (`assets/webr/harness.R` duplicates `TABLE_EMIT_HELPERS`).
  - Update "Current scope / what's built": move on-device execution from "not built" to built (v1 scope), and record the v1 boundaries (local data-import, local packages, cross-restart persistence still not built).
  - Update the Commands section: note the WebR runtime is vendored via `app/src/main/assets/webr/scripts/fetch-webr.sh`.
- `README.md`: add an on-device execution bullet to the feature list.
- `backend/README.md`: add a short note that the app has a local WebR engine emitting the **same** `ExecuteResponse`/`RTable` contract, and that `assets/webr/harness.R` must stay byte-compatible with `TABLE_EMIT_HELPERS` in `plumber.R`.

- [ ] **Step 2: Full verification**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint`
Expected: unit tests PASS, APK assembles, lint clean (or only pre-existing warnings).

Confirm the Task 1 and Task 4 **manual device gates** have been executed and passed (WebR boots; a real script returns text + plot + table; workspace persists; timeout works). The feature is not done until these pass on a device.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md backend/README.md
git commit -m "docs: on-device WebR execution engine"
```

---

## Notes for the executor

- **Manual gates are real.** Tasks 1 and 4 have on-device verification that CI cannot perform (no emulator in the JVM test job; the sandbox has no Android SDK at all). Do not mark the feature complete on unit tests alone.
- **WebR API drift.** The `bridge.js` uses WebR 0.4.2's `captureR({ captureStreams, captureGraphics })`, `Shelter`, `FS`, `objs.globalEnv`, and `interrupt()`. If the vendored version differs, adapt the calls (they are the parts most likely to need small changes) — the Kotlin contract (`ExecuteResponse` JSON) stays fixed.
- **Harness parity.** If you change table capture in `backend/plumber.R`, change `assets/webr/harness.R` in the same commit (and vice-versa). The `BridgeResponseParsingTest` guards the JSON shape, not the R logic.
- **Fallback channel.** If Task 1 Gate B fires, the PostMessage channel changes timeout handling (recreate instance instead of `interrupt()`); document it where it lands.
```
