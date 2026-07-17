# Per-project shared-library opt-in (Local engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Local-engine project opt into a package library shared across projects, keeping its workspace and data per-project.

**Architecture:** Decouple a project's *library key* from its *session id*. The session id (`proj-<id>`) keys workspace + data (unchanged); the library key is `"shared"` when the project opts in, else `proj-<id>`, and keys the on-device package library (`.libPaths`, residency, LRU, tar.gz snapshot). Local (WebR) only; no backend change. Threading is via an optional `libraryKey` argument on the `ExecutionEngine` ops (Remote ignores it; Local forwards it to `WebRController` → `bridge.js`).

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, JUnit4, WebR (JS in an offscreen WebView).

**Spec:** `docs/superpowers/specs/2026-07-17-per-project-shared-library-design.md`

**Conventions for every commit in this plan:**
- Windows shell. Set JDK 21 once per shell: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`.
- Run a single test class: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.SomeTest"`.
- Full app unit suite: `.\gradlew.bat :app:testDebugUnitTest`.
- `bridge.js` isn't unit-tested; verify with `node --check app/src/main/assets/webr/bridge.js` and (later) on-device.
- Commit trailer (last line of every commit message):
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Leave the untracked CSVs (`200mb.csv`, `military.csv`, `sales.csv`) uncommitted.

---

## Two invariants the implementation must preserve (from the spec)

1. **A single project's delete/reset must never purge the shared library** — `bridge.js` `webrReset` skips the library purge when the library key is `"shared"`.
2. **`resetPackagesToBase` still runs on every session swap** — and also on a same-session library-only change — so a package `library()`'d in one project's run can't linger against the next.

---

## Task 1: Data model — `Project.sharedLibrary`, `ProjectSession.libraryKey`

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/project/Project.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/project/ProjectSession.kt`
- Test: `app/src/test/java/com/rmobile/console/data/project/ProjectSessionTest.kt`
- Test: `app/src/test/java/com/rmobile/console/data/project/ProjectSharedLibraryTest.kt` (new)

- [ ] **Step 1: Write the failing tests for `libraryKey`**

Add to `ProjectSessionTest.kt` (the existing `project(...)` helper omits `sharedLibrary`, so it defaults to `false`; add an overload that sets it):

```kotlin
    private fun project(id: Long, name: String, shared: Boolean) =
        Project(id = id, name = name, files = emptyList(), activeFileName = "",
                entryFileName = "", updatedAt = 0, sharedLibrary = shared)

    @Test fun `libraryKey is the project session when not shared`() {
        assertEquals("proj-5", ProjectSession.libraryKey(project(5, "P", false)))
    }

    @Test fun `libraryKey is the shared key when shared`() {
        assertEquals(ProjectSession.SHARED_LIBRARY_KEY,
            ProjectSession.libraryKey(project(5, "P", true)))
    }

    @Test fun `shared key is the literal string shared`() {
        assertEquals("shared", ProjectSession.SHARED_LIBRARY_KEY)
    }
```

Create `app/src/test/java/com/rmobile/console/data/project/ProjectSharedLibraryTest.kt` (JSON back-compat — an old project JSON with no `sharedLibrary` must deserialize to `false`):

```kotlin
package com.rmobile.console.data.project

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSharedLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `defaults to false`() {
        assertFalse(ProjectOps.newProject(1, "P", 0).sharedLibrary)
    }

    @Test fun `old JSON without the field decodes to false`() {
        val legacy = """{"id":1,"name":"P","files":[],"activeFileName":"a",
            "entryFileName":"a","updatedAt":0}""".trimIndent()
        assertFalse(json.decodeFromString(Project.serializer(), legacy).sharedLibrary)
    }

    @Test fun `round-trips true`() {
        val p = ProjectOps.newProject(1, "P", 0).copy(sharedLibrary = true)
        val back = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), p))
        assertTrue(back.sharedLibrary)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectSessionTest" --tests "com.rmobile.console.data.project.ProjectSharedLibraryTest"`
Expected: FAIL to compile — `sharedLibrary` and `ProjectSession.SHARED_LIBRARY_KEY`/`libraryKey` don't exist.

