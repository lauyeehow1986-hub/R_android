# On-device Help + Symbols Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route in-app R help and autocomplete's symbol/package index through the `ExecutionEngine` (like execute/preview) so `?aes` and completions work offline on Local (WebR) projects, with a best-effort Remote fallback when an on-device lookup comes up empty.

**Architecture:** `help` and `symbols` become `ExecutionEngine` methods. `RemoteExecutionEngine` delegates to the existing repository (backend `/help`, `/symbols`); `LocalExecutionEngine` delegates to `WebRController` → new `bridge.js` functions that run the same R the backend uses, on-device. The **local-first / remote-fallback policy lives in `EditorViewModel`** (`showHelp`, `refreshSymbols`), so the engines stay pure. No new models, no backend changes.

**Tech Stack:** Kotlin + Jetpack Compose, kotlinx.serialization, coroutines; JUnit4 (`app/src/test/`); WebR (GNU R → WASM) via an offscreen WebView with a JS↔Kotlin bridge (`app/src/main/assets/webr/bridge.js`).

**Spec:** `docs/superpowers/specs/2026-07-19-on-device-help-symbols-design.md`

---

## Background the implementer needs

- `ExecutionEngine` (interface + `RemoteExecutionEngine`) lives in
  `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`;
  `LocalExecutionEngine` in the same package
  (`.../data/execution/LocalExecutionEngine.kt`). Every engine method already carries a
  trailing `libraryKey: String? = null` (Remote ignores it; Local forwards it).
- Models already exist in `.../data/model/AssistModels.kt`:
  - `data class HelpResponse(val topic: String = "", val packageName: String? = null, val text: String = "", val found: Boolean = false)`
  - `data class SymbolsResponse(val symbols: List<String> = emptyList())`
- `RExecutionRepository` (`.../data/RExecutionRepository.kt`) already exposes:
  - `suspend fun help(topic: String, sessionId: String = DEFAULT_SESSION_ID): Result<HelpResponse>`
  - `suspend fun listSymbols(sessionId: String = DEFAULT_SESSION_ID): Result<List<String>>`
- `WebRController` (`.../data/execution/WebRController.kt`) exposes bridge methods via a
  private `callBridge(fn, vararg jsArgs)` helper. Existing pattern (line ~168):
  ```kotlin
  suspend fun preview(requestJson: String, libraryKey: String): String =
      callBridge("window.webrPreview", org.json.JSONObject.quote(requestJson), org.json.JSONObject.quote(libraryKey))
  ```
- `EditorViewModel` (`.../ui/editor/EditorViewModel.kt`) already holds
  `repository`, `defaultEngine: () -> ExecutionEngineChoice`, and
  `engineProvider: (ExecutionEngineChoice) -> ExecutionEngine`. It resolves an engine with
  `ExecutionEngineChoice.resolve(project.engine, defaultEngine())`, a session with
  `ProjectSession.of(project)`, and a library key with `ProjectSession.libraryKey(project)`.
- `HelpState` (`.../ui/editor/HelpState.kt`): `Loading(topic)`, `Loaded(val response: HelpResponse)`,
  `NotFound(val topic)`, `Error(val topic, val message)`.
- Three test doubles implement `ExecutionEngine` and MUST gain the two new methods or the
  test module won't compile: `EditorViewModelTest.FakeEngine`,
  `PreviewViewModelTest.FakeEngine`, `PackagesViewModelTest.FakeEngine`.

## File structure (what changes and why)

- `ExecutionEngine.kt` — add `help`/`symbols` to the interface; implement in `RemoteExecutionEngine`.
- `LocalExecutionEngine.kt` — implement `help`/`symbols` (decode bridge JSON).
- `WebRController.kt` — add `help`/`symbols` bridge callers.
- `assets/webr/bridge.js` — add `window.webrHelp` / `window.webrSymbols`.
- `EditorViewModel.kt` — route `showHelp` + `refreshSymbols` through the engine, local-first.
- Tests: `RemoteExecutionEngineTest.kt` (delegation), `EditorViewModelTest.kt` (routing +
  fallback), plus the two `FakeEngine`s in `PreviewViewModelTest.kt` / `PackagesViewModelTest.kt`.
- `CLAUDE.md` — document that help/symbols are engine-routed, local-first.

