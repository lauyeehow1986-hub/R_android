# Local-engine data import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Local (WebR) engine use uploaded data files — stored on-device per project, readable by on-device runs by bare name, and previewable as a table — with list/delete/insert parity with the Remote Data screen.

**Architecture:** A `SessionDataStore` abstraction (Remote = backend endpoints; Local = Android private storage) routed by the current engine, plus a lazy incremental sync of a session's files into WebR's in-memory FS (`/rmobile/data/<session>`) that gets symlinked into each run dir. `preview` is added to `ExecutionEngine` so local file/object preview runs in WebR.

**Tech Stack:** Kotlin + Jetpack Compose, kotlinx.serialization, JUnit + coroutines-test, WebR 0.4.2 (WASM in a WebView), OkHttp (Remote uploads).

**Reference:** `docs/superpowers/specs/2026-07-13-local-data-import-design.md`.

**Environment note:** JVM tests need `JAVA_HOME` (Android Studio JBR). Bash tool: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. Ignore "LF will be replaced by CRLF" git warnings.

**Verification legend:** 🟢 CI/JVM — a subagent fully verifies via `./gradlew :app:testDebugUnitTest`. 🔵 Device — WebR/WebView behavior; the subagent implements to spec, the **user** runs the manual checklist in the final task.

---

## File Structure

- `app/.../data/datafiles/SessionDataStore.kt` — **create**: interface, `DataUpload`, pure `DataFileName` sanitizer.
- `app/.../data/datafiles/RemoteSessionDataStore.kt` — **create**: wraps `RExecutionRepository`.
- `app/.../data/datafiles/LocalSessionDataStore.kt` — **create**: Android-files store (takes a base `File`).
- `app/.../data/network/UriRequestBody.kt` — **modify**: stream from an `openStream` lambda.
- `app/.../data/ServiceLocator.kt` — **modify**: `currentSessionDataStore()`.
- `app/.../data/execution/ExecutionEngine.kt` — **modify**: add `preview`; implement in Remote.
- `app/.../data/execution/LocalExecutionEngine.kt` + `WebRController.kt` — **modify**: `preview` + `dataList`/`dataChunk` bridge.
- `app/.../ui/data/DataViewModel.kt` / `DataUiState.kt` / `DataScreen.kt` — **modify**: route through store, `DataUpload`, `onShown`, >200 MB warning, engine-aware caption.
- `app/.../ui/preview/PreviewViewModel.kt` — **modify**: route through engine, `onShown`.
- `app/src/main/assets/webr/bridge.js` — **modify**: `syncData`/`linkData`/`previewData` + run injection.
- Tests + docs as noted per task.

---

## Task 1: SessionDataStore (interface + sanitizer + Remote + Local)

🟢 CI/JVM.

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/datafiles/SessionDataStore.kt`
- Create: `app/src/main/java/com/rmobile/console/data/datafiles/LocalSessionDataStore.kt`
- Create: `app/src/main/java/com/rmobile/console/data/datafiles/RemoteSessionDataStore.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/network/UriRequestBody.kt`
- Test: `app/src/test/java/com/rmobile/console/data/datafiles/LocalSessionDataStoreTest.kt`
- Test: `app/src/test/java/com/rmobile/console/data/datafiles/DataFileNameTest.kt`

- [ ] **Step 1: Write failing tests**

`DataFileNameTest.kt`:
```kotlin
package com.rmobile.console.data.datafiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataFileNameTest {
    @Test fun `keeps a normal name`() = assertEquals("sales.csv", DataFileName.sanitize("sales.csv"))
    @Test fun `strips directory components`() = assertEquals("a.csv", DataFileName.sanitize("/tmp/x/a.csv"))
    @Test fun `drops disallowed characters`() = assertEquals("ab.csv", DataFileName.sanitize("a b.csv"))
    @Test fun `rejects empty`() = assertNull(DataFileName.sanitize("   "))
    @Test fun `rejects reserved names`() {
        assertNull(DataFileName.sanitize("script.R"))
        assertNull(DataFileName.sanitize("objects.txt"))
        assertNull(DataFileName.sanitize("main.R"))
        assertNull(DataFileName.sanitize("plot001.png"))
        assertNull(DataFileName.sanitize("table012.json"))
    }
}
```

`LocalSessionDataStoreTest.kt`:
```kotlin
package com.rmobile.console.data.datafiles

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class LocalSessionDataStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LocalSessionDataStore(tmp.root)
    private fun upload(name: String, bytes: ByteArray) =
        DataUpload(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    @Test fun `save then list reports the file and size`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "hello".toByteArray())).getOrThrow()
        val files = s.list("proj-1").getOrThrow()
        assertEquals(1, files.size)
        assertEquals("a.csv", files[0].name)
        assertEquals(5L, files[0].size)
    }

    @Test fun `sessions are isolated`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "x".toByteArray())).getOrThrow()
        assertTrue(s.list("proj-2").getOrThrow().isEmpty())
    }

    @Test fun `delete removes the file`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "x".toByteArray())).getOrThrow()
        assertTrue(s.delete("proj-1", "a.csv").getOrThrow())
        assertTrue(s.list("proj-1").getOrThrow().isEmpty())
    }

    @Test fun `delete missing returns false`() = runTest {
        assertFalse(store().delete("proj-1", "nope.csv").getOrThrow())
    }

    @Test fun `rejects a reserved filename`() = runTest {
        val r = store().save("proj-1", upload("script.R", "x".toByteArray()))
        assertTrue(r.isFailure)
    }
}
```

- [ ] **Step 2: Run to see them fail** (compile error — types don't exist)

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.datafiles.*"`
Expected: FAIL (unresolved `LocalSessionDataStore`/`DataFileName`/`DataUpload`).

