# Per-Project Local (WebR) Isolation + Per-Project Engine Choice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each project its own isolated R environment on the Local (WebR) engine (own workspace + own package library) and let each project remember whether it runs on Local or Remote.

**Architecture:** Keep the single WebR instance; make `bridge.js` session-aware via an `ensureSession(sessionId)` choke point that swaps `globalenv` and `.libPaths()` when the active project changes, backed by per-`(kind, session)` Kotlin snapshot files. Thread the active project's resolved engine (`project.engine ?: settingsDefault`) through engine/data-store selection. Migrate the existing shared Local state into the last-open project once at startup.

**Tech Stack:** Kotlin + Jetpack Compose (app), kotlinx.serialization, JUnit4 (JVM unit tests), WebR (GNU R → WASM) in an offscreen WebView, JS↔Kotlin `@JavascriptInterface` bridge.

**Spec:** `docs/superpowers/specs/2026-07-15-per-project-local-isolation-design.md`

**Build/test environment (Windows):** Use `.\gradlew.bat`. Gradle's Kotlin-DSL needs a JDK 17–21 toolchain, not OpenJDK 25 — set `JAVA_HOME` for gradle invocations:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest
```
The Android build/APK and all `bridge.js` behavior are **device-verified** (no emulator in this environment) — bridge.js tasks below specify manual on-device checks.

---

## File structure

**New files:**
- `app/src/main/java/com/rmobile/console/data/execution/SnapshotNaming.kt` — pure: sanitize a session id and build `webr-<kind>-<session>.<ext>` snapshot filenames.
- `app/src/main/java/com/rmobile/console/data/execution/LegacyLocalStateMigration.kt` — pure: one-time rename of the legacy shared snapshots to the last-open project's per-session names.
- `app/src/main/java/com/rmobile/console/data/execution/SwapPhase.kt` — enum of Local session-swap progress phases.
- Test files mirroring each pure unit above under `app/src/test/...`.

**Modified files:**
- `data/project/Project.kt` — nullable `engine` field; `ProjectOps.newProject` gains an `engine` param.
- `data/settings/ExecutionEngineChoice.kt` — pure `resolve(projectEngine, default)`.
- `data/execution/ExecutionEngine.kt` — `reset` gains `purgePackages`; Remote/Local pass it.
- `data/execution/LocalExecutionEngine.kt` — thread `sessionId`/`purgePackages` to the controller.
- `data/execution/WebRController.kt` — per-`(kind, session)` `SnapshotStore`; `sessionId` on the six snapshot bridge methods; `onSwapProgress` bridge method + `swapProgress` flow; `sessionId`/`purgePackages` on reset & package calls; varargs `callBridge`.
- `data/ServiceLocator.kt` — `engineFor`/`dataStoreFor(choice)`, `swapProgress` passthrough, run migration in `init`.
- `assets/webr/bridge.js` — `ensureSession`, per-session snapshot/restore/lib, LRU, progress, error surfacing, `sessionId` on package/reset ops, boot no longer eager-restores.
- `ui/editor/EditorViewModel.kt` + `EditorUiState` — per-project engine resolution, engine toggle, new-project stamping, engine-correct project-delete teardown, `swapPhase` state.
- `ui/packages/PackagesViewModel.kt`, `ui/data/DataViewModel.kt`, `ui/preview/PreviewViewModel.kt` — per-project engine resolution + `swapPhase`.
- `ui/editor/EditorScreen.kt` — overflow engine toggle + swap-progress indicator.
- `ui/settings/SettingsScreen.kt` + `SettingsViewModel` copy — relabel toggle as "default for new projects".
- `CLAUDE.md`, `app/src/main/assets/webr/README.md` — docs.
- Test updates: `EditorViewModelTest`, `PackagesViewModel`/`DataViewModel`/`PreviewViewModel` tests, plus new pure tests.

---

## Task 1: `Project.engine` field + `ProjectOps.newProject(engine)`

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/project/Project.kt`
- Test: `app/src/test/java/com/rmobile/console/data/project/ProjectEngineTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/project/ProjectEngineTest.kt`:

```kotlin
package com.rmobile.console.data.project

import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `legacy project json without engine deserializes to null engine`() {
        val legacy = """
          {"id":5,"name":"Old","files":[{"name":"main.R","content":"1"}],
           "activeFileName":"main.R","entryFileName":"main.R","updatedAt":1}
        """.trimIndent()
        val p = json.decodeFromString(Project.serializer(), legacy)
        assertNull(p.engine)
    }

    @Test fun `engine round-trips through json`() {
        val p = ProjectOps.newProject(1, "P", 1, engine = ExecutionEngineChoice.REMOTE)
        val back = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), p))
        assertEquals(ExecutionEngineChoice.REMOTE, back.engine)
    }

    @Test fun `newProject stamps the passed engine`() {
        assertEquals(ExecutionEngineChoice.LOCAL, ProjectOps.newProject(1, "P", 1, ExecutionEngineChoice.LOCAL).engine)
    }

    @Test fun `newProject defaults engine to null when unspecified`() {
        assertNull(ProjectOps.newProject(1, "P", 1).engine)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectEngineTest"
```
Expected: FAIL to compile — `Project` has no `engine`, `newProject` has no `engine` param.

- [ ] **Step 3: Add the field and parameter**

In `Project.kt`, add the import and field (field is **last** with a default so existing JSON deserializes):

```kotlin
import com.rmobile.console.data.settings.ExecutionEngineChoice

@Serializable
data class Project(
    val id: Long,
    val name: String,
    val files: List<ProjectFile>,
    val activeFileName: String,
    val entryFileName: String,
    val updatedAt: Long,
    val engine: ExecutionEngineChoice? = null,
)
```

Update `ProjectOps.newProject` signature and body:

```kotlin
fun newProject(id: Long, name: String, now: Long, engine: ExecutionEngineChoice? = null): Project =
    Project(
        id = id,
        name = name,
        files = listOf(ProjectFile("main.R", SAMPLE)),
        activeFileName = "main.R",
        entryFileName = "main.R",
        updatedAt = now,
        engine = engine,
    )
```

(`ExecutionEngineChoice` is `@Serializable`? It is a plain enum — kotlinx serializes enums by name automatically, no annotation needed. Leave `ExecutionEngineChoice.kt` unchanged in this task.)

- [ ] **Step 4: Run to verify it passes**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectEngineTest"
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/data/project/Project.kt app/src/test/java/com/rmobile/console/data/project/ProjectEngineTest.kt
git commit -m "feat: add nullable per-project engine field"
```

---

## Task 2: `ExecutionEngineChoice.resolve`

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/settings/ExecutionEngineChoice.kt`
- Test: `app/src/test/java/com/rmobile/console/data/settings/ExecutionEngineChoiceTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `ExecutionEngineChoiceTest.kt` (inside the existing class):

```kotlin
    @Test fun `resolve prefers the project engine`() {
        assertEquals(
            ExecutionEngineChoice.REMOTE,
            ExecutionEngineChoice.resolve(ExecutionEngineChoice.REMOTE, ExecutionEngineChoice.LOCAL),
        )
    }

    @Test fun `resolve falls back to the default when the project engine is null`() {
        assertEquals(
            ExecutionEngineChoice.REMOTE,
            ExecutionEngineChoice.resolve(null, ExecutionEngineChoice.REMOTE),
        )
    }
```

- [ ] **Step 2: Run to verify it fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.settings.ExecutionEngineChoiceTest"
```
Expected: FAIL to compile — no `resolve`.