- [ ] **Step 3: Add the model field**

In `Project.kt`, add `sharedLibrary` as the **last** field (kotlinx default → JSON back-compat):

```kotlin
@Serializable
data class Project(
    val id: Long,
    val name: String,
    val files: List<ProjectFile>,
    val activeFileName: String,
    val entryFileName: String,
    val updatedAt: Long,
    val engine: ExecutionEngineChoice? = null,
    val sharedLibrary: Boolean = false,
)
```

`ProjectOps.newProject` already returns a `Project` via named args and needs no change (the new field defaults to `false`). Leave `newProject`'s signature as-is.

- [ ] **Step 4: Add `libraryKey` + the shared key constant**

Replace `ProjectSession.kt` body with:

```kotlin
object ProjectSession {
    /** The library key shared by every project that opts in. Kept in sync with
     *  bridge.js `SHARED_LIBRARY_KEY`. */
    const val SHARED_LIBRARY_KEY = "shared"

    fun of(project: Project): String = "proj-${project.id}"

    /** Which package library a project uses: the shared one when it opted in, else its own. */
    fun libraryKey(project: Project): String =
        if (project.sharedLibrary) SHARED_LIBRARY_KEY else of(project)
}
```

(Keep the existing KDoc header comment above the object.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectSessionTest" --tests "com.rmobile.console.data.project.ProjectSharedLibraryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/project/Project.kt \
        app/src/main/java/com/rmobile/console/data/project/ProjectSession.kt \
        app/src/test/java/com/rmobile/console/data/project/ProjectSessionTest.kt \
        app/src/test/java/com/rmobile/console/data/project/ProjectSharedLibraryTest.kt
git commit -m "feat: Project.sharedLibrary + ProjectSession.libraryKey (shared vs isolated)"
```

---

## Task 2: Thread `libraryKey` through `ExecutionEngine`, engines, and `WebRController`

This is a plumbing/refactor task: add an optional `libraryKey` arg to the six Local-relevant engine ops. Remote ignores it; Local forwards it to the controller, which passes it to the bridge as an extra JS argument. Because JS ignores extra arguments, the bridge keeps working until Task 6 consumes them. **Changing the interface forces every implementer to compile**, so this task also updates the two production engines and the five test fake engines.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt` (interface + `RemoteExecutionEngine`)
- Modify: `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- Modify (test fakes): `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`, `.../ui/preview/PreviewViewModelTest.kt`, `.../ui/editor/EditorViewModelTest.kt`, `.../ui/data/DataViewModelTest.kt`, `.../ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Update the interface**

In `ExecutionEngine.kt`, change the interface method signatures to add a trailing optional `libraryKey`:

```kotlin
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest, libraryKey: String? = null): Result<ExecuteResponse>
    /** Clears the session's workspace (and its package library when [purgePackages]).
     *  [libraryKey] selects which on-device library to purge; a shared library is never purged. */
    suspend fun reset(sessionId: String, purgePackages: Boolean = false, libraryKey: String? = null): Result<Unit>
    suspend fun listPackages(sessionId: String, libraryKey: String? = null): Result<PackagesResponse>
    suspend fun install(request: InstallRequest, libraryKey: String? = null): Result<InstallResponse>
    suspend fun uninstall(request: UninstallRequest, libraryKey: String? = null): Result<UninstallResponse>
    /** Read-only preview of a data file or workspace object as a table. */
    suspend fun preview(request: PreviewRequest, libraryKey: String? = null): Result<PreviewResponse>
}
```

- [ ] **Step 2: Update `RemoteExecutionEngine` (ignore `libraryKey`)**

In the same file, add the param to each override; the backend has no shared-library concept, so ignore it (Kotlin overrides omit the `= null`):

```kotlin
    override suspend fun execute(request: ExecuteRequest, libraryKey: String?): Result<ExecuteResponse> {
        val sessionId = request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID
        val files = request.files
        return if (files != null) {
            repository.run(files, request.entryFile.orEmpty(), sessionId)
        } else {
            repository.run(request.code.orEmpty(), sessionId)
        }
    }

    override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?): Result<Unit> =
        repository.reset(sessionId, purgePackages).map { }

    override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> =
        repository.listPackages(sessionId)

    override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> =
        repository.install(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> =
        repository.uninstall(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun preview(request: PreviewRequest, libraryKey: String?): Result<PreviewResponse> =
        repository.preview(request.source, request.name, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)
```

- [ ] **Step 3: Update `LocalExecutionEngine` (forward `libraryKey`, default to session)**

Replace the six overrides in `LocalExecutionEngine.kt`:

```kotlin
    override suspend fun execute(request: ExecuteRequest, libraryKey: String?): Result<ExecuteResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        val requestJson = json.encodeToString(ExecuteRequest.serializer(), request)
        json.decodeFromString<ExecuteResponse>(controller.execute(requestJson, libraryKey ?: session))
    }

    override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?): Result<Unit> = runCatching {
        controller.reset(sessionId, purgePackages, libraryKey ?: sessionId); Unit
    }

    override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> = runCatching {
        json.decodeFromString<PackagesResponse>(controller.listPackages(sessionId, libraryKey ?: sessionId))
    }

    override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName, session, libraryKey ?: session))
    }

    override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName, session, libraryKey ?: session))
    }

    override suspend fun preview(request: PreviewRequest, libraryKey: String?): Result<PreviewResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        val reqJson = json.encodeToString(PreviewRequest.serializer(), request)
        json.decodeFromString<PreviewResponse>(controller.preview(reqJson, libraryKey ?: session))
    }
```

- [ ] **Step 4: Update `WebRController` to pass the extra JS argument**

Replace the six public suspend methods in `WebRController.kt` (each adds a `libraryKey` arg, encoded as a JS literal):

```kotlin
    /** Runs a full ExecuteRequest (as JSON) against the given library key. */
    suspend fun execute(requestJson: String, libraryKey: String): String =
        callBridge("window.webrRun", org.json.JSONObject.quote(requestJson), org.json.JSONObject.quote(libraryKey))

    /** Clears the given session's workspace (and its library when [purgePackages], unless shared). */
    suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String): String =
        callBridge("window.webrReset", org.json.JSONObject.quote(sessionId), purgePackages.toString(), org.json.JSONObject.quote(libraryKey))

    suspend fun installPackage(pkg: String, sessionId: String, libraryKey: String): String =
        callBridge("window.webrInstall", org.json.JSONObject.quote(pkg), org.json.JSONObject.quote(sessionId), org.json.JSONObject.quote(libraryKey))

    suspend fun uninstallPackage(pkg: String, sessionId: String, libraryKey: String): String =
        callBridge("window.webrUninstall", org.json.JSONObject.quote(pkg), org.json.JSONObject.quote(sessionId), org.json.JSONObject.quote(libraryKey))

    suspend fun listPackages(sessionId: String, libraryKey: String): String =
        callBridge("window.webrListPackages", org.json.JSONObject.quote(sessionId), org.json.JSONObject.quote(libraryKey))

    suspend fun preview(requestJson: String, libraryKey: String): String =
        callBridge("window.webrPreview", org.json.JSONObject.quote(requestJson), org.json.JSONObject.quote(libraryKey))
```

- [ ] **Step 5: Update the five test fake engines so the suite compiles**

Each test declares a fake `ExecutionEngine`. Add the `libraryKey: String?` param to every override (no default in overrides). For **`PackagesViewModelTest.kt`**, also capture the library key so Task 3 can assert on it — replace its `FakeEngine` with:

```kotlin
    private class FakeEngine(val label: String = "local") : com.rmobile.console.data.execution.ExecutionEngine {
        var installedArg: String? = null
        var lastListLibraryKey: String? = null
        var lastInstallLibraryKey: String? = null
        var lastUninstallLibraryKey: String? = null
        override suspend fun execute(request: ExecuteRequest, libraryKey: String?) = Result.success(ExecuteResponse())
        override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?) = Result.success(Unit)
        override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> {
            lastListLibraryKey = libraryKey
            return Result.success(PackagesResponse(listOf("$label-pkg")))
        }
        override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> {
            installedArg = request.packageName; lastInstallLibraryKey = libraryKey
            return Result.success(InstallResponse(installed = true))
        }
        override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> {
            lastUninstallLibraryKey = libraryKey
            return Result.success(UninstallResponse(removed = true))
        }
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.PreviewResponse())
    }
```

For the other four (`PreviewViewModelTest.kt`, `EditorViewModelTest.kt`, `DataViewModelTest.kt`, `SettingsViewModelTest.kt`), find each `: ExecutionEngine` fake and add `, libraryKey: String?` to the parameter list of every override (`execute`, `reset`, `listPackages`, `install`, `uninstall`, `preview`), leaving their bodies unchanged. Do not add `= null` in the overrides.

- [ ] **Step 6: Verify the whole unit suite compiles and passes**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS (all existing tests still green; nothing behavioral changed yet).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt \
        app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt \
        app/src/main/java/com/rmobile/console/data/execution/WebRController.kt \
        app/src/test/java/com/rmobile/console/ui
git commit -m "refactor: thread optional libraryKey through ExecutionEngine + WebRController"
```

---

## Task 3: `PackagesViewModel` — route by library key + `setSharedLibrary` toggle

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `PackagesViewModelTest.kt`. These use a `FakeEngine` (from Task 2) injected via `engineProvider`, and a project that starts isolated:

```kotlin
    @Test
    fun `list uses the isolated library key by default`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()
        assertEquals("proj-100", engine.lastListLibraryKey)
        assertFalse(vm.uiState.value.sharedLibrary)
    }

    @Test
    fun `enabling shared library persists the flag and reloads with the shared key`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()

        vm.setSharedLibrary(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sharedLibrary)
        assertTrue(store.stored.first { it.id == 100L }.sharedLibrary)   // persisted
        assertEquals("shared", engine.lastListLibraryKey)                 // reloaded with shared key
    }

    @Test
    fun `install and uninstall use the resolved library key`() = runTest {
        val project = ProjectOps.newProject(id = 7, name = "P", now = 0).copy(sharedLibrary = true)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 7)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()
        vm.onPackageNameChanged("praise"); vm.install(); advanceUntilIdle()
        vm.uninstall("praise"); advanceUntilIdle()
        assertEquals("shared", engine.lastInstallLibraryKey)
        assertEquals("shared", engine.lastUninstallLibraryKey)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: FAIL — `setSharedLibrary`, `uiState.sharedLibrary`, and library-key routing don't exist.