- [ ] **Step 3: Implement `SessionDataStore.kt`**

```kotlin
package com.rmobile.console.data.datafiles

import com.rmobile.console.data.model.DataFile
import java.io.InputStream

/** Engine-agnostic upload source. openStream is reopened per read (never buffered whole). */
class DataUpload(val name: String, val size: Long, val openStream: () -> InputStream)

/** Where a session's uploaded data files live. Remote = backend; Local = on-device storage. */
interface SessionDataStore {
    suspend fun list(sessionId: String): Result<List<DataFile>>
    suspend fun save(sessionId: String, upload: DataUpload): Result<Unit>
    suspend fun delete(sessionId: String, name: String): Result<Boolean>
}

/** Pure filename policy, mirroring the backend: basename, keep [A-Za-z0-9._-], reject empty/reserved. */
object DataFileName {
    private val reserved = setOf("script.R", "objects.txt", "main.R")
    private val reservedPattern = Regex("^(plot[0-9]+\\.png|table[0-9]+\\.json)$")

    fun sanitize(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (cleaned.isBlank()) return null
        if (cleaned in reserved || reservedPattern.matches(cleaned)) return null
        return cleaned
    }
}
```

- [ ] **Step 4: Implement `LocalSessionDataStore.kt`**

```kotlin
package com.rmobile.console.data.datafiles

import com.rmobile.console.data.model.DataFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stores a session's data files under baseDir/<sessionId>/<name> on device. */
class LocalSessionDataStore(private val baseDir: File) : SessionDataStore {

    private fun sessionDir(sessionId: String): File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }
        return File(baseDir, safe)
    }

    override suspend fun list(sessionId: String): Result<List<DataFile>> = withContext(Dispatchers.IO) {
        runCatching {
            (sessionDir(sessionId).listFiles() ?: emptyArray())
                .filter { it.isFile }
                .map { DataFile(it.name, it.length()) }
                .sortedBy { it.name }
        }
    }

    override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val name = DataFileName.sanitize(upload.name) ?: error("Invalid or reserved filename.")
            val dir = sessionDir(sessionId).apply { mkdirs() }
            upload.openStream().use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    override suspend fun delete(sessionId: String, name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val safe = DataFileName.sanitize(name) ?: return@runCatching false
            val f = File(sessionDir(sessionId), safe)
            f.exists() && f.delete()
        }
    }
}
```

- [ ] **Step 5: Implement `RemoteSessionDataStore.kt`** (build a multipart part from `DataUpload`)

```kotlin
package com.rmobile.console.data.datafiles

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.network.UriRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody

/** Delegates to the backend data endpoints (unchanged behavior). */
class RemoteSessionDataStore(private val repository: RExecutionRepository) : SessionDataStore {
    override suspend fun list(sessionId: String): Result<List<DataFile>> = repository.listDataFiles(sessionId)

    override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> {
        val body = UriRequestBody(upload.openStream, upload.size, "application/octet-stream".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", upload.name, body)
        return repository.uploadFile(sessionId, part).map { }
    }

    override suspend fun delete(sessionId: String, name: String): Result<Boolean> =
        repository.deleteDataFile(name, sessionId)
}
```