- [ ] **Step 3: Add `resolve` to the companion**

In `ExecutionEngineChoice.kt`, inside the existing `companion object`, add:

```kotlin
        /** The engine a project should use: its own choice, or the app-wide default when unset. */
        fun resolve(projectEngine: ExecutionEngineChoice?, default: ExecutionEngineChoice): ExecutionEngineChoice =
            projectEngine ?: default
```

- [ ] **Step 4: Run to verify it passes**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.settings.ExecutionEngineChoiceTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/data/settings/ExecutionEngineChoice.kt app/src/test/java/com/rmobile/console/data/settings/ExecutionEngineChoiceTest.kt
git commit -m "feat: ExecutionEngineChoice.resolve(projectEngine, default)"
```

---

## Task 3: `SnapshotNaming` (sanitize + per-session filename)

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/SnapshotNaming.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/SnapshotNamingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotNamingTest {
    @Test fun `sanitize keeps allowed chars and blanks fall back to default`() {
        assertEquals("proj-5", SnapshotNaming.sanitize("proj-5"))
        assertEquals("A_b-9", SnapshotNaming.sanitize("A_b-9"))
        assertEquals("default", SnapshotNaming.sanitize(""))
        assertEquals("default", SnapshotNaming.sanitize("///"))
    }

    @Test fun `sanitize strips disallowed characters`() {
        assertEquals("projX1", SnapshotNaming.sanitize("proj.X!1"))
    }

    @Test fun `fileName builds per-kind extensions`() {
        assertEquals("webr-workspace-proj-5.RData", SnapshotNaming.fileName("workspace", "proj-5"))
        assertEquals("webr-library-proj-5.tar.gz", SnapshotNaming.fileName("library", "proj-5"))
    }

    @Test fun `fileName sanitizes the session id`() {
        assertEquals("webr-workspace-projX5.RData", SnapshotNaming.fileName("workspace", "proj.X5"))
    }

    @Test fun `fileName rejects an unknown kind`() {
        assertThrows(IllegalStateException::class.java) { SnapshotNaming.fileName("bogus", "proj-5") }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.SnapshotNamingTest"
```
Expected: FAIL to compile — no `SnapshotNaming`.

- [ ] **Step 3: Implement**

Create `SnapshotNaming.kt`:

```kotlin
package com.rmobile.console.data.execution

/**
 * Pure naming for the per-session WebR snapshot files under filesDir. Kept separate
 * from [WebRController] (which is Android-bound) so it is JVM-unit-testable, and so
 * the migration and the runtime store agree byte-for-byte on filenames.
 */
object SnapshotNaming {
    /** Matches the sanitizer LocalSessionDataStore / WebRController.localDataDir use. */
    fun sanitize(sessionId: String): String =
        sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }

    fun fileName(kind: String, sessionId: String): String {
        val ext = when (kind) {
            "workspace" -> "RData"
            "library" -> "tar.gz"
            else -> error("Unknown snapshot kind: $kind")
        }
        return "webr-$kind-${sanitize(sessionId)}.$ext"
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.SnapshotNamingTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/data/execution/SnapshotNaming.kt app/src/test/java/com/rmobile/console/data/execution/SnapshotNamingTest.kt
git commit -m "feat: SnapshotNaming for per-session snapshot filenames"
```

---