## Build/test commands (Windows)

- Unit tests: `.\gradlew.bat :app:testDebugUnitTest`
- Single class: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
- Debug build: `.\gradlew.bat :app:assembleDebug`
- `JAVA_HOME` is `C:\Program Files\Android\Android Studio\jbr` if Gradle can't find a JDK.

---

### Task 1: `ExecutionEngine.help` + `symbols` (interface + Remote impl + fakes + tests)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`
- Modify (test fakes): `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`,
  `app/src/test/java/com/rmobile/console/ui/preview/PreviewViewModelTest.kt`,
  `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt`

- [ ] **Step 1: Write the failing tests (Remote delegation)**

In `RemoteExecutionEngineTest.kt`, first make `RecordingApi` capture help/symbols. Replace its
existing lines:
```kotlin
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) = com.rmobile.console.data.model.HelpResponse()
```
with:
```kotlin
        var lastHelp: com.rmobile.console.data.model.HelpRequest? = null
        var lastSymbolsSessionId: String? = null
        override suspend fun symbols(sessionId: String): com.rmobile.console.data.model.SymbolsResponse {
            lastSymbolsSessionId = sessionId
            return com.rmobile.console.data.model.SymbolsResponse(listOf("mean"))
        }
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest): com.rmobile.console.data.model.HelpResponse {
            lastHelp = request
            return com.rmobile.console.data.model.HelpResponse(topic = request.topic, text = "Usage", found = true)
        }
```
Then add two tests at the end of the class (before the closing brace):
```kotlin
    @Test
    fun `help forwards topic and session to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.help("mean", "proj-2")
        assertEquals("mean", api.lastHelp!!.topic)
        assertEquals("proj-2", api.lastHelp!!.sessionId)
        assertTrue(result.getOrNull()!!.found)
    }

    @Test
    fun `symbols forwards the session and wraps the list in a SymbolsResponse`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.symbols("proj-2")
        assertEquals("proj-2", api.lastSymbolsSessionId)
        assertEquals(listOf("mean"), result.getOrNull()!!.symbols)
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.RemoteExecutionEngineTest"`
Expected: compilation failure — `help`/`symbols` are not members of `ExecutionEngine`.

- [ ] **Step 3: Add the interface methods and Remote implementation**

In `ExecutionEngine.kt`, add imports near the other model imports:
```kotlin
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.SymbolsResponse
```
Add to the `ExecutionEngine` interface (after `preview`):
```kotlin
    /** Rendered R help text for [topic]. */
    suspend fun help(topic: String, sessionId: String, libraryKey: String? = null): Result<HelpResponse>
    /** Completion symbol names for the session (base + attached/library()'d exports). */
    suspend fun symbols(sessionId: String, libraryKey: String? = null): Result<SymbolsResponse>
```
Add to `RemoteExecutionEngine` (after its `preview` override):
```kotlin
    override suspend fun help(topic: String, sessionId: String, libraryKey: String?): Result<HelpResponse> =
        repository.help(topic, sessionId)

    override suspend fun symbols(sessionId: String, libraryKey: String?): Result<SymbolsResponse> =
        repository.listSymbols(sessionId).map { SymbolsResponse(it) }
```

- [ ] **Step 4: Update the three `ExecutionEngine` test fakes so the test module compiles**

In `PreviewViewModelTest.kt` `FakeEngine` and `PackagesViewModelTest.kt` `FakeEngine`, add after
their `preview` override (inside each class):
```kotlin
        override suspend fun help(topic: String, sessionId: String, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.HelpResponse())
        override suspend fun symbols(sessionId: String, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.SymbolsResponse())
```