- [ ] **Step 3: Add `sharedLibrary` to the UI state**

In `PackagesUiState.kt` add a field:

```kotlin
data class PackagesUiState(
    val installed: List<String> = emptyList(),
    val packageName: String = "",
    val installing: Boolean = false,
    val message: String? = null,
    val log: String = "",
    val isError: Boolean = false,
    val projectName: String = "",
    val engineIsLocal: Boolean = false,
    val sharedLibrary: Boolean = false,
    val swapPhase: SwapPhase = SwapPhase.IDLE,
)
```

- [ ] **Step 4: Route ops by library key + add the toggle in `PackagesViewModel`**

In `PackagesViewModel.kt`:

Add imports:
```kotlin
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
```

Add fields next to `session`/`engineChoice`:
```kotlin
    private var activeProject: Project? = null
    private var libraryKey: String = RExecutionRepository.DEFAULT_SESSION_ID
```

In `resolveContext()`, capture the active project + library key and publish `sharedLibrary` (replace the method body's assignments accordingly):
```kotlin
    private fun resolveContext() {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        activeProject = active
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        libraryKey = active?.let { ProjectSession.libraryKey(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        engineChoice = ExecutionEngineChoice.resolve(active?.engine, defaultEngine())
        _uiState.update {
            it.copy(
                projectName = active?.name ?: "",
                engineIsLocal = engineChoice == ExecutionEngineChoice.LOCAL,
                sharedLibrary = active?.sharedLibrary ?: false,
            )
        }
    }
```

Pass `libraryKey` to the three engine ops:
- In `refresh()`: `engineProvider(engineChoice).listPackages(session, libraryKey)`
- In `install()`: `engineProvider(engineChoice).install(InstallRequest(pkg, session), libraryKey)`
- In `uninstall()`: `engineProvider(engineChoice).uninstall(UninstallRequest(packageName, session), libraryKey)`

Add the toggle method (after `uninstall`):
```kotlin
    /** Opts the active project into (or out of) the shared package library. Persists the
     *  flag, re-resolves the library key, and reloads the list to reflect the new library. */
    fun setSharedLibrary(shared: Boolean) {
        val current = activeProject ?: return
        if (current.sharedLibrary == shared) return
        val updated = current.copy(sharedLibrary = shared)
        projectStore.persistProjects(ProjectOps.upsert(projectStore.loadProjects(), updated))
        activeProject = updated
        libraryKey = ProjectSession.libraryKey(updated)
        _uiState.update { it.copy(sharedLibrary = shared) }
        refresh()
    }
```

- [ ] **Step 5: Run to verify pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt \
        app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "feat: PackagesViewModel routes by library key + shared-library toggle"
```

---

## Task 4: `EditorViewModel` + `PreviewViewModel` — pass library key; delete-purge guard

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Write the failing test (delete of a shared project uses the shared library key)**

The Editor fake engine records `reset` arguments. Extend that fake (in `EditorViewModelTest.kt`) to capture the reset library key + purge flag, then add:

```kotlin
    @Test
    fun `deleting a shared-library project resets with the shared library key`() = runTest {
        // Arrange a shared-library project as the active/only project, then delete it.
        // (Uses the test's existing VM builder + fake engine; see neighbouring tests.)
        val shared = ProjectOps.newProject(id = 55, name = "S", now = 0).copy(sharedLibrary = true)
        val vm = viewModelWithProjects(listOf(shared), active = 55L)   // existing helper pattern
        advanceUntilIdle()

        vm.deleteProject(55L)
        advanceUntilIdle()

        assertEquals("proj-55", fakeEngine.lastResetSessionId)
        assertEquals("shared", fakeEngine.lastResetLibraryKey)
        assertTrue(fakeEngine.lastResetPurge)   // app still requests purge; bridge skips it for shared
    }
```

> If `EditorViewModelTest.kt` has no `viewModelWithProjects`/`fakeEngine` helpers, mirror the construction used by the existing delete/run tests in that file (inject an `InMemoryProjectStore` + a fake `engineProvider`), and add `var lastResetSessionId`, `var lastResetLibraryKey`, `var lastResetPurge` to that file's fake engine's `reset` override.

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: FAIL — `reset` is still called without a library key.

- [ ] **Step 3: Pass the library key from Editor ops**

In `EditorViewModel.kt`:

`deleteProject` (the `victim` reset — line ~168):
```kotlin
            viewModelScope.launch {
                engineFor(victim).reset(
                    ProjectSession.of(victim),
                    purgePackages = true,
                    libraryKey = ProjectSession.libraryKey(victim),
                )
            }
```

`resetSession` (line ~252):
```kotlin
    fun resetSession() {
        val project = _uiState.value.project
        val session = ProjectSession.of(project)
        viewModelScope.launch {
            engineFor(project).reset(session, libraryKey = ProjectSession.libraryKey(project))
                .onSuccess { _uiState.update { it.copy(workspaceObjects = emptyList()) } }
                .onFailure { t -> _uiState.update { it.copy(errorMessage = t.message ?: "Failed to reset the session.") } }
        }
    }
```

`runCode` (the `execute` call — line ~275):
```kotlin
            engineFor(project).execute(request, libraryKey = ProjectSession.libraryKey(project))
```

- [ ] **Step 4: Pass the library key from Preview**

In `PreviewViewModel.kt`, the preview call resolves the active project already. Capture the project's library key and pass it. Where it currently computes `session`, also compute the key and pass it:
```kotlin
        // active is the resolved Project (same value used for `session`)
        val libraryKey = active?.let { com.rmobile.console.data.project.ProjectSession.libraryKey(it) } ?: session
        ...
        engineProvider(engineChoice).preview(PreviewRequest(source, name, session), libraryKey)
```
(If `PreviewViewModel` keeps only `session` and not the `Project`, add a `private var libraryKey` set in its `resolveContext`/`init` exactly as `session` is, using `ProjectSession.libraryKey(active)`.)

- [ ] **Step 5: Run to verify pass + full suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "feat: Editor/Preview pass the project's library key to engine ops"
```

---

## Task 5: Packages screen — the shared-library toggle (Local only)

No unit test (Compose UI); verified on-device in Task 7.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt`

- [ ] **Step 1: Add the toggle row**

Add imports near the other `androidx.compose.material3` imports:
```kotlin
import androidx.compose.material3.Switch
```

Insert a toggle row directly **after** the engine-caption `Text(...)` block (the one ending at the `)` before the `swapPhaseLabel` block, around line 87) and **before** the `swapPhaseLabel` block — shown only for Local projects:
```kotlin
            if (uiState.engineIsLocal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share package library across projects",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Installs go to a library shared by all projects that opt in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = uiState.sharedLibrary,
                        onCheckedChange = { viewModel.setSharedLibrary(it) },
                    )
                }
            }
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt
git commit -m "feat: shared-library toggle on the Packages screen (Local projects)"
```

---

## Task 6: `bridge.js` — decouple library key from session id

The core WebR change. `ensureSession` splits into workspace (session id) and library (library key); the reset purge guard skips the shared library; handlers accept the extra `libraryKey` argument that Task 2's controller already passes.

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js`