## Task 4: `LegacyLocalStateMigration`

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/LegacyLocalStateMigration.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/LegacyLocalStateMigrationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.data.execution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyLocalStateMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray) = File(tmp.root, name).writeBytes(bytes)

    @Test fun `renames legacy workspace and library to the session names`() {
        write("webr-workspace.RData", byteArrayOf(1, 2, 3))
        write("webr-library.tar.gz", byteArrayOf(4, 5))

        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")

        assertFalse(File(tmp.root, "webr-workspace.RData").exists())
        assertFalse(File(tmp.root, "webr-library.tar.gz").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(tmp.root, "webr-workspace-proj-7.RData").readBytes())
        assertArrayEquals(byteArrayOf(4, 5), File(tmp.root, "webr-library-proj-7.tar.gz").readBytes())
    }

    @Test fun `no-op when there is no last-open session`() {
        write("webr-workspace.RData", byteArrayOf(1))
        LegacyLocalStateMigration.migrate(tmp.root, null)
        assertTrue(File(tmp.root, "webr-workspace.RData").exists())
    }

    @Test fun `does not overwrite an existing per-session file`() {
        write("webr-workspace.RData", byteArrayOf(9))
        write("webr-workspace-proj-7.RData", byteArrayOf(1, 1)) // already migrated / newer
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")
        // Existing target is preserved; legacy left as-is (not clobbered, not deleted).
        assertArrayEquals(byteArrayOf(1, 1), File(tmp.root, "webr-workspace-proj-7.RData").readBytes())
        assertTrue(File(tmp.root, "webr-workspace.RData").exists())
    }

    @Test fun `idempotent second run is a no-op`() {
        write("webr-library.tar.gz", byteArrayOf(4))
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7") // legacy already gone
        assertArrayEquals(byteArrayOf(4), File(tmp.root, "webr-library-proj-7.tar.gz").readBytes())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.LegacyLocalStateMigrationTest"
```
Expected: FAIL to compile — no `LegacyLocalStateMigration`.

- [ ] **Step 3: Implement**

```kotlin
package com.rmobile.console.data.execution

import java.io.File

/**
 * One-time adoption of the pre-per-project shared Local snapshots into the last-open
 * project's per-session snapshot files. Pure file ops so it is JVM-unit-testable.
 * Best-effort: never overwrites an existing per-session file; the rename is its own
 * idempotency marker (once moved, the legacy name is gone).
 */
object LegacyLocalStateMigration {
    private val LEGACY = listOf(
        "workspace" to "webr-workspace.RData",
        "library" to "webr-library.tar.gz",
    )

    fun migrate(filesDir: File, lastOpenSessionId: String?) {
        if (lastOpenSessionId.isNullOrBlank()) return
        for ((kind, legacyName) in LEGACY) {
            val src = File(filesDir, legacyName)
            val dst = File(filesDir, SnapshotNaming.fileName(kind, lastOpenSessionId))
            if (src.exists() && !dst.exists()) {
                runCatching { src.renameTo(dst) }
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.LegacyLocalStateMigrationTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/data/execution/LegacyLocalStateMigration.kt app/src/test/java/com/rmobile/console/data/execution/LegacyLocalStateMigrationTest.kt
git commit -m "feat: LegacyLocalStateMigration adopts shared Local state into last-open project"
```

---

## Task 5: `SwapPhase` enum + `WebRController` per-session stores, session-scoped bridge, progress flow

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/SwapPhase.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`

This task has no JVM test (WebRController is Android-bound); correctness is compile + Task 3's naming test + on-device Tasks 7–8. Keep it a faithful mechanical refactor.

- [ ] **Step 1: Create `SwapPhase.kt`**

```kotlin
package com.rmobile.console.data.execution

/** Progress phases for a Local project (session) swap, surfaced to the UI. */
enum class SwapPhase {
    IDLE,
    SAVING_WORKSPACE,
    LOADING_WORKSPACE,
    RESTORING_LIBRARY,
    RESTORE_FAILED;

    companion object {
        fun fromName(name: String): SwapPhase =
            entries.firstOrNull { it.name == name } ?: IDLE
    }
}
```

- [ ] **Step 2: Rework `WebRController` stores to be per-`(kind, session)`**

Replace the fixed two-store block (currently around lines 77–91: `libraryStore`, `workspaceStore`, `snapshotStore(kind)`) with a keyed cache using `SnapshotNaming`:

```kotlin
    // Persisted snapshots under filesDir, streamed to/from the WebR VFS in base64
    // chunks. Now per (kind, session): each project's workspace + library is its own
    // file, so switching projects swaps isolated state. Still avoids IDBFS FS.mount.
    private val stores = ConcurrentHashMap<String, SnapshotStore>()
    private fun snapshotStore(kind: String, sessionId: String): SnapshotStore {
        val name = SnapshotNaming.fileName(kind, sessionId) // throws on unknown kind
        return stores.getOrPut(name) { SnapshotStore(java.io.File(appContext.filesDir, name)) }
    }
```

- [ ] **Step 3: Accept an injected swap-phase sink; update the `Bridge` snapshot methods to take `sessionId`**

Add imports at the top of the file:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Take the sink as a constructor parameter so the progress flow can live in `ServiceLocator` and be read **without constructing the controller** (so a Remote-only user never boots WebR just to observe progress). Change the class header:

```kotlin
class WebRController(
    context: Context,
    private val swapPhaseSink: MutableStateFlow<SwapPhase> = MutableStateFlow(SwapPhase.IDLE),
) {
```

Expose a read-only view (near the other fields):

```kotlin
    /** Emits the current Local session-swap phase; IDLE when no swap is in progress. */
    val swapProgress: StateFlow<SwapPhase> = swapPhaseSink.asStateFlow()
```

Replace the six `Bridge` snapshot methods so each takes `kind` **and** `sessionId`, and add `onSwapProgress`:

```kotlin
        @JavascriptInterface fun snapshotSize(kind: String, sessionId: String): Int =
            try { snapshotStore(kind, sessionId).size() } catch (e: Exception) { 0 }

        @JavascriptInterface fun snapshotRead(kind: String, sessionId: String, offset: Int, length: Int): String = try {
            val buf = snapshotStore(kind, sessionId).read(offset, length)
            if (buf.isEmpty()) "" else android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        @JavascriptInterface fun snapshotBegin(kind: String, sessionId: String) {
            try { snapshotStore(kind, sessionId).begin() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotAppend(kind: String, sessionId: String, b64: String) {
            try { snapshotStore(kind, sessionId).append(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)) } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotCommit(kind: String, sessionId: String) {
            try { snapshotStore(kind, sessionId).commit() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotDelete(kind: String, sessionId: String) {
            try { snapshotStore(kind, sessionId).delete() } catch (e: Exception) {}
        }

        @JavascriptInterface fun onSwapProgress(phase: String) {
            swapPhaseSink.value = SwapPhase.fromName(phase)
        }
```

- [ ] **Step 4: Add varargs `callBridge` and session/purge params to the public methods**

Replace the private `callBridge` with a varargs form, and update the public methods:

```kotlin
    /** Runs a full ExecuteRequest (as JSON) and returns the bridge's ExecuteResponse JSON. */
    suspend fun execute(requestJson: String): String =
        callBridge("window.webrRun", org.json.JSONObject.quote(requestJson))

    /** Clears the given session's workspace (and its package library when [purgePackages]). */
    suspend fun reset(sessionId: String, purgePackages: Boolean): String =
        callBridge("window.webrReset", org.json.JSONObject.quote(sessionId), purgePackages.toString())

    suspend fun installPackage(pkg: String, sessionId: String): String =
        callBridge("window.webrInstall", org.json.JSONObject.quote(pkg), org.json.JSONObject.quote(sessionId))

    suspend fun uninstallPackage(pkg: String, sessionId: String): String =
        callBridge("window.webrUninstall", org.json.JSONObject.quote(pkg), org.json.JSONObject.quote(sessionId))

    suspend fun listPackages(sessionId: String): String =
        callBridge("window.webrListPackages", org.json.JSONObject.quote(sessionId))

    suspend fun preview(requestJson: String): String =
        callBridge("window.webrPreview", org.json.JSONObject.quote(requestJson))

    /**
     * Invokes a bridge function that takes the result id as its first argument and
     * [jsArgs] (already JS-literal-encoded) as the following arguments, and awaits the
     * JSON it posts back.
     */
    private suspend fun callBridge(fn: String, vararg jsArgs: String): String {
        withContext(Dispatchers.Main) { webView }
        ready.await()
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val args = (listOf(id.toString()) + jsArgs).joinToString(", ")
        val call = "$fn($args)"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(call, null) }
        return deferred.await()
    }
```

Note: `purgePackages.toString()` yields the bare literal `true`/`false` (valid JS), so `webrReset(id, "proj-5", true)`.

- [ ] **Step 5: Compile**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:compileDebugKotlin
```
Expected: FAIL — `LocalExecutionEngine` still calls `controller.reset()`, `installPackage(pkg)`, etc. with the old signatures. That is fixed in Task 6; compiling the whole app happens there. (If you want a green checkpoint now, proceed to Task 6 before committing, or commit this + Task 6 together.) For subagent-driven flow, **commit at the end of Task 6**; leave this task's files staged:

```powershell
git add app/src/main/java/com/rmobile/console/data/execution/SwapPhase.kt app/src/main/java/com/rmobile/console/data/execution/WebRController.kt
```

---

## Task 6: `LocalExecutionEngine` threading + `ServiceLocator` selection + migration

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/ServiceLocator.kt`

- [ ] **Step 1: `ExecutionEngine.reset` gains `purgePackages`; Remote passes it**

In `ExecutionEngine.kt`, change the interface method and the Remote impl:

```kotlin
    /** Clears the session's workspace (and its package library when [purgePackages]). */
    suspend fun reset(sessionId: String, purgePackages: Boolean = false): Result<Unit>
```

```kotlin
    override suspend fun reset(sessionId: String, purgePackages: Boolean): Result<Unit> =
        repository.reset(sessionId, purgePackages).map { }
```

(`RExecutionRepository.reset(sessionId, purgePackages)` already exists — `EditorViewModel.deleteProject` calls it with `purgePackages = true`.)

- [ ] **Step 2: `LocalExecutionEngine` threads session/purge to the controller**

```kotlin
    override suspend fun reset(sessionId: String, purgePackages: Boolean): Result<Unit> = runCatching {
        controller.reset(sessionId, purgePackages); Unit
    }

    override suspend fun listPackages(sessionId: String): Result<PackagesResponse> = runCatching {
        json.decodeFromString<PackagesResponse>(controller.listPackages(sessionId))
    }

    override suspend fun install(request: InstallRequest): Result<InstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName, session))
    }

    override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName, session))
    }
```

(`execute`/`preview` are unchanged — the session travels inside the request JSON, and `bridge.js` reads it.)

- [ ] **Step 3: `ServiceLocator` — choice-taking selectors, swapProgress, run migration**

Replace `currentExecutionEngine`/`currentSessionDataStore` with choice-taking forms, expose `swapProgress`, keep a reference to the `WebRController`, and run the migration in `init`:

```kotlin
object ServiceLocator {
    lateinit var settingsStore: SettingsStore
        private set
    private lateinit var appContext: Context

    // The swap-phase flow lives here (not on the controller) so reading it never
    // forces the WebR WebView to boot — a Remote-only user pays nothing.
    private val swapPhaseSink = MutableStateFlow(SwapPhase.IDLE)
    /** Local session-swap progress, for a "Switching project…" indicator. */
    val swapProgress: StateFlow<SwapPhase> = swapPhaseSink.asStateFlow()

    private val webRController: WebRController by lazy { WebRController(appContext, swapPhaseSink) }

    private val remoteEngine: ExecutionEngine by lazy {
        RemoteExecutionEngine(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localEngine: ExecutionEngine by lazy { LocalExecutionEngine(webRController) }

    private val remoteDataStore: SessionDataStore by lazy {
        RemoteSessionDataStore(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localDataStore: SessionDataStore by lazy {
        LocalSessionDataStore(File(appContext.filesDir, "localdata"))
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsStore = SettingsStore(context)
        NetworkModule.updateConfig(settingsStore.baseUrl, settingsStore.apiKey)
        // One-time: adopt the pre-per-project shared Local state into the last-open project.
        com.rmobile.console.data.execution.LegacyLocalStateMigration.migrate(
            appContext.filesDir,
            settingsStore.loadLastOpenProjectId()?.let { "proj-$it" },
        )
    }

    fun engineFor(choice: ExecutionEngineChoice): ExecutionEngine =
        when (choice) {
            ExecutionEngineChoice.LOCAL -> localEngine
            ExecutionEngineChoice.REMOTE -> remoteEngine
        }

    fun dataStoreFor(choice: ExecutionEngineChoice): SessionDataStore =
        when (choice) {
            ExecutionEngineChoice.LOCAL -> localDataStore
            ExecutionEngineChoice.REMOTE -> remoteDataStore
        }
}
```

Add imports (if not already present):

```kotlin
import com.rmobile.console.data.execution.WebRController
import com.rmobile.console.data.execution.SwapPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Note: `swapProgress` reads the `swapPhaseSink` directly, so a ViewModel observing it (Task 9's editor `init`) does **not** force the `webRController`/WebView to boot. WebR still boots lazily on the first Local op, as today. The controller and `ServiceLocator` share the same sink, so bridge `onSwapProgress` updates propagate to observers.

- [ ] **Step 4: Compile the whole app**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:compileDebugKotlin
```
Expected: FAIL — the four ViewModels still call `ServiceLocator.currentExecutionEngine()` / `currentSessionDataStore()` and `engineProvider()` with no arg. Those are fixed in Tasks 9–10. For a green checkpoint, temporarily nothing else references the removed methods except the ViewModels. Since subagent-driven reviews per task, **commit Tasks 5+6 together now** (they form one compilable-with-callers unit only after Tasks 9–10). To keep commits coherent, commit the engine/controller/servicelocator layer now and expect ViewModels to be updated next:

Temporarily keep the app compiling by updating the four call sites minimally is done in Tasks 9–10; if your workflow requires each commit to compile, fold Tasks 9–10 in before this commit. Otherwise commit the layer:

```powershell
git add app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt app/src/main/java/com/rmobile/console/data/ServiceLocator.kt app/src/main/java/com/rmobile/console/data/execution/SwapPhase.kt app/src/main/java/com/rmobile/console/data/execution/WebRController.kt
git commit -m "feat: session-scoped Local engine plumbing (controller, engine, ServiceLocator, migration)"
```

> **Implementer note:** Because Tasks 5–6 remove the no-arg `currentExecutionEngine()`/`currentSessionDataStore()` that the ViewModels still use, the app will not fully compile until Tasks 9–10 land. Do Tasks 9 and 10 immediately after 6 and run the full `:app:testDebugUnitTest` once at the end of Task 10 as the compile/test gate for this whole layer.

---

## Task 7: `bridge.js` — session-aware core (`ensureSession`, per-session snapshot/lib, threading)

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js`

Device-verified (no JVM test). This is the heart of the feature. Make the changes below, then run the on-device checks at the end of Task 8 (they cover Tasks 7 and 8 together).

- [ ] **Step 1: Replace the module-level snapshot constants/state**

Replace the current workspace/library constant block (lines ~11–22) with session-aware state:

```javascript
const USER_LIB_ROOT = '/rmobile/library';           // per-session libs live at <root>/<session>
const SNAP_TARBALL = '/rmobile/lib.tar';             // scratch path for a lib tar
const SNAP_CHUNK = 512 * 1024;
const WS_RDATA = '/rmobile/workspace.RData';         // scratch path for a workspace blob
const WS_MAX_BYTES = 200 * 1024 * 1024;
const MAX_RESIDENT_LIBS = 3;                         // LRU cap on lib dirs kept in the VFS

let currentSession = null;                           // the session whose state is live
let pendingWorkspaceSnapshot = null;                 // in-flight background save.image
let runGeneration = 0;
const residentLibs = [];                             // session ids with a restored lib dir (LRU order)
let lastSwapWarning = '';                            // surfaced into the next run's stderr
let lastWorkspaceInfo = '';
let lastSnapshotInfo = '';
let lastRestoreInfo = '';
```

- [ ] **Step 2: Thread `sessionId` through the stream helpers**

Replace `streamBytesOut`/`streamOut`/`streamIn` so the snapshot bridge calls carry the session:

```javascript
function streamBytesOut(kind, sessionId, bytes) {
  AndroidBridge.snapshotBegin(kind, sessionId);
  for (let i = 0; i < bytes.length; i += SNAP_CHUNK) {
    AndroidBridge.snapshotAppend(kind, sessionId, bytesToB64(bytes.subarray(i, i + SNAP_CHUNK)));
  }
  AndroidBridge.snapshotCommit(kind, sessionId);
}

async function streamOut(kind, sessionId, vfsPath) {
  const bytes = await webR.FS.readFile(vfsPath);
  streamBytesOut(kind, sessionId, bytes);
  return bytes.length;
}

async function streamIn(kind, sessionId, vfsPath) {
  const size = AndroidBridge.snapshotSize(kind, sessionId);
  if (!size) return 0;
  const parts = [];
  let total = 0;
  for (let off = 0; off < size; off += SNAP_CHUNK) {
    const b64 = AndroidBridge.snapshotRead(kind, sessionId, off, SNAP_CHUNK);
    if (!b64) break;
    const part = b64ToBytes(b64);
    parts.push(part);
    total += part.length;
  }
  const all = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { all.set(p, o); o += p.length; }
  await webR.FS.writeFile(vfsPath, all);
  return total;
}
```

- [ ] **Step 3: Make the library ops session-scoped**

Replace `ensureUserLib`, `snapshotLibrary`, `restoreLibrary` with session-aware versions and add `libDir`/`ensureLibResident`:

```javascript
function libDir(sessionId) { return `${USER_LIB_ROOT}/${sessionId}`; }

// Create the current session's lib dir and put it first on .libPaths().
async function ensureUserLib(sessionId) {
  const dir = libDir(sessionId);
  await webR.evalRVoid(
    `dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE); ` +
    `.libPaths(unique(c(${JSON.stringify(dir)}, .libPaths())))`
  );
}

// Restore a session's lib dir from its Kotlin tarball snapshot into the VFS if this
// process hasn't already. Idempotent; tracks LRU residency.
async function ensureLibResident(sessionId) {
  if (residentLibs.includes(sessionId)) { touchResident(sessionId); return; }
  const dir = libDir(sessionId);
  try {
    const total = await streamIn('library', sessionId, SNAP_TARBALL);
    await webR.evalRVoid(`dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE)`);
    if (total) {
      // tar="internal" is REQUIRED under Emscripten (default untar shells out via system()).
      await webR.evalRVoid(`utils::untar(${JSON.stringify(SNAP_TARBALL)}, exdir = ${JSON.stringify(dir)}, tar = "internal")`);
      try { await webR.evalRVoid(`unlink(${JSON.stringify(SNAP_TARBALL)})`); } catch (e) {}
    }
    lastRestoreInfo = `lib ${sessionId}: restored ${total}B`;
  } catch (e) { lastRestoreInfo = `lib ${sessionId}: restore failed: ` + String(e); }
  residentLibs.push(sessionId);
  touchResident(sessionId);
}

// Tar the current session's lib and hand the bytes to Kotlin under its snapshot.
async function snapshotLibrary(sessionId) {
  try {
    const dir = libDir(sessionId);
    await webR.evalRVoid(
      `local({ owd <- getwd(); on.exit(setwd(owd)); setwd(${JSON.stringify(dir)}); ` +
      `utils::tar(${JSON.stringify(SNAP_TARBALL)}, ".", compression = "none") })`
    );
    const n = await streamOut('library', sessionId, SNAP_TARBALL);
    lastSnapshotInfo = `lib ${sessionId}: tar ${n}B`;
    try { await webR.evalRVoid(`unlink(${JSON.stringify(SNAP_TARBALL)})`); } catch (e) {}
  } catch (e) { lastSnapshotInfo = `lib ${sessionId}: snapshot failed: ` + String(e); }
}
```

- [ ] **Step 4: Make the workspace ops session-scoped**

Replace `snapshotWorkspace`/`restoreWorkspace`:

```javascript
async function snapshotWorkspace(sessionId) {
  try {
    const nR = await webR.evalR('length(ls(globalenv()))');
    const n = (await nR.toArray())[0];
    webR.destroy(nR);
    if (!n) { AndroidBridge.snapshotDelete('workspace', sessionId); lastWorkspaceInfo = `${sessionId}: empty → cleared`; return; }
    await webR.evalRVoid(`save.image(${JSON.stringify(WS_RDATA)})`);
    const bytes = await webR.FS.readFile(WS_RDATA);
    if (bytes.length > WS_MAX_BYTES) { lastWorkspaceInfo = `${sessionId}: too large (${bytes.length}B) → not persisted`; return; }
    streamBytesOut('workspace', sessionId, bytes);
    lastWorkspaceInfo = `${sessionId}: saved ${bytes.length}B`;
    try { await webR.evalRVoid(`unlink(${JSON.stringify(WS_RDATA)})`); } catch (e) {}
  } catch (e) { lastWorkspaceInfo = `${sessionId}: snapshot failed: ` + String(e); }
}

// Returns true on a clean restore (or genuinely empty), false if a restore was
// attempted but threw — the caller records a swap warning in that case.
async function restoreWorkspace(sessionId) {
  try {
    const total = await streamIn('workspace', sessionId, WS_RDATA);
    if (!total) { lastWorkspaceInfo = `${sessionId}: no snapshot`; return true; }
    await webR.evalRVoid(`load(${JSON.stringify(WS_RDATA)}, envir = globalenv())`);
    try { await webR.evalRVoid(`unlink(${JSON.stringify(WS_RDATA)})`); } catch (e) {}
    lastWorkspaceInfo = `${sessionId}: restored ${total}B`;
    return true;
  } catch (e) { lastWorkspaceInfo = `${sessionId}: restore failed: ` + String(e); return false; }
}
```

- [ ] **Step 5: Add `ensureSession` (the swap choke point) — progress/LRU wired in Task 8**

Add above `runOnce`:

```javascript
// Make sessionId the live session: swap the workspace out/in and point .libPaths at
// its lib. A no-op when it is already current. Best-effort throughout; a failed
// restore records lastSwapWarning (surfaced into the run's stderr) but never throws.
async function ensureSession(sessionId) {
  if (sessionId === currentSession) return;
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  if (currentSession != null) {
    await snapshotWorkspace(currentSession);
    try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
  }
  const ok = await restoreWorkspace(sessionId);
  lastSwapWarning = ok ? '' : "Couldn't load this project's saved workspace; starting empty.";
  await ensureLibResident(sessionId);
  await ensureUserLib(sessionId);
  currentSession = sessionId;
  // touchResident + eviction added in Task 8.
}

function touchResident(sessionId) {
  const i = residentLibs.indexOf(sessionId);
  if (i >= 0) residentLibs.splice(i, 1);
  residentLibs.push(sessionId);
}
```

- [ ] **Step 6: Call `ensureSession` from every session-scoped op; drop eager boot restore**

In `boot()`, remove the eager `restoreLibrary()` / `restoreWorkspace()` calls (they were global; restore is now lazy per session). New `boot`:

```javascript
async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));
```

In `runOnce`, after `const myGen = ++runGeneration;` and awaiting `pendingWorkspaceSnapshot`, add the session swap and change the post-run snapshot to be session-scoped. Replace the top of `runOnce` and the snapshot block:

```javascript
async function runOnce(req) {
  const myGen = ++runGeneration;
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId);
  await resetRunDir();
  ...
```

And where it currently prepends skipped-data notes to stderr, also prepend the swap warning. Change the `stderr` line so both notes apply — replace:

```javascript
    const stderr = withSkippedNote(cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n'), skippedData);
```
with:
```javascript
    let stderr = withSkippedNote(cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n'), skippedData);
    if (lastSwapWarning) { stderr = stderr ? `${lastSwapWarning}\n${stderr}` : lastSwapWarning; lastSwapWarning = ''; }
```
Also prepend `lastSwapWarning` in the early `captureR` catch branch (the one that returns on a top-level R error): replace its `const msg = withSkippedNote(...)` with:
```javascript
      let msg = withSkippedNote(String((e && e.message) || e), skippedData);
      if (lastSwapWarning) { msg = `${lastSwapWarning}\n${msg}`; lastSwapWarning = ''; }
```

Change the fire-and-forget post-run snapshot to target this session:

```javascript
    if (myGen === runGeneration) {
      const snap = snapshotWorkspace(sessionId);
      pendingWorkspaceSnapshot = snap;
      snap.finally(() => { if (pendingWorkspaceSnapshot === snap) pendingWorkspaceSnapshot = null; });
    }
```

- [ ] **Step 7: Session-scope the package ops, preview, and reset**

`webrInstall`/`webrUninstall`/`webrListPackages` now take `sessionId` and `ensureSession` first; they use the current session's lib and snapshot to it:

```javascript
window.webrInstall = async (id, pkg, sessionId) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ installed: false, error: 'WebR not ready', stdout: '', stderr: '', timedOut: false, systemRequirements: null })); return; }
  await ensureSession(sessionId);
  const shelter = await new webR.Shelter();
  try {
    const cap = await shelter.captureR(
      `webr::install(${JSON.stringify(pkg)}, repos = c(${JSON.stringify(LOCAL_REPO_URL)}, "https://repo.r-wasm.org"))`,
      { withAutoprint: false, captureStreams: true }
    );
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    const stderr = cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n');
    const okR = await webR.evalR(`requireNamespace(${JSON.stringify(pkg)}, quietly = TRUE)`);
    const installed = (await okR.toArray())[0] === true;
    webR.destroy(okR);
    if (installed) await snapshotLibrary(sessionId);
    const detail = (stderr || stdout || '').split('\n').filter((l) => l.trim()).slice(-6).join('\n');
    AndroidBridge.onResult(id, JSON.stringify({
      installed, stdout, stderr,
      error: installed ? null : (`Could not install ${pkg}.` + (detail ? `\n${detail}` : ' No details were captured.')),
      timedOut: false, systemRequirements: null,
    }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ installed: false, stdout: '', stderr: String(e), error: String(e), timedOut: false, systemRequirements: null }));
  } finally { shelter.purge(); }
};