- [ ] **Step 6: Refactor `UriRequestBody`** to stream from a lambda

```kotlin
package com.rmobile.console.data.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream

/** Streams from [openStream] (reopened per writeTo, so it's reusable) without buffering in memory. */
class UriRequestBody(
    private val openStream: () -> InputStream,
    private val contentLength: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = contentLength
    override fun writeTo(sink: BufferedSink) {
        openStream().source().use { sink.writeAll(it) }
    }
}
```
(The old `DataScreen` call site changes in Task 3.)

- [ ] **Step 7: Run tests → PASS.** `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.datafiles.*"`

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/datafiles app/src/main/java/com/rmobile/console/data/network/UriRequestBody.kt app/src/test/java/com/rmobile/console/data/datafiles
git commit -m "$(cat <<'EOF'
feat: SessionDataStore (Remote backend + Local on-device) with sanitizer

Local store persists a session's uploaded files under a per-session dir;
UriRequestBody now streams from an openStream lambda so both stores share one
upload source (DataUpload).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ServiceLocator.currentSessionDataStore()

🟢 CI/JVM (compile; wiring).

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/ServiceLocator.kt`

- [ ] **Step 1: Add the store providers and selector**

Add imports for `RExecutionRepository` (already present), `NetworkModule` (present), and:
```kotlin
import com.rmobile.console.data.datafiles.LocalSessionDataStore
import com.rmobile.console.data.datafiles.RemoteSessionDataStore
import com.rmobile.console.data.datafiles.SessionDataStore
import java.io.File
```
Add lazy instances + selector:
```kotlin
    private val remoteDataStore: SessionDataStore by lazy {
        RemoteSessionDataStore(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localDataStore: SessionDataStore by lazy {
        LocalSessionDataStore(File(appContext.filesDir, "localdata"))
    }

    /** The data store for the currently-selected engine; read fresh so a toggle takes effect. */
    fun currentSessionDataStore(): SessionDataStore =
        when (settingsStore.executionEngine) {
            ExecutionEngineChoice.LOCAL -> localDataStore
            ExecutionEngineChoice.REMOTE -> remoteDataStore
        }
```

- [ ] **Step 2: Compile.** `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/ServiceLocator.kt
git commit -m "$(cat <<'EOF'
feat: ServiceLocator.currentSessionDataStore() routes data ops by engine

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: DataViewModel + DataScreen route through the store

🟢 CI/JVM for the ViewModel; 🔵 the screen compiles and is verified visually later.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/data/DataUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/data/DataViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/data/DataViewModelTest.kt`

- [ ] **Step 1: Write failing tests** (rewrite `DataViewModelTest.kt` to use a fake store)

Replace the API-based fakes with a `FakeSessionDataStore` and reconstruct the VM through it:
```kotlin
package com.rmobile.console.ui.data

import com.rmobile.console.data.datafiles.DataUpload
import com.rmobile.console.data.datafiles.SessionDataStore
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakeStore(var files: List<DataFile> = listOf(DataFile("a.csv", 10))) : SessionDataStore {
        var lastListSession: String? = null
        var lastSaveSession: String? = null
        var deleted: String? = null
        override suspend fun list(sessionId: String): Result<List<DataFile>> { lastListSession = sessionId; return Result.success(files) }
        override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> { lastSaveSession = sessionId; return Result.success(Unit) }
        override suspend fun delete(sessionId: String, name: String): Result<Boolean> { deleted = name; return Result.success(true) }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun upload(name: String, n: Int = 3) = DataUpload(name, n.toLong()) { ByteArrayInputStream(ByteArray(n)) }

    private fun vm(store: FakeStore, projectStore: ProjectStore = InMemoryProjectStore(), local: Boolean = true) =
        DataViewModel(
            dataStoreProvider = { store },
            projectStore = projectStore,
            engineIsLocalProvider = { local },
        )

    @Test fun `loads files on init`() = runTest {
        val vm = vm(FakeStore(listOf(DataFile("a.csv", 1), DataFile("b.csv", 2))))
        advanceUntilIdle()
        assertEquals(listOf("a.csv", "b.csv"), vm.uiState.value.files.map { it.name })
    }

    @Test fun `upload routes to the store and refreshes`() = runTest {
        val store = FakeStore(emptyList())
        val vm = vm(store)
        advanceUntilIdle()
        store.files = listOf(DataFile("new.csv", 3))
        vm.upload(upload("new.csv"))
        advanceUntilIdle()
        assertEquals("new.csv", store.lastSaveSession?.let { "new.csv" })
        assertEquals(listOf("new.csv"), vm.uiState.value.files.map { it.name })
    }

    @Test fun `delete routes to the store`() = runTest {
        val store = FakeStore(listOf(DataFile("a.csv", 1)))
        val vm = vm(store)
        advanceUntilIdle()
        store.files = emptyList()
        vm.delete("a.csv")
        advanceUntilIdle()
        assertEquals("a.csv", store.deleted)
        assertTrue(vm.uiState.value.files.isEmpty())
    }

    @Test fun `uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 7, name = "P", now = 0)
        val store = FakeStore()
        vm(store, InMemoryProjectStore(listOf(project), 7))
        advanceUntilIdle()
        assertEquals("proj-7", store.lastListSession)
    }

    @Test fun `large-file warning flag`() = runTest {
        val vm = vm(FakeStore())
        advanceUntilIdle()
        assertFalse(vm.warnLargeFile(100L * 1024 * 1024))       // 100 MB: no warn
        assertTrue(vm.warnLargeFile(300L * 1024 * 1024))        // 300 MB: warn
    }
}
```

- [ ] **Step 2: Run → fail** (`DataViewModel` has no `dataStoreProvider`/`engineIsLocalProvider`/`warnLargeFile`).

- [ ] **Step 3: Update `DataUiState`** — add `engineIsLocal` (default false).

```kotlin
data class DataUiState(
    val projectName: String = "",
    val files: List<DataFile> = emptyList(),
    val isLoading: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
    val engineIsLocal: Boolean = false,
)
```

- [ ] **Step 4: Rewrite `DataViewModel`** to route through the store

```kotlin
class DataViewModel(
    private val dataStoreProvider: () -> SessionDataStore = { ServiceLocator.currentSessionDataStore() },
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val engineIsLocalProvider: () -> Boolean =
        { ServiceLocator.settingsStore.executionEngine == ExecutionEngineChoice.LOCAL },
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataUiState())
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()
    private var session: String = RExecutionRepository.DEFAULT_SESSION_ID

    init { resolveContext(); refresh() }

    private fun resolveContext() {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() } ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        _uiState.update { it.copy(projectName = active?.name ?: "", engineIsLocal = engineIsLocalProvider()) }
    }

    /** Re-resolve engine/session and reload when the screen appears. */
    fun onShown() { resolveContext(); refresh() }

    /** True if a file this big risks OOM on the in-memory Local engine. */
    fun warnLargeFile(size: Long): Boolean =
        engineIsLocalProvider() && size > 200L * 1024 * 1024

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            dataStoreProvider().list(session)
                .onSuccess { files -> _uiState.update { it.copy(files = files, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.message ?: "Failed to load files.") } }
        }
    }

    fun upload(upload: DataUpload) {
        if (_uiState.value.uploading) return
        _uiState.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            dataStoreProvider().save(session, upload)
                .onSuccess { _uiState.update { it.copy(uploading = false) }; refresh() }
                .onFailure { t -> _uiState.update { it.copy(uploading = false, error = t.message ?: "Upload failed.") } }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            dataStoreProvider().delete(session, name)
                .onSuccess { removed -> if (removed) refresh() else _uiState.update { it.copy(error = "$name was not deleted.") } }
                .onFailure { t -> _uiState.update { it.copy(error = t.message ?: "Delete failed.") } }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }
}
```
Add imports: `com.rmobile.console.data.datafiles.DataUpload`, `com.rmobile.console.data.datafiles.SessionDataStore`, `com.rmobile.console.data.settings.ExecutionEngineChoice`. Remove the now-unused `repository`/`NetworkModule`/`MultipartBody` imports.

- [ ] **Step 5: Update `DataScreen`** — build a `DataUpload`, warn on large local files, engine-aware caption, `onShown`

- SAF picker callback: read name + size (as today), then:
  ```kotlin
  val upload = com.rmobile.console.data.datafiles.DataUpload(name, size) {
      context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
  }
  if (size in 1..Long.MAX_VALUE && viewModel.warnLargeFile(size)) {
      pendingLargeUpload = upload   // show a confirm dialog
  } else {
      viewModel.upload(upload)
  }
  ```
  Add a simple `AlertDialog` (state `pendingLargeUpload`) warning: "This file is over 200 MB. The on-device engine keeps files in memory and may run out of memory — Remote handles large data better. Upload anyway?" Confirm → `viewModel.upload(it)`.
- Add `LaunchedEffect(Unit) { viewModel.onShown() }` (import `androidx.compose.runtime.LaunchedEffect`).
- Replace the fixed "these apply to the Remote engine" note with an engine-aware one from `state.engineIsLocal` ("On-device (Local) data files — readable by your R code by name." vs the Remote wording).
- Remove the `UriRequestBody`/`MultipartBody`/`toMediaTypeOrNull` imports (no longer built here).

- [ ] **Step 6: Run tests → PASS** (`DataViewModelTest`), then full suite. Also `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/data app/src/test/java/com/rmobile/console/ui/data/DataViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat: Data screen routes through the engine's SessionDataStore

Upload/list/delete target ServiceLocator.currentSessionDataStore(); the screen
builds an engine-neutral DataUpload, re-resolves on show, warns above 200 MB on
Local, and shows an engine-aware caption.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: preview on ExecutionEngine + PreviewViewModel routing

🟢 CI/JVM for Remote delegation + ViewModel; Local delegate compiles (device-verified in Task 5/6).

**Files:**
- Modify: `app/.../data/execution/ExecutionEngine.kt` (interface + Remote)
- Modify: `app/.../data/execution/LocalExecutionEngine.kt` + `WebRController.kt`
- Modify: `app/.../ui/preview/PreviewViewModel.kt`
- Test: `app/.../data/execution/RemoteExecutionEngineTest.kt`
- Modify: every other `ExecutionEngine` test fake (see Step 4)

- [ ] **Step 1: Write failing test** (append to `RemoteExecutionEngineTest.kt`)

Extend `RecordingApi` to record preview, add a test:
```kotlin
    // in RecordingApi:
    var lastPreview: com.rmobile.console.data.model.PreviewRequest? = null
    override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest): com.rmobile.console.data.model.PreviewResponse {
        lastPreview = request
        return com.rmobile.console.data.model.PreviewResponse(truncated = true)
    }

    @Test
    fun `preview forwards the request to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.preview(com.rmobile.console.data.model.PreviewRequest("file", "a.csv", "proj-2"))
        assertEquals("a.csv", api.lastPreview!!.name)
        assertEquals("proj-2", api.lastPreview!!.sessionId)
        assertTrue(result.getOrNull()!!.truncated)
    }
```
(If `RecordingApi` already has a non-recording `preview` override, replace it.)

- [ ] **Step 2: Run → fail** (`engine.preview` unresolved).

- [ ] **Step 3: Add `preview` to `ExecutionEngine` + Remote**

Interface:
```kotlin
    /** Read-only preview of a data file or workspace object as a table. */
    suspend fun preview(request: PreviewRequest): Result<PreviewResponse>
```
Imports `com.rmobile.console.data.model.PreviewRequest`, `PreviewResponse`. `RemoteExecutionEngine`:
```kotlin
    override suspend fun preview(request: PreviewRequest): Result<PreviewResponse> =
        repository.preview(request.source, request.name, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)
```
(Check `repository.preview` signature at `RExecutionRepository.kt:66` and match arg order.)

- [ ] **Step 4: Implement Local + controller, and fix all `ExecutionEngine` fakes**

`WebRController` — add:
```kotlin
    suspend fun preview(requestJson: String): String = callBridge("window.webrPreview", org.json.JSONObject.quote(requestJson))
```
`LocalExecutionEngine` — add:
```kotlin
    override suspend fun preview(request: PreviewRequest): Result<PreviewResponse> = runCatching {
        val reqJson = json.encodeToString(PreviewRequest.serializer(), request)
        json.decodeFromString<PreviewResponse>(controller.preview(reqJson))
    }
```
(imports for `PreviewRequest`/`PreviewResponse`.)

Then add `override suspend fun preview(request: PreviewRequest) = Result.success(PreviewResponse())` (or a recording variant) to **every** test fake implementing `ExecutionEngine`: search `app/src/test` for `: ExecutionEngine` / `: com.rmobile.console.data.execution.ExecutionEngine` and update each (`EditorViewModelTest`, `PackagesViewModelTest`'s `FakeEngine`, and any others).

- [ ] **Step 5: Route `PreviewViewModel` through the engine + `onShown`**

Change constructor to inject an engine provider and re-resolve:
```kotlin
class PreviewViewModel(
    private val engineProvider: () -> ExecutionEngine = { ServiceLocator.currentExecutionEngine() },
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {
    ...
    private var session = RExecutionRepository.DEFAULT_SESSION_ID
    init { resolveSession() }
    private fun resolveSession() { /* same active-project resolution */ }
    fun load(source: String, name: String) {
        resolveSession()
        _uiState.value = PreviewUiState(title = name, isLoading = true)
        viewModelScope.launch {
            engineProvider().preview(PreviewRequest(source, name, session))
                .onSuccess { resp -> _uiState.update { it.copy(isLoading = false, table = resp.table, error = resp.error, truncated = resp.truncated, totalRows = resp.table?.totalRows ?: 0) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.message ?: "Preview failed.") } }
        }
    }
}
```
Update `PreviewViewModelTest` to build the VM with a fake engine whose `preview` returns a known `PreviewResponse`; assert the state maps through. (Match the existing test's construction pattern; if it constructed with a repository, switch it to `engineProvider = { fakeEngine }`.)

- [ ] **Step 6: Run the full suite → PASS.** `./gradlew :app:testDebugUnitTest`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution app/src/main/java/com/rmobile/console/ui/preview app/src/test/java/com/rmobile/console
git commit -m "$(cat <<'EOF'
feat: engine-routed preview (Remote backend, Local WebR)

Adds preview() to ExecutionEngine; PreviewViewModel routes through the active
engine and re-resolves on load. Local delegates to a WebR bridge (added next).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: bridge.js — data sync, run injection, and previewData

🔵 Device. Implement to spec; verified in Task 6.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt` (Bridge data accessors)
- Modify: `app/src/main/assets/webr/bridge.js`

- [ ] **Step 1: WebRController — expose on-device data files to JS**

In `WebRController`, add a `localDataDir` and Bridge methods (mirror the snapshot chunk methods). Data lives where `LocalSessionDataStore` wrote it: `filesDir/localdata/<sanitized-session>/`.
```kotlin
    private fun localDataDir(sessionId: String): java.io.File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }
        return java.io.File(java.io.File(appContext.filesDir, "localdata"), safe)
    }
```
In `inner class Bridge`:
```kotlin
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
```
(`b64ToBytes` already exists in bridge.js from the package snapshot work; reuse it.)

- [ ] **Step 2: bridge.js — sync + link + run injection**

Add constants and helpers near the snapshot helpers:
```js
const DATA_CHUNK = 512 * 1024;

// Lazily mirror the session's on-device data files into /rmobile/data/<session>
// (persists for the app process), pulling only new/changed files and dropping
// deleted ones. Chunked base64 transfer, like the library snapshot.
async function syncData(sessionId) {
  const dir = `/rmobile/data/${sessionId}`;
  await webR.evalRVoid(`dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE)`);
  let wanted;
  try { wanted = JSON.parse(AndroidBridge.dataList(sessionId)); } catch (e) { return; }
  const wantedNames = new Set(wanted.map((f) => f.name));
  // Drop files no longer on device.
  const haveR = await webR.evalR(`list.files(${JSON.stringify(dir)})`);
  const have = await haveR.toArray(); webR.destroy(haveR);
  for (const name of have) if (!wantedNames.has(name)) {
    await webR.evalRVoid(`unlink(file.path(${JSON.stringify(dir)}, ${JSON.stringify(name)}))`);
  }
  // Pull new / size-changed files.
  for (const f of wanted) {
    const path = `${dir}/${f.name}`;
    let size = -1;
    try { const sz = await webR.evalR(`if (file.exists(${JSON.stringify(path)})) file.info(${JSON.stringify(path)})$size else -1`); size = (await sz.toArray())[0]; webR.destroy(sz); } catch (e) {}
    if (size === f.size) continue;
    const parts = [];
    for (let off = 0; off < f.size; off += DATA_CHUNK) {
      const b64 = AndroidBridge.dataChunk(sessionId, f.name, off, DATA_CHUNK);
      if (!b64) break;
      parts.push(b64ToBytes(b64));
    }
    let total = 0; for (const p of parts) total += p.length;
    const all = new Uint8Array(total); let o = 0; for (const p of parts) { all.set(p, o); o += p.length; }
    await webR.FS.writeFile(path, all);
  }
}

// Make the session's data files readable by bare name from the run cwd.
async function linkData(sessionId) {
  const dir = `/rmobile/data/${sessionId}`;
  await webR.evalRVoid(
    `local({ d <- ${JSON.stringify(dir)}; if (dir.exists(d)) for (f in list.files(d, full.names = TRUE)) ` +
    `try(file.symlink(f, file.path("/rmobile/run", basename(f))), silent = TRUE) })`
  );
}
```
In `runOnce`, after `resetRunDir()` (and after writing the entry/harness files? — do it **before** writing entry files so a run's own files win, matching the backend), insert:
```js
  if (req.sessionId) { await syncData(req.sessionId); await linkData(req.sessionId); }
```
Place the two lines right after `await resetRunDir();`.

> **Device-verify note:** if `file.symlink` doesn't work in WebR's FS, change `linkData` to copy: `file.copy(f, file.path("/rmobile/run", basename(f)), overwrite = TRUE)`. Decide on-device in Task 6.

- [ ] **Step 3: bridge.js — `window.webrPreview`**

```js
// Read-only preview of a data file or workspace object as one RTable. Reuses the
// harness table emitter's format via a small inline emit.
window.webrPreview = async (id, requestJson) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ table: null, error: 'WebR not ready', truncated: false })); return; }
  const req = JSON.parse(requestJson);
  const shelter = await new webR.Shelter();
  try {
    if (req.source === 'file' && req.sessionId) await syncData(req.sessionId);
    const path = req.source === 'file' ? `/rmobile/data/${req.sessionId}/${req.name}` : null;
    // Build a data.frame `.pv` from the file (by extension) or the workspace object.
    const rExpr = req.source === 'file'
      ? `local({ p <- ${JSON.stringify(path)}; ext <- tolower(tools::file_ext(p)); ` +
        `if (ext %in% c('csv')) read.csv(p, check.names = FALSE) ` +
        `else if (ext %in% c('tsv')) read.delim(p, check.names = FALSE) ` +
        `else if (ext %in% c('rds')) readRDS(p) ` +
        `else stop(sprintf('Preview of .%s files needs the Remote engine.', ext)) })`
      : `get(${JSON.stringify(req.name)}, envir = globalenv())`;
    // Reuse the harness .emit by sourcing harness.R's helpers is overkill here;
    // emit a compact table via the same JSON shape the app expects.
    await webR.evalRVoid(`.pv <- try(${rExpr}, silent = TRUE)`);
    const okR = await webR.evalR(`!inherits(.pv, "try-error")`);
    const ok = (await okR.toArray())[0] === true; webR.destroy(okR);
    if (!ok) {
      const eR = await webR.evalR(`as.character(attr(.pv, "condition")$message)`);
      const msg = (await eR.toArray())[0] || 'Could not read for preview.'; webR.destroy(eR);
      AndroidBridge.onResult(id, JSON.stringify({ table: null, error: String(msg), truncated: false }));
      return;
    }
    const tableJson = await webR.evalR(
      `local({ df <- as.data.frame(.pv); n <- nrow(df); sub <- utils::head(df, 200); ` +
      `cols <- names(sub); types <- vapply(sub, function(c) class(c)[1], character(1)); ` +
      `cells <- lapply(sub, function(c) as.character(format(c, trim = TRUE))); ` +
      `rows <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(c) c[i], character(1)))); ` +
      `jsonlite::toJSON(list(columns = as.character(cols), columnTypes = as.character(types), rows = rows, ` +
      `totalRows = jsonlite::unbox(as.integer(n))), auto_unbox = FALSE) })`
    );
    const table = JSON.parse((await tableJson.toArray())[0]); webR.destroy(tableJson);
    await webR.evalRVoid('rm(.pv)');
    AndroidBridge.onResult(id, JSON.stringify({ table, error: null, truncated: table.totalRows > 200 }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ table: null, error: String(e), truncated: false }));
  } finally { shelter.purge(); }
};
```
> **Device-verify:** confirm the emitted table JSON matches `RTable` (columns/columnTypes/rows/totalRows) — compare against `harness.R`'s `.emit`. Adjust to match byte-for-byte if the app fails to parse.

- [ ] **Step 4: Syntax-check + commit** — `node --check app/src/main/assets/webr/bridge.js`.

```bash
git add app/src/main/assets/webr/bridge.js app/src/main/java/com/rmobile/console/data/execution/WebRController.kt
git commit -m "$(cat <<'EOF'
feat: WebR bridge — data file sync into runs + local preview