- [ ] **Step 1: Add the shared-key constant + `currentLibraryKey` state**

Near the top constants (by `const USER_LIB_ROOT = '/rmobile/library';`), add:
```javascript
const SHARED_LIBRARY_KEY = 'shared';   // kept in sync with Kotlin ProjectSession.SHARED_LIBRARY_KEY
```
Where `let currentSession` is declared, add alongside it:
```javascript
let currentLibraryKey = null;          // which library dir .libPaths currently points at
```

- [ ] **Step 2: Rewrite `ensureSession` to take a library key**

Replace the whole `ensureSession` function with:
```javascript
// Make sessionId the live session, pointing the library at libraryKey (which is the
// session id for an isolated project, or "shared" for an opted-in one). Workspace/data
// swap on the session id; the library dir re-points on the library key. A no-op when
// both already match. Best-effort throughout; a failed restore records lastSwapWarning.
async function ensureSession(sessionId, libraryKey) {
  const lib = libraryKey || sessionId;
  if (sessionId === currentSession && lib === currentLibraryKey) return;
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  const sessionChanged = sessionId !== currentSession;
  try {
    if (sessionChanged) {
      if (currentSession != null) {
        AndroidBridge.onSwapProgress('SAVING_WORKSPACE');
        await snapshotWorkspace(currentSession);
        try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
        await resetPackagesToBase(); // don't let the outgoing project's library()'d packages leak
      }
      AndroidBridge.onSwapProgress('LOADING_WORKSPACE');
      const ok = await restoreWorkspace(sessionId);
      if (!ok) { lastSwapWarning = "Couldn't load this project's saved workspace; starting empty."; AndroidBridge.onSwapProgress('RESTORE_FAILED'); }
      else { lastSwapWarning = ''; }
    } else if (lib !== currentLibraryKey) {
      // Same project, library pointer changed (an opt-in toggle). Unload packages loaded
      // from the old library so they don't linger against the new one (invariant #2).
      await resetPackagesToBase();
    }
    AndroidBridge.onSwapProgress('RESTORING_LIBRARY');
    await ensureLibResident(lib);
    await ensureUserLib(lib);
    currentSession = sessionId;
    currentLibraryKey = lib;
    await evictLibsIfOverCap(lib);
  } finally {
    AndroidBridge.onSwapProgress('IDLE');
  }
}
```