window.webrUninstall = async (id, pkg, sessionId) => {
  try {
    await ensureSession(sessionId);
    const lib = libDir(sessionId);
    try { await webR.FS.unmount(`${lib}/${pkg}`); } catch (e) { /* not a mount */ }
    const goneR = await webR.evalR(
      `local({\n` +
      `  p <- ${JSON.stringify(pkg)}; lib <- ${JSON.stringify(lib)}; dir <- file.path(lib, p);\n` +
      `  if (dir.exists(dir)) {\n` +
      `    ff <- tryCatch(list.files(dir, recursive = TRUE, all.files = TRUE, full.names = TRUE, include.dirs = TRUE), error = function(e) character(0));\n` +
      `    try(Sys.chmod(c(dir, ff), mode = '0777', use_umask = FALSE), silent = TRUE);\n` +
      `    unlink(dir, recursive = TRUE, force = TRUE)\n` +
      `  }\n` +
      `  !(p %in% rownames(installed.packages(lib.loc = lib, noCache = TRUE)))\n` +
      `})`
    );
    const removed = (await goneR.toArray())[0] === true;
    webR.destroy(goneR);
    if (removed) {
      try {
        await webR.evalRVoid(
          `local({ p <- ${JSON.stringify(pkg)}; ` +
          `try({ if (paste0("package:", p) %in% search()) detach(paste0("package:", p), character.only = TRUE, unload = TRUE) }, silent = TRUE); ` +
          `try({ if (p %in% loadedNamespaces()) unloadNamespace(p) }, silent = TRUE) })`
        );
      } catch (e) {}
      await snapshotLibrary(sessionId);
    }
    AndroidBridge.onResult(id, JSON.stringify({ removed, error: removed ? null : `${pkg} was not removed.` }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ removed: false, error: String(e) }));
  }
};