Lazily mirror a session's on-device data files into /rmobile/data/<session>,
symlink them into each run dir (so read.csv by bare name works), and add
webrPreview to render a file/object as a table. Device-verified.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Docs + full verification

🟢 suite; 🔵 device checklist.

**Files:** `CLAUDE.md`, `README.md`, `app/src/main/assets/webr/README.md`.

- [ ] **Step 1: Full JVM suite** — `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 2: Update `CLAUDE.md`** — Local engine now supports data import: `SessionDataStore` (Local = `filesDir/localdata/<session>`), lazy `/rmobile/data/<session>` VFS sync + per-run symlink, engine-routed `preview`, `dataList`/`dataChunk` bridge methods. Update the v1-boundaries paragraph (drop "no data-import"; keep local *workspace* persistence + per-project engine choice as the remaining gaps) and the `ui/data`/`ui/preview` sections (they now route per engine).

- [ ] **Step 3: Update `README.md`** — Features: Local engine can import data (read by name) and preview it; 1 GB cap with the on-device-memory caveat. Remove "local-engine data import" from "Not built yet".

- [ ] **Step 4: Update `app/src/main/assets/webr/README.md`** — the `/rmobile/data/<session>` sync/link mechanism, `dataList`/`dataChunk`, and `webrPreview`.

- [ ] **Step 5: Device verification checklist (USER runs)** — `./gradlew :app:installDebug`, Local engine:
  1. **Data screen** shows the "On-device (Local)" caption; upload a small `.csv` → appears in the list.
  2. **Read in a run:** `df <- read.csv("<name>.csv"); nrow(df); head(df)` → returns the rows.
  3. **Preview:** tap the file's "View" → renders as a table. Also `View()` a data-frame workspace object.
  4. **Delete** the file → gone from list; a subsequent run's `read.csv` errors (file not found).
  5. **Persistence:** re-upload, kill + relaunch the app, run `read.csv` again → still readable (re-synced from device storage).
  6. **Fast path:** run `1+1` with a file uploaded → still fast (sync no-op).
  7. **>200 MB warning:** picking a >200 MB file shows the confirm dialog on Local.
  8. **Remote unaffected:** switch to Remote → Data/preview still work against the backend; caption reverts.
  9. **If step 2/3 fails** with "cannot open file", `file.symlink` isn't supported → switch `linkData` to `file.copy` (Task 5 Step 2 note) and re-verify.

- [ ] **Step 6: Commit docs**

```bash
git add CLAUDE.md README.md app/src/main/assets/webr/README.md
git commit -m "$(cat <<'EOF'
docs: local-engine data import (on-device storage, VFS sync, local preview)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Finish the branch** — use superpowers:finishing-a-development-branch (push + PR into `claude/r-app-android-version-ztmyd1`).

---

## Self-Review Notes

- **Spec coverage:** storage abstraction (T1), engine routing (T2), Data UI + warning (T3), engine-routed preview (T4), WebR sync/link/preview (T5), docs + device checklist (T6). All spec sections mapped.
- **Type consistency:** `DataUpload(name, size, openStream)`, `SessionDataStore.list/save/delete`, `DataFile(name, size)`, `PreviewRequest(source, name, sessionId)`, `PreviewResponse(table, error, truncated)`, `RTable(columns, columnTypes, rows, totalRows)` — used identically across store, engine, ViewModels, and bridge JSON.
- **Device honesty:** the symlink-vs-copy unknown and the preview table-JSON shape are explicit device-verify items with concrete fallbacks; JVM-provable logic (stores, ViewModels, Remote delegation) is TDD'd in T1–T4.
- **Fake churn:** adding `preview` to `ExecutionEngine` (T4) breaks existing test fakes; T4 Step 4 updates them.