- [ ] **Step 3: Thread the library key through `runOnce` / `webrRun`**

In `webrRun`, accept the extra arg and pass it down:
```javascript
window.webrRun = async (id, requestJson, libraryKey) => {
```
and change the `runOnce(req)` call inside it to `runOnce(req, libraryKey)`.

In `runOnce`, change the signature and the `ensureSession` call:
```javascript
async function runOnce(req, libraryKey) {
  ...
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId, libraryKey || sessionId);
```
(Leave `snapshotWorkspace(sessionId)`, `syncData(req.sessionId)`, and `linkData(req.sessionId)` keyed on the session id — unchanged.)

- [ ] **Step 4: Update `webrReset` (library key + shared-purge guard)**

Replace `window.webrReset` with:
```javascript
window.webrReset = async (id, sessionId, purge, libraryKey) => {
  const lib = libraryKey || sessionId;
  try {
    if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
    if (sessionId === currentSession) {
      try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
      await resetPackagesToBase();
    }
    AndroidBridge.snapshotDelete('workspace', sessionId); // must not resurrect vars on next launch
    // Invariant #1: never purge a shared library on a single project's reset/delete.
    if (purge && lib !== SHARED_LIBRARY_KEY) {
      AndroidBridge.snapshotDelete('library', lib);
      const libd = libDir(lib);
      try { await webR.evalRVoid(`if (dir.exists(${JSON.stringify(libd)})) unlink(${JSON.stringify(libd)}, recursive = TRUE, force = TRUE)`); } catch (e) {}
      const i = residentLibs.indexOf(lib);
      if (i >= 0) residentLibs.splice(i, 1);
      if (lib === currentLibraryKey) currentLibraryKey = null;
      if (sessionId === currentSession) currentSession = null;
    }
    AndroidBridge.onResult(id, JSON.stringify({ ok: true }));
  } catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};
```