window.webrListPackages = async (id, sessionId) => {
  try {
    await ensureSession(sessionId);
    const r = await webR.evalR(`rownames(installed.packages(lib.loc = ${JSON.stringify(libDir(sessionId))}))`);
    const packages = await r.toArray();
    webR.destroy(r);
    AndroidBridge.onResult(id, JSON.stringify({ packages }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ packages: [] }));
  }
};
```

In `webrPreview`, add `await ensureSession(req.sessionId || 'default');` immediately after `const req = JSON.parse(requestJson);`. (Object preview reads `globalenv()`, which must be the requested session's.)

Replace `webrReset` to take `sessionId` + `purge`:

```javascript
window.webrReset = async (id, sessionId, purge) => {
  try {
    if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
    // Clear the live globalenv only if this is the live session.
    if (sessionId === currentSession) {
      try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
    }
    AndroidBridge.snapshotDelete('workspace', sessionId);
    if (purge) {
      AndroidBridge.snapshotDelete('library', sessionId);
      const lib = libDir(sessionId);
      try { await webR.evalRVoid(`if (dir.exists(${JSON.stringify(lib)})) unlink(${JSON.stringify(lib)}, recursive = TRUE, force = TRUE)`); } catch (e) {}
      const i = residentLibs.indexOf(sessionId);
      if (i >= 0) residentLibs.splice(i, 1);
    }
    AndroidBridge.onResult(id, JSON.stringify({ ok: true }));
  } catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};