In `EditorViewModelTest.kt` `FakeEngine`, add configurable fields + overrides (Task 4 uses them),
after its `preview` override (before the class's closing brace at line ~140):
```kotlin
        var helpResult: Result<com.rmobile.console.data.model.HelpResponse> =
            Result.success(com.rmobile.console.data.model.HelpResponse(found = false))
        var symbolsResult: Result<com.rmobile.console.data.model.SymbolsResponse> =
            Result.success(com.rmobile.console.data.model.SymbolsResponse())
        var lastHelpTopic: String? = null
        override suspend fun help(topic: String, sessionId: String, libraryKey: String?): Result<com.rmobile.console.data.model.HelpResponse> {
            lastHelpTopic = topic
            return helpResult
        }
        override suspend fun symbols(sessionId: String, libraryKey: String?): Result<com.rmobile.console.data.model.SymbolsResponse> =
            symbolsResult
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.RemoteExecutionEngineTest"`
Expected: PASS (both new tests). If time permits run the full suite; it should stay green
because `RemoteExecutionEngine.symbols` delegates to `repository.listSymbols` (the source the
existing `EditorViewModelTest` symbol tests already stub via `FakeApi.symbolsResponse`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt \
  app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt \
  app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt \
  app/src/test/java/com/rmobile/console/ui/preview/PreviewViewModelTest.kt \
  app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "feat: add help + symbols to ExecutionEngine (Remote delegates to repository)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `LocalExecutionEngine.help` + `symbols` + `WebRController` bridge callers

No JVM unit test — `WebRController` needs an Android WebView, so (exactly like
`LocalExecutionEngine.preview`) this task is verified by compilation here and on-device in Task 5.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`

- [ ] **Step 1: Add the `WebRController` bridge callers**

In `WebRController.kt`, after the `preview` method (line ~169), add:
```kotlin
    suspend fun help(requestJson: String, libraryKey: String): String =
        callBridge("window.webrHelp", org.json.JSONObject.quote(requestJson), org.json.JSONObject.quote(libraryKey))

    suspend fun symbols(sessionId: String, libraryKey: String): String =
        callBridge("window.webrSymbols", org.json.JSONObject.quote(sessionId), org.json.JSONObject.quote(libraryKey))
```

- [ ] **Step 2: Add the `LocalExecutionEngine` implementations**

In `LocalExecutionEngine.kt`, add imports near the other model imports:
```kotlin
import com.rmobile.console.data.model.HelpRequest
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.SymbolsResponse
```
Add these overrides after `preview` (before the class's closing brace):
```kotlin
    override suspend fun help(topic: String, sessionId: String, libraryKey: String?): Result<HelpResponse> = runCatching {
        val reqJson = json.encodeToString(HelpRequest.serializer(), HelpRequest(topic, sessionId))
        json.decodeFromString<HelpResponse>(controller.help(reqJson, libraryKey ?: sessionId))
    }

    override suspend fun symbols(sessionId: String, libraryKey: String?): Result<SymbolsResponse> = runCatching {
        json.decodeFromString<SymbolsResponse>(controller.symbols(sessionId, libraryKey ?: sessionId))
    }
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no reference to WebR runtime behavior yet — pure Kotlin wiring).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/WebRController.kt \
  app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt
git commit -m "feat: LocalExecutionEngine help + symbols via WebRController bridge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `bridge.js` `webrHelp` + `webrSymbols` (on-device R)

No JVM test (JavaScript running in WebR). Verified on-device in Task 5. Keep LF line endings
(`.gitattributes` pins `webr/*.R`; `bridge.js` is plain LF too — do not introduce CRLF).

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js`

- [ ] **Step 1: Add `window.webrHelp`**

After the `window.webrPreview = async (...) => { ... }` block in `bridge.js`, add. This mirrors the
backend `/help` handler: resolve the topic with `utils::help`, fetch the parsed Rd via
`utils:::.getHelpFile`, render with `tools::Rd2txt` to a VFS file, strip terminal overstrike
(`\010` = backspace), read the text back, and post `{topic, packageName, text, found}`.
```javascript
window.webrHelp = async (id, requestJson, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ topic: '', packageName: null, text: '', found: false })); return; }
  const req = JSON.parse(requestJson);
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId, libraryKey || sessionId);
  try {
    // Topic is validated app-side (^[A-Za-z0-9._]+$); pass it as a quoted R string and
    // resolve it via as.name() so it is never spliced into an R code path.
    const topicLit = JSON.stringify(req.topic);
    await webR.evalRVoid(
      `local({ topic <- ${topicLit}; ` +
      `unlink(c('/tmp/rmobile_help.txt','/tmp/rmobile_help_pkg.txt')); ` +
      `h <- tryCatch(eval(substitute(utils::help(TT), list(TT = as.name(topic)))), error = function(e) NULL); ` +
      `if (!is.null(h) && length(h) >= 1) { path <- as.character(h)[1]; ` +
      `  tryCatch({ rd <- utils:::.getHelpFile(path); ` +
      `    tools::Rd2txt(rd, out = '/tmp/rmobile_help.txt'); ` +
      `    writeLines(basename(dirname(dirname(path))), '/tmp/rmobile_help_pkg.txt') }, error = function(e) NULL) } })`
    );
    const exists = async (p) => {
      const r = await webR.evalR(`file.exists(${JSON.stringify(p)})`);
      const v = (await r.toArray())[0] === true; webR.destroy(r); return v;
    };
    let text = '';
    if (await exists('/tmp/rmobile_help.txt')) {
      const bytes = await webR.FS.readFile('/tmp/rmobile_help.txt');
      text = new TextDecoder().decode(bytes);
      // Strip Rd2txt terminal overstrike: `_\bX` (underline) and `X\bX` (bold). \b = \x08.
      text = text.replace(/.\x08/g, '');
    }
    let pkg = null;
    if (await exists('/tmp/rmobile_help_pkg.txt')) {
      const b = await webR.FS.readFile('/tmp/rmobile_help_pkg.txt');
      pkg = new TextDecoder().decode(b).split('\n')[0].trim() || null;
    }
    const found = text.length > 0;
    AndroidBridge.onResult(id, JSON.stringify({ topic: req.topic, packageName: pkg, text, found }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ topic: req.topic, packageName: null, text: '', found: false }));
  }
};
```

- [ ] **Step 2: Add `window.webrSymbols`**

After `window.webrHelp`, add. This mirrors the backend `/symbols`: exports of the default-attached
packages plus whatever is currently attached, filtered to valid identifiers, sorted, capped.
`DEFAULT_ATTACHED` and the 5000 cap match `plumber.R` (`DEFAULT_ATTACHED`, `SYMBOLS_MAX`).
```javascript
window.webrSymbols = async (id, sessionId, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ symbols: [] })); return; }
  const sid = sessionId || 'default';
  await ensureSession(sid, libraryKey || sid);
  try {
    await webR.evalRVoid(
      `local({ base_pkgs <- c('base','methods','datasets','utils','grDevices','graphics','stats'); ` +
      `attached <- sub('^package:', '', grep('^package:', search(), value = TRUE)); ` +
      `pkgs <- unique(c(base_pkgs, attached)); ` +
      `syms <- unlist(lapply(pkgs, function(p) tryCatch(getNamespaceExports(p), error = function(e) character(0)))); ` +
      `syms <- unique(syms[grepl('^[A-Za-z.][A-Za-z0-9._]*$', syms)]); syms <- sort(syms); ` +
      `if (length(syms) > 5000L) syms <- syms[seq_len(5000L)]; ` +
      `writeLines(jsonlite::toJSON(list(symbols = as.list(syms)), auto_unbox = FALSE), '/tmp/rmobile_symbols.json') })`
    );
    const bytes = await webR.FS.readFile('/tmp/rmobile_symbols.json');
    const parsed = JSON.parse(new TextDecoder().decode(bytes));
    AndroidBridge.onResult(id, JSON.stringify({ symbols: parsed.symbols || [] }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ symbols: [] }));
  }
};
```

- [ ] **Step 3: Sanity-check the JS parses (no syntax error)**

Run: `node --check app/src/main/assets/webr/bridge.js`
Expected: exits 0 (no output). If `node` is unavailable, skip — Task 5's on-device run will
surface any syntax error as a bridge failure.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/webr/bridge.js
git commit -m "feat: on-device webrHelp + webrSymbols in the WebR bridge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Route `EditorViewModel.showHelp` + `refreshSymbols` through the engine (local-first)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `EditorViewModelTest.kt` (inside the class). They rely on the `FakeEngine.helpResult` /
`symbolsResult` / `lastHelpTopic` fields added in Task 1, and on `FakeApi` (repository) fields
`helpResponse` / `symbolsResponse` / `lastHelp` that already exist.
```kotlin
    @Test
    fun `showHelp on a Local project routes to the engine and does not hit the backend when found`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(
            com.rmobile.console.data.model.HelpResponse(topic = "aes", text = "Aesthetics", found = true),
        )
        val api = FakeApi()
        val vm = viewModel(api = api, engineProvider = { _ -> engine }) // defaultEngine = LOCAL
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals("aes", engine.lastHelpTopic)
        assertEquals(null, api.lastHelp) // no Remote fallback when the engine found it
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded && help.response.text == "Aesthetics")
    }

    @Test
    fun `showHelp on a Local project falls back to the backend when on-device help is not found`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(com.rmobile.console.data.model.HelpResponse(topic = "aes", found = false))
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(topic = "aes", packageName = "ggplot2", text = "From backend", found = true)
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals("aes", api.lastHelp!!.topic) // fallback consulted
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded && help.response.text == "From backend")
    }

    @Test
    fun `showHelp shows NotFound when neither the engine nor the backend has it`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(com.rmobile.console.data.model.HelpResponse(found = false))
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(found = false)
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        vm.showHelp("nope")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.help is HelpState.NotFound)
    }

    @Test
    fun `showHelp on a Remote project does not fall back and surfaces the error`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.failure(RuntimeException("boom"))
        val api = FakeApi()
        val vm = viewModel(
            api = api,
            defaultEngine = { ExecutionEngineChoice.REMOTE },
            engineProvider = { _ -> engine },
        )
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals(null, api.lastHelp) // REMOTE: no second (fallback) call
        assertTrue(vm.uiState.value.help is HelpState.Error)
    }

    @Test
    fun `refreshSymbols on a Local project uses the engine symbols`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.symbolsResult = Result.success(com.rmobile.console.data.model.SymbolsResponse(listOf("engine_sym")))
        val vm = viewModel(engineProvider = { _ -> engine })
        advanceUntilIdle() // init calls refreshSymbols()
        assertTrue(vm.uiState.value.completionSymbols.contains("engine_sym"))
    }

    @Test
    fun `refreshSymbols falls back to backend symbols when the engine fails`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.symbolsResult = Result.failure(RuntimeException("offline"))
        val api = FakeApi()
        api.symbolsResponse = com.rmobile.console.data.model.SymbolsResponse(listOf("backend_sym"))
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completionSymbols.contains("backend_sym"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: the six new tests FAIL — `showHelp` still calls `repository.help` directly (so a Local
project hits the backend and `engine.lastHelpTopic` stays null; a Remote engine failure still
routes through the repository), and `refreshSymbols` still uses `repository.listSymbols`.

- [ ] **Step 3: Rewrite `showHelp` to route through the engine (local-first)**

Replace the `showHelp` body (currently `.../EditorViewModel.kt:363-386`) with:
```kotlin
    fun showHelp(topic: String) {
        val t = topic.trim()
        if (t.isEmpty()) return
        // Stamp each request so a stale response (e.g. after the user dismissed the
        // sheet, or fired a newer lookup) can't overwrite current help state.
        val requestId = ++helpRequestId
        val project = _uiState.value.project
        val choice = ExecutionEngineChoice.resolve(project.engine, defaultEngine())
        val session = ProjectSession.of(project)
        val libraryKey = ProjectSession.libraryKey(project)
        _uiState.update { it.copy(help = HelpState.Loading(t)) }
        viewModelScope.launch {
            // Local-first: try the resolved engine (on-device for Local projects). If a Local
            // lookup errors or finds nothing, fall back to the Remote backend when one is
            // reachable — a failed fallback degrades to the local not-found (no error banner).
            val primary = engineProvider(choice).help(t, session, libraryKey)
            var resp = primary.getOrNull()
            var failure = primary.exceptionOrNull()
            if (choice == ExecutionEngineChoice.LOCAL && (resp == null || !resp.found)) {
                repository.help(t, session).getOrNull()?.let { fb ->
                    if (fb.found) { resp = fb; failure = null }
                }
            }
            if (requestId != helpRequestId) return@launch
            val r = resp
            _uiState.update {
                it.copy(
                    help = when {
                        r != null && r.found -> HelpState.Loaded(r)
                        r != null -> HelpState.NotFound(t)
                        failure != null -> HelpState.Error(t, failure.message ?: "Failed to load help.")
                        else -> HelpState.NotFound(t)
                    },
                )
            }
        }
    }
```

- [ ] **Step 4: Rewrite `refreshSymbols` to route through the engine (local-first)**

Replace the `refreshSymbols` body (currently `.../EditorViewModel.kt:350-361`) with:
```kotlin
    /** Refresh the cached symbol index + package names for the active project's session. */
    fun refreshSymbols() {
        val project = _uiState.value.project
        val choice = ExecutionEngineChoice.resolve(project.engine, defaultEngine())
        val session = ProjectSession.of(project)
        val libraryKey = ProjectSession.libraryKey(project)
        val engine = engineProvider(choice)
        viewModelScope.launch {
            var syms = engine.symbols(session, libraryKey).getOrNull()?.symbols
            var pkgs = engine.listPackages(session, libraryKey).getOrNull()?.packages
            // Local-first: if an on-device lookup fails, fall back to the backend when reachable.
            if (choice == ExecutionEngineChoice.LOCAL) {
                if (syms == null) syms = repository.listSymbols(session).getOrNull()
                if (pkgs == null) pkgs = repository.listPackages(session).getOrNull()?.packages
            }
            if (ProjectSession.of(_uiState.value.project) == session) {
                symbolIndex = syms.orEmpty()
                packageNames = pkgs.orEmpty()
                recomputeSymbols()
            }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: PASS (all tests, including the existing `refreshSymbols`/help tests — the default
`viewModel()` wires `RemoteExecutionEngine(repo)`, whose `symbols`/`help` still delegate to the
repository the existing tests stub).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
  app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "feat: route help + symbols through the engine, local-first with remote fallback

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Docs + full verification (incl. on-device acceptance & the help-DB spike)

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `CLAUDE.md`**

In the `ui/editor/` code-assist paragraph (the sentence describing `showHelp` / `/help` and the
`/symbols` index), add a note that both are now **engine-routed, local-first**. Suggested wording to
weave in:
> Help and the completion `/symbols` index are **engine-routed** (like execute/preview):
> `EditorViewModel.showHelp`/`refreshSymbols` call the active project's `ExecutionEngine`
> (`help`/`symbols`) — Local resolves them on-device via `bridge.js` `webrHelp`/`webrSymbols`
> (real `tools::Rd2txt` + `getNamespaceExports`, no network), Remote via the backend
> `/help`/`/symbols`. On a Local project a not-found/failed on-device lookup falls back
> best-effort to the Remote backend when one is reachable; a failed fallback degrades quietly
> (help → `NotFound`, not an error banner).

Also add `help`/`symbols` to the list of `ExecutionEngine` methods in the `data/execution/`
paragraph (it currently enumerates `listPackages`/`install`/`uninstall`/`preview`).

- [ ] **Step 2: Full unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Debug build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: On-device acceptance (manual — WebR can't run under JVM tests)**

Install on a device/emulator (`.\gradlew.bat :app:installDebug`) and verify on a **Local** project:
1. **Help-DB spike:** with the device in **airplane mode**, top-bar `?` search `sum` (or type `?sum`
   and tap the `? sum` chip) → the help sheet shows base R's `sum` help text, no connection error.
   - If base help text does NOT appear offline, WebR ships no base help DB. That is acceptable and
     expected to be covered by the Remote fallback: turn airplane mode off with a backend configured
     and confirm `?sum` then resolves via the backend. Note the finding in the commit message.
2. Install `ggplot2` on-device (Packages screen), then `?aes` renders help offline.
3. Autocomplete shows an installed package's completions offline (type a prefix of an exported
   function and confirm a chip appears without a backend).
4. Regression: on a **Remote** project with a reachable backend, `?mean` and completions still work;
   with an **unreachable** backend, `?mean` shows the error banner (not a bland "not found").

- [ ] **Step 5: Commit docs**

```bash
git add CLAUDE.md
git commit -m "docs: help + symbols are engine-routed, local-first (WebR on-device)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Notes for the executor

- **Do not** run `/code-review ultra`.
- **Do not** `git add -A` — the repo has untracked CSV fixtures (`200mb.csv`, `military.csv`,
  `sales.csv`) that must stay uncommitted. Add only the files each task lists.
- Every commit message ends with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.
- Keep `bridge.js` and `harness.R` at LF line endings.
- Backend (`plumber.R`) is intentionally **not** changed — `/help` and `/symbols` stay as the
  Remote implementation and the fallback target.