- [ ] **Step 5: Update `webrInstall` / `webrUninstall` / `webrListPackages` to key on the library**

For each, accept `libraryKey`, resolve `const lib = libraryKey || sessionId;`, call `ensureSession(sessionId, lib)`, and use `lib` everywhere the old code used `sessionId` for the library (`libDir(...)`, `snapshotLibrary(...)`).

`webrInstall` signature + body changes:
```javascript
window.webrInstall = async (id, pkg, sessionId, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ installed: false, error: 'WebR not ready', stdout: '', stderr: '', timedOut: false, systemRequirements: null })); return; }
  const lib = libraryKey || sessionId;
  await ensureSession(sessionId, lib);
  ...
    if (installed) await snapshotLibrary(lib); // persist across restarts
  ...
};
```

`webrUninstall`:
```javascript
window.webrUninstall = async (id, pkg, sessionId, libraryKey) => {
  try {
    const lib = libraryKey || sessionId;
    await ensureSession(sessionId, lib);
    const libDirPath = libDir(lib);
    try { await webR.FS.unmount(`${libDirPath}/${pkg}`); } catch (e) { /* not a mount */ }
    const goneR = await webR.evalR(
      `local({\n` +
      `  p <- ${JSON.stringify(pkg)}; lib <- ${JSON.stringify(libDirPath)}; dir <- file.path(lib, p);\n` +
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
      } catch (e) { /* unload is best-effort */ }
      await snapshotLibrary(lib); // persist the removal across restarts
    }
    AndroidBridge.onResult(id, JSON.stringify({ removed, error: removed ? null : `${pkg} was not removed.` }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ removed: false, error: String(e) }));
  }
};
```

`webrListPackages`:
```javascript
window.webrListPackages = async (id, sessionId, libraryKey) => {
  try {
    const lib = libraryKey || sessionId;
    await ensureSession(sessionId, lib);
    const r = await webR.evalR(`rownames(installed.packages(lib.loc = ${JSON.stringify(libDir(lib))}))`);
    const packages = await r.toArray();
    webR.destroy(r);
    AndroidBridge.onResult(id, JSON.stringify({ packages }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ packages: [] }));
  }
};
```

- [ ] **Step 6: Update `webrPreview` to pass the library key**

```javascript
window.webrPreview = async (id, requestJson, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ table: null, error: 'WebR not ready', truncated: false })); return; }
  const req = JSON.parse(requestJson);
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId, libraryKey || sessionId);
```
(Leave the rest of `webrPreview` unchanged — it reads `req.sessionId` for data-file lookups, which stays session-keyed.)

- [ ] **Step 7: Syntax-check**

Run: `node --check app/src/main/assets/webr/bridge.js`
Expected: no output (valid). Also grep to confirm no `ensureSession(` call remains with a single argument:
Run: `grep -n "ensureSession(" app/src/main/assets/webr/bridge.js`
Expected: every call passes two arguments (the definition line, plus `runOnce`, `webrReset`?—no, reset doesn't call ensureSession—`webrInstall`, `webrUninstall`, `webrListPackages`, `webrPreview`, `runOnce`).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/assets/webr/bridge.js
git commit -m "feat: bridge.js keys the library on a separate key (shared-library opt-in)"
```

---

## Task 7: Docs + full verification

**Files:**
- Modify: `CLAUDE.md`
- Modify: `app/src/main/assets/webr/README.md`

- [ ] **Step 1: Update `CLAUDE.md`**

In the on-device section that describes per-project isolation and the "Still not built" note, replace the "per-project shared-library opt-in" *deferred* mention with a *built* description. Add, near the `ensureSession` description:

> A project can **opt into a shared package library** (`Project.sharedLibrary`): its library key (`ProjectSession.libraryKey`) becomes the fixed `"shared"` key instead of `proj-<id>`, so `.libPaths`, library residency/LRU, and the `webr-library-shared.tar.gz` snapshot are shared across all opted-in projects, while workspace + data stay per-project. The Local engine ops carry an optional `libraryKey` (Remote ignores it); `bridge.js` `ensureSession(sessionId, libraryKey)` swaps the workspace on the session id and points `.libPaths` on the library key. A single project's delete/reset **never purges the shared library**. The toggle is on the Packages screen (Local projects only).

And remove the shared-library item from the "Still **not** built" paragraph (leave the network-egress/VM-isolation item).

- [ ] **Step 2: Update `app/src/main/assets/webr/README.md`**

Add a short paragraph documenting: the `SHARED_LIBRARY_KEY = 'shared'` constant (kept in sync with Kotlin `ProjectSession.SHARED_LIBRARY_KEY`), that `ensureSession` takes `(sessionId, libraryKey)`, and that `webrReset` skips the library purge for the shared key.

- [ ] **Step 3: Full unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Lint/compile the app**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: On-device acceptance (manual — record results)**

Build/install: `.\gradlew.bat :app:installDebug`. With two Local projects A and B:
1. Toggle both A and B to shared (Packages screen). Install a package in A → open B's Packages screen → it appears; a run in B can `library()` it.
2. Define `x <- 1` in A's editor and `y <- 2` in B's → each project still shows only its own workspace object (data/workspace stay isolated).
3. Toggle B back to isolated → the package disappears from B's list but remains in A (shared).
4. Delete a shared project → the shared library survives for the other.
5. Kill and relaunch the app → the shared library persists (restored from `webr-library-shared.tar.gz`).

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md app/src/main/assets/webr/README.md
git commit -m "docs: per-project shared-library opt-in (Local engine)"
```

---

## Self-review notes (coverage against the spec)

- Spec §Data model → Task 1. §App-side threading → Tasks 2–4. §bridge.js decoupling → Task 6. §UI → Task 5. §Testing (pure/JVM) → Tasks 1, 3, 4; (on-device) → Task 7 Step 5.
- Invariant #1 (never purge shared) → Task 6 Step 4 (`lib !== SHARED_LIBRARY_KEY`) + Task 4 (Editor passes the key). Invariant #2 (`resetPackagesToBase` on every swap and on same-session library change) → Task 6 Step 2.
- Type consistency: `libraryKey: String?` across the interface/engines/controller; `ProjectSession.libraryKey` / `SHARED_LIBRARY_KEY` used identically in Kotlin and mirrored as `SHARED_LIBRARY_KEY = 'shared'` in `bridge.js`.
```