```

- [ ] **Step 8: Stage (verify on-device at end of Task 8)**

```powershell
git add app/src/main/assets/webr/bridge.js
```
Commit together with Task 8.

---

## Task 8: `bridge.js` — LRU eviction + swap progress emission

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js`

- [ ] **Step 1: Emit progress phases from `ensureSession`**

Wrap the swap body with progress calls and a `finally` that resets to IDLE. Replace `ensureSession` from Task 7 with:

```javascript
async function ensureSession(sessionId) {
  if (sessionId === currentSession) return;
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  try {
    if (currentSession != null) {
      AndroidBridge.onSwapProgress('SAVING_WORKSPACE');
      await snapshotWorkspace(currentSession);
      try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
    }
    AndroidBridge.onSwapProgress('LOADING_WORKSPACE');
    const ok = await restoreWorkspace(sessionId);
    if (!ok) { lastSwapWarning = "Couldn't load this project's saved workspace; starting empty."; AndroidBridge.onSwapProgress('RESTORE_FAILED'); }
    else { lastSwapWarning = ''; }
    AndroidBridge.onSwapProgress('RESTORING_LIBRARY');
    await ensureLibResident(sessionId);
    await ensureUserLib(sessionId);
    currentSession = sessionId;
    evictLibsIfOverCap(sessionId);
  } finally {
    AndroidBridge.onSwapProgress('IDLE');
  }
}
```

- [ ] **Step 2: Add LRU eviction**

Add below `touchResident`:

```javascript
// Bound in-process VFS growth: keep at most MAX_RESIDENT_LIBS session lib dirs
// restored. Evict the least-recently-used (never the current). An evicted dir
// re-restores from its Kotlin tarball on next visit. Best-effort.
async function evictLibsIfOverCap(keepSessionId) {
  while (residentLibs.length > MAX_RESIDENT_LIBS) {
    const victim = residentLibs.find((s) => s !== keepSessionId);
    if (!victim) break;
    const i = residentLibs.indexOf(victim);
    residentLibs.splice(i, 1);
    const dir = libDir(victim);
    try {
      // Unmount any freshly-installed (mounted) package images before unlinking.
      const pkgsR = await webR.evalR(`if (dir.exists(${JSON.stringify(dir)})) list.files(${JSON.stringify(dir)}) else character(0)`);
      const pkgs = await pkgsR.toArray(); webR.destroy(pkgsR);
      for (const p of pkgs) { try { await webR.FS.unmount(`${dir}/${p}`); } catch (e) {} }
      await webR.evalRVoid(`unlink(${JSON.stringify(dir)}, recursive = TRUE, force = TRUE)`);
    } catch (e) { /* leave it resident on failure — memory, not correctness */ }
  }
}
```

Note: `evictLibsIfOverCap` is `async`; call it with `await` in `ensureSession` (Step 1 already does).

- [ ] **Step 3: On-device verification (covers Tasks 7 + 8)**

Build/install on a device:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:installDebug
```
Run the matrix (Local engine unless noted):
1. **Workspace isolation:** Project A → `x <- 42; print(x)`. New project B → `print(exists("x"))` → `FALSE`. Back to A → `print(x)` → `42`. Kill/relaunch, repeat both → still isolated.
2. **Package isolation:** In A install a small package (e.g. from the bundled repo); it appears in A's Packages list. Switch to B → not listed. Back to A → listed.
3. **Reset:** In A, reset session → A empty; B unaffected.
4. **LRU:** With `MAX_RESIDENT_LIBS = 3`, visit 4 different projects that each have an installed package, run in each; no crash; returning to the first re-lists its packages correctly (it re-untars).
5. **Progress + error:** Switch into a project with a large workspace/library — a "Switching project…" indicator appears (Task 11 wires the UI; here confirm via `logcat`/no hang). Corrupt a workspace snapshot file (adb push garbage over `webr-workspace-proj-<id>.RData`), relaunch, run that project → stderr shows "Couldn't load this project's saved workspace; starting empty." and the run still completes.

- [ ] **Step 4: Commit Tasks 7 + 8**

```powershell
git add app/src/main/assets/webr/bridge.js
git commit -m "feat: session-aware Local WebR (per-project workspace+library, LRU, progress, error surfacing)"
```

---

## Task 9: `EditorViewModel` per-project engine + toggle + engine-correct delete + swap phase

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `EditorViewModelTest.kt` (follow the file's existing fake/setup patterns; a `FakeEngine : ExecutionEngine` and an injected `engineProvider` already exist there — adapt them to the new `(ExecutionEngineChoice) -> ExecutionEngine` shape):

```kotlin
    @Test fun `runCode resolves the active project engine`() {
        val local = RecordingEngine(); val remote = RecordingEngine()
        val vm = newVm(engineProvider = { choice ->
            if (choice == ExecutionEngineChoice.LOCAL) local else remote
        }, defaultEngine = { ExecutionEngineChoice.LOCAL })
        // active project has engine = REMOTE
        vm.setProjectEngine(ExecutionEngineChoice.REMOTE)
        vm.runCode()
        assertTrue(remote.executed); assertFalse(local.executed)
    }

    @Test fun `new project is stamped with the settings default engine`() {
        val vm = newVm(defaultEngine = { ExecutionEngineChoice.REMOTE })
        vm.newProject("Fresh")
        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.project.engine)
    }

    @Test fun `setProjectEngine persists the choice on the active project`() {
        val vm = newVm(defaultEngine = { ExecutionEngineChoice.LOCAL })
        vm.setProjectEngine(ExecutionEngineChoice.REMOTE)
        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.project.engine)
        assertEquals(ExecutionEngineChoice.REMOTE, projectStore.loadProjects().first { it.id == vm.uiState.value.project.id }.engine)
    }

    @Test fun `deleteProject resets via the victim project's engine`() {
        val local = RecordingEngine(); val remote = RecordingEngine()
        val vm = newVm(engineProvider = { c -> if (c == ExecutionEngineChoice.LOCAL) local else remote },
            defaultEngine = { ExecutionEngineChoice.LOCAL })
        vm.newProject("ToDelete"); vm.setProjectEngine(ExecutionEngineChoice.REMOTE)
        val id = vm.uiState.value.project.id
        vm.deleteProject(id)
        assertTrue(remote.resetSessions.contains("proj-$id"))
        assertTrue(remote.resetPurge)
    }
```

Where `RecordingEngine` is a test `ExecutionEngine` capturing `executed`, `resetSessions`, `resetPurge` (add it to the test file if not present). `newVm(...)` is the existing helper — extend it to accept `engineProvider` and `defaultEngine`.

- [ ] **Step 2: Run to verify failure**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"
```
Expected: FAIL to compile — no `setProjectEngine`, `engineProvider` shape mismatch.

- [ ] **Step 3: Update `EditorUiState`**

Add a swap-phase field (import `SwapPhase`):

```kotlin
    val swapPhase: SwapPhase = SwapPhase.IDLE,
```

- [ ] **Step 4: Update `EditorViewModel`**

Change constructor params:

```kotlin
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val engineProvider: (ExecutionEngineChoice) -> ExecutionEngine = { ServiceLocator.engineFor(it) },
    private val swapProgress: StateFlow<SwapPhase> = ServiceLocator.swapProgress,
```

Add a private resolver and use it in `runCode`/`resetSession`:

```kotlin
    private fun engineFor(project: Project): ExecutionEngine =
        engineProvider(ExecutionEngineChoice.resolve(project.engine, defaultEngine()))
```

In `runCode`, replace `engineProvider().execute(request)` with `engineFor(project).execute(request)`.
In `resetSession`, replace `engineProvider().reset(session)` with `engineFor(_uiState.value.project).reset(session)`.

Replace `newProject` body's creation line to stamp the default engine:

```kotlin
        val created = ProjectOps.newProject(now(), name.trim().ifEmpty { "Untitled" }, now(), engine = defaultEngine())
```

Replace the two `ProjectOps.newProject(now(), "Untitled", now())` fallbacks (init and deleteProject) with the stamped form `ProjectOps.newProject(now(), "Untitled", now(), engine = defaultEngine())`.

Replace `deleteProject`'s reset line (currently `repository.reset(ProjectSession.of(victim), purgePackages = true)`) with an engine-correct reset:

```kotlin
        _uiState.value.projects.firstOrNull { it.id == id }?.let { victim ->
            viewModelScope.launch { engineFor(victim).reset(ProjectSession.of(victim), purgePackages = true) }
        }
```

Add the engine toggle:

```kotlin
    fun setProjectEngine(choice: ExecutionEngineChoice) {
        val updated = _uiState.value.project.copy(engine = choice, updatedAt = now())
        persistProject(updated)
        _uiState.update { it.copy(project = updated) }
    }
```

Collect swap progress in `init` (after `uiState = _uiState.asStateFlow()`):

```kotlin
        viewModelScope.launch { swapProgress.collect { phase -> _uiState.update { it.copy(swapPhase = phase) } } }
```

Add imports: `ExecutionEngineChoice`, `SwapPhase`, `StateFlow`.

- [ ] **Step 5: Run editor tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"
```
Expected: PASS (existing + new). Fix any existing test that injected the old `engineProvider = { fakeEngine }` — change to `engineProvider = { _ -> fakeEngine }` and pass a `defaultEngine`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "feat: EditorViewModel resolves per-project engine, engine toggle, engine-correct delete"
```

---

## Task 10: `PackagesViewModel` / `DataViewModel` / `PreviewViewModel` per-project engine

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/data/DataViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt`
- Test: their existing test files under `app/src/test/...`

The pattern is identical in all three: each already resolves the active project in `resolveContext`/`init`; compute its resolved engine there and route through it. Replace the no-arg providers.

- [ ] **Step 1: `PackagesViewModel`**

Change constructor providers:

```kotlin
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val engineProvider: (ExecutionEngineChoice) -> ExecutionEngine = { ServiceLocator.engineFor(it) },
```

Remove `engineIsLocalProvider`. Add a resolved-choice field set in `resolveContext`:

```kotlin
    private var engineChoice: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL
```

In `resolveContext`, after computing `active`/`session`:

```kotlin
        engineChoice = ExecutionEngineChoice.resolve(active?.engine, defaultEngine())
        _uiState.update { it.copy(projectName = active?.name ?: "", engineIsLocal = engineChoice == ExecutionEngineChoice.LOCAL) }
```

Replace every `engineProvider()` call with `engineProvider(engineChoice)` (in `refresh`, `install`, `uninstall`).

- [ ] **Step 2: `DataViewModel`**

Same shape. Replace `dataStoreProvider: () -> SessionDataStore = { ServiceLocator.currentSessionDataStore() }` with:

```kotlin
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val dataStoreProvider: (ExecutionEngineChoice) -> SessionDataStore = { ServiceLocator.dataStoreFor(it) },
```

Remove the `engineIsLocalProvider`; resolve from the active project. In the init/resolve where `active`/`session` are computed, set `engineChoice = ExecutionEngineChoice.resolve(active?.engine, defaultEngine())`, drive `engineIsLocal` from it, and replace `dataStoreProvider()` with `dataStoreProvider(engineChoice)`.

- [ ] **Step 3: `PreviewViewModel`**

Replace `engineProvider: () -> ExecutionEngine = { ServiceLocator.currentExecutionEngine() }` with the choice-taking form + `defaultEngine`, resolve `engineChoice` from the active project (it already resolves `active`), and call `engineProvider(engineChoice).preview(...)`.

- [ ] **Step 4: Update the three ViewModels' tests**

Wherever a test injects `engineProvider = { fake }` / `dataStoreProvider = { fake }` / `engineIsLocalProvider = { true }`, change to the choice-taking lambdas (`{ _ -> fake }`) and add `defaultEngine = { ExecutionEngineChoice.LOCAL }`. Add one test each asserting the resolved engine follows the active project's `engine` (mirror Task 9's `runCode resolves the active project engine`, adapted to `refresh`/`list`/`preview`).

- [ ] **Step 5: Run the full unit suite (compile gate for Tasks 5–10)**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest
```
Expected: PASS (all, incl. the new pure tests). This is the first point the whole app compiles after Tasks 5–6.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt app/src/main/java/com/rmobile/console/ui/data/DataViewModel.kt app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt app/src/test/java/com/rmobile/console/ui/packages app/src/test/java/com/rmobile/console/ui/data app/src/test/java/com/rmobile/console/ui/preview
git commit -m "feat: Packages/Data/Preview ViewModels route through per-project engine"
```

---

## Task 11: Editor UI — engine toggle + swap-progress indicator

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`

No JVM test (Compose UI); verify on device.

- [ ] **Step 1: Add an engine item to the editor overflow menu**

In the overflow `DropdownMenu` (where "Data files" / other items live), add an item that reads and flips the active project's resolved engine. Use `uiState.project.engine` resolved against the app default (the ViewModel already exposes `setProjectEngine`). Minimal item:

```kotlin
DropdownMenuItem(
    text = { Text(if (uiState.project.engine == ExecutionEngineChoice.REMOTE) "Engine: Remote" else "Engine: Local") },
    onClick = {
        menuExpanded = false
        val next = if (uiState.project.engine == ExecutionEngineChoice.REMOTE)
            ExecutionEngineChoice.LOCAL else ExecutionEngineChoice.REMOTE
        viewModel.setProjectEngine(next)
    },
)
```

(Import `ExecutionEngineChoice`. Note a null `engine` displays as "Local"; the first tap sets an explicit choice — acceptable, since null resolves to the default which is Local by default. If the app default is Remote, prefer showing the *resolved* label: compute `uiState.project.engine ?: <default>`. Since the screen has no direct settings handle, showing the explicit choice with a "Local" fallback is acceptable for v1.)

- [ ] **Step 2: Add a swap-progress indicator**

Where the run indicator / status text renders, show a line when `uiState.swapPhase != SwapPhase.IDLE`:

```kotlin
if (uiState.swapPhase != SwapPhase.IDLE) {
    val label = when (uiState.swapPhase) {
        SwapPhase.SAVING_WORKSPACE -> "Switching project… saving workspace"
        SwapPhase.LOADING_WORKSPACE -> "Switching project… loading workspace"
        SwapPhase.RESTORING_LIBRARY -> "Switching project… restoring packages"
        SwapPhase.RESTORE_FAILED -> "Couldn't load saved workspace — starting empty"
        SwapPhase.IDLE -> ""
    }
    Text(label, style = MaterialTheme.typography.labelSmall)
}
```

(Import `SwapPhase`.)

- [ ] **Step 3: Verify on device**

Build/install, switch a project's engine via the overflow menu (confirm a Remote project runs against the backend and a Local one on-device), and observe the swap indicator when switching between two Local projects with non-trivial workspaces.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "feat: editor engine toggle + swap-progress indicator"
```

---

## Task 12: Settings — relabel the engine toggle as "default for new projects"

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Update copy**

In `SettingsScreen.kt`, change the engine section's heading/description to state it is the default for new projects, e.g. the section title to "Default engine for new projects" and a supporting line: "New projects start on this engine. Change a project's engine from the editor's ⋮ menu." Keep the two radio options (Local/Remote) and `viewModel.setEngine(...)` wiring unchanged.

- [ ] **Step 2: Verify on device**

Open Settings; confirm the relabeled copy; create a new project and confirm it starts on the selected default; confirm changing the default does not move an existing project that has an explicit engine.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/rmobile/console/ui/settings/SettingsScreen.kt
git commit -m "docs: relabel Settings engine toggle as default for new projects"
```

---

## Task 13: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `app/src/main/assets/webr/README.md`

- [ ] **Step 1: `CLAUDE.md`**

In the `data/execution/` description and the "Current scope" section: state that the Local engine is now **per-project isolated** (workspace + package library) via a session-keyed `ensureSession` swap in `bridge.js`, backed by per-`(kind, session)` snapshot files (`webr-<kind>-<session>.RData`/`.tar.gz`), with LRU eviction (`MAX_RESIDENT_LIBS`), swap progress (`SwapPhase`/`swapProgress`), swap error surfacing into stderr, and a one-time `LegacyLocalStateMigration`. State that **engine choice is now per-project** (`Project.engine`, resolved via `ExecutionEngineChoice.resolve(project.engine, settingsDefault)`; the Settings toggle is the default for new projects). Remove "per-project engine choice" and "per-project Local isolation" from the "Still not built" list; add the deferred **shared-library opt-in** as the remaining Local item.

- [ ] **Step 2: `app/src/main/assets/webr/README.md`**

Add a section documenting the session-swap model (`ensureSession`), per-session snapshot/library files, LRU eviction, and the progress/error surfacing, alongside the existing library/workspace persistence notes.

- [ ] **Step 3: Commit**

```powershell
git add CLAUDE.md app/src/main/assets/webr/README.md
git commit -m "docs: per-project Local isolation + per-project engine choice"
```

---

## Task 14: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Unit tests + lint**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:lint
```
Expected: all unit tests PASS; Lint 0 errors.

- [ ] **Step 2: On-device acceptance matrix**

Build/install and run the full matrix from the spec's Testing section:
1. Two Local projects: variables isolated both directions, across kill/relaunch.
2. Package installed in A not visible in B; visible again on return to A.
3. Per-project engine: A=Local, B=Remote; switching projects switches engine with no Settings change; a new project inherits the Settings default.
4. Migration: upgrade from a build with the shared state adopts the prior workspace+library into the last-open project; other projects start empty. (Verify by installing the pre-change build, creating state, then installing this build.)
5. LRU: visiting >3 Local projects with libraries evicts the oldest resident lib; no crash; packages still correct on revisit.
6. Progress + error: swap phases surface in the editor; a corrupted/failed restore surfaces the stderr warning + `RESTORE_FAILED`.

- [ ] **Step 3: Final commit (if any doc/tweak fixes came out of verification)**

```powershell
git add -A
git commit -m "chore: per-project Local isolation verification fixes"
```

Then hand off with **superpowers:finishing-a-development-branch** (base branch `claude/r-app-android-version-ztmyd1`).

---

## Notes for the implementer

- **Windows CRLF:** `.gitattributes` pins `webr/*.R` to LF, and `bridge.js` already strips CR from `harness.R`. Keep `bridge.js` edits LF-clean; do not introduce a CR that WebR's parser would choke on.
- **Never name a Kotlin class/object `R`** — collides with the generated resource class.
- **Harness parity:** this change does not touch `harness.R` or the table-emit helpers; leave the backend `plumber.R` byte-for-byte parity intact.
- **Untracked CSVs** (`200mb.csv`, `military.csv`, `sales.csv`) are local test data — do not commit them.
- **Do not run `/code-review ultra`** — it is user-triggered/billed.
