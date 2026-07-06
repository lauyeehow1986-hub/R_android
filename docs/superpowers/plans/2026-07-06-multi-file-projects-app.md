# Multi-file Projects — App Plan (B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-buffer editor into a project-based one: multiple named projects of `.R` files, a file switcher with a pinned entry file, and a project library — sending `files`/`entryFile` to the (already multi-file-capable) backend on Run.

**Architecture:** `Project`/`ProjectFile` + pure `ProjectOps`; a `ProjectStore` (SharedPreferences). `EditorViewModel` becomes project-aware but keeps `uiState.code` as a **mirror of the active file's content** so the existing editor field is nearly untouched; new UI (file switcher, project library) is additive. A single `EditorViewModel` instance is shared by the editor and the project-library screen so opening a project updates the editor. This is Plan B of two (backend landed in Plan A).

**Tech Stack:** Kotlin + Compose, kotlinx.serialization, JUnit4 + coroutines-test.

---

## Prerequisites

App tests need the bundled JDK + truststore (plain `./gradlew` fails with `PKIX path building failed`):
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export JKS="C:/Users/lauye/AppData/Local/Temp/claude/C--Users-lauye-Downloads-R-android/6471244e-45c9-4b58-a6cc-c90db51dfa4b/scratchpad/win-roots.jks"
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JKS -Djavax.net.ssl.trustStorePassword=changeit"
```
Append to every gradle command: `-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`. Verify `$JKS` exists first (else BLOCKED).

**Branch:** `feat/multi-file-projects-app` (already created off the default branch, which already has the backend contract).

---

## File Structure

**Create:** `data/project/Project.kt` (models + `ProjectOps`), `data/project/ProjectStore.kt` (interface), `ui/projects/ProjectsScreen.kt`; tests `ProjectOpsTest.kt`, `ProjectStoreSerializationTest.kt`.
**Modify:** `data/model/ExecuteModels.kt` (`ExecFile` + request fields), `data/RExecutionRepository.kt` (`run(files, entryFile)`), `data/settings/SettingsStore.kt` (implement `ProjectStore`), `ui/editor/EditorUiState.kt`, `ui/editor/EditorViewModel.kt`, `ui/editor/EditorScreen.kt`, `MainActivity.kt`, and `EditorViewModelTest.kt`.

---

## Task 1: Project model + `ProjectOps` (pure)

**Files:** Create `app/src/main/java/com/rmobile/console/data/project/Project.kt`, `app/src/test/java/com/rmobile/console/data/project/ProjectOpsTest.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rmobile/console/data/project/ProjectOpsTest.kt`:
```kotlin
package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {

    private fun project() = ProjectOps.newProject(id = 1, name = "P", now = 1)

    @Test
    fun `new project has one entry+active main file`() {
        val p = project()
        assertEquals(listOf("main.R"), p.files.map { it.name })
        assertEquals("main.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
    }

    @Test
    fun `activeContent returns the active file content`() {
        val p = project().copy(files = listOf(ProjectFile("main.R", "x <- 1")), activeFileName = "main.R")
        assertEquals("x <- 1", ProjectOps.activeContent(p))
    }

    @Test
    fun `updateActiveContent replaces only the active file`() {
        val p = ProjectOps.updateActiveContent(project(), "new code", now = 2)
        assertEquals("new code", ProjectOps.activeContent(p))
        assertEquals(2, p.updatedAt)
    }

    @Test
    fun `addFile appends an empty file and makes it active`() {
        val p = ProjectOps.addFile(project(), "helpers.R", now = 2)!!
        assertEquals(listOf("main.R", "helpers.R"), p.files.map { it.name })
        assertEquals("helpers.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
    }

    @Test
    fun `addFile rejects invalid or duplicate names`() {
        assertNull(ProjectOps.addFile(project(), "../evil", now = 2))
        assertNull(ProjectOps.addFile(project(), "main.R", now = 2))
    }

    @Test
    fun `renameFile updates active and entry references`() {
        val p = ProjectOps.renameFile(project(), "main.R", "app.R", now = 2)!!
        assertEquals(listOf("app.R"), p.files.map { it.name })
        assertEquals("app.R", p.activeFileName)
        assertEquals("app.R", p.entryFileName)
    }

    @Test
    fun `deleteFile reassigns active and entry, and rejects the last file`() {
        val two = ProjectOps.addFile(project(), "helpers.R", now = 2)!!   // active = helpers.R, entry = main.R
        val withEntry = ProjectOps.setEntry(two, "helpers.R", now = 3)     // entry = helpers.R
        val p = ProjectOps.deleteFile(withEntry, "helpers.R", now = 4)!!
        assertEquals(listOf("main.R"), p.files.map { it.name })
        assertEquals("main.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
        assertNull(ProjectOps.deleteFile(p, "main.R", now = 5)) // can't delete last
    }

    @Test
    fun `setEntry and setActive only accept existing files`() {
        val p = ProjectOps.addFile(project(), "helpers.R", now = 2)!!
        assertEquals("helpers.R", ProjectOps.setEntry(p, "helpers.R", now = 3).entryFileName)
        assertEquals("main.R", ProjectOps.setEntry(p, "ghost.R", now = 3).entryFileName)
        assertEquals("main.R", ProjectOps.setActive(p, "main.R").activeFileName)
    }

    @Test
    fun `project list upsert replaces by id and sorts by updatedAt desc`() {
        val a = ProjectOps.newProject(1, "A", now = 100)
        val b = ProjectOps.newProject(2, "B", now = 200)
        assertEquals(listOf(2L, 1L), ProjectOps.upsert(listOf(a), b).map { it.id })
        val a2 = a.copy(name = "A2", updatedAt = 300)
        val result = ProjectOps.upsert(listOf(a, b), a2)
        assertEquals(2, result.size)
        assertEquals("A2", result.first { it.id == 1L }.name)
    }

    @Test
    fun `project list delete removes by id`() {
        val a = ProjectOps.newProject(1, "A", now = 100)
        val b = ProjectOps.newProject(2, "B", now = 200)
        assertEquals(listOf(2L), ProjectOps.delete(listOf(b, a), id = 1).map { it.id })
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectOpsTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (types don't exist).

- [ ] **Step 3: Create the models + ops**

Create `app/src/main/java/com/rmobile/console/data/project/Project.kt`:
```kotlin
package com.rmobile.console.data.project

import kotlinx.serialization.Serializable

@Serializable
data class ProjectFile(val name: String, val content: String)

@Serializable
data class Project(
    val id: Long,
    val name: String,
    val files: List<ProjectFile>,
    val activeFileName: String,
    val entryFileName: String,
    val updatedAt: Long,
)

/**
 * Pure operations on projects and the project list — no Android or persistence,
 * so they can be unit-tested. All return new immutable values; file-mutating ops
 * return null when the request is invalid (bad name, duplicate, missing file,
 * or deleting the last file) so callers can surface an error.
 */
object ProjectOps {

    const val MAX_PROJECTS = 100
    val FILE_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private const val SAMPLE = "# Write R code and tap Run\nsummary(cars)\nplot(cars)\n"

    fun isValidFileName(name: String): Boolean = FILE_NAME_REGEX.matches(name)

    fun newProject(id: Long, name: String, now: Long): Project =
        Project(
            id = id,
            name = name,
            files = listOf(ProjectFile("main.R", SAMPLE)),
            activeFileName = "main.R",
            entryFileName = "main.R",
            updatedAt = now,
        )

    fun activeContent(project: Project): String =
        project.files.firstOrNull { it.name == project.activeFileName }?.content ?: ""

    fun updateActiveContent(project: Project, content: String, now: Long): Project =
        project.copy(
            files = project.files.map {
                if (it.name == project.activeFileName) it.copy(content = content) else it
            },
            updatedAt = now,
        )

    fun setActive(project: Project, name: String): Project =
        if (project.files.any { it.name == name }) project.copy(activeFileName = name) else project

    fun setEntry(project: Project, name: String, now: Long): Project =
        if (project.files.any { it.name == name }) project.copy(entryFileName = name, updatedAt = now) else project

    fun addFile(project: Project, name: String, now: Long): Project? {
        if (!isValidFileName(name) || project.files.any { it.name == name }) return null
        return project.copy(
            files = project.files + ProjectFile(name, ""),
            activeFileName = name,
            updatedAt = now,
        )
    }

    fun renameFile(project: Project, oldName: String, newName: String, now: Long): Project? {
        if (!isValidFileName(newName)) return null
        if (project.files.none { it.name == oldName }) return null
        if (project.files.any { it.name == newName }) return null
        return project.copy(
            files = project.files.map { if (it.name == oldName) it.copy(name = newName) else it },
            activeFileName = if (project.activeFileName == oldName) newName else project.activeFileName,
            entryFileName = if (project.entryFileName == oldName) newName else project.entryFileName,
            updatedAt = now,
        )
    }

    fun deleteFile(project: Project, name: String, now: Long): Project? {
        if (project.files.size <= 1 || project.files.none { it.name == name }) return null
        val remaining = project.files.filterNot { it.name == name }
        val fallback = remaining.first().name
        return project.copy(
            files = remaining,
            activeFileName = if (project.activeFileName == name) fallback else project.activeFileName,
            entryFileName = if (project.entryFileName == name) fallback else project.entryFileName,
            updatedAt = now,
        )
    }

    fun upsert(existing: List<Project>, project: Project): List<Project> =
        (existing.filterNot { it.id == project.id } + project).sortedByDescending { it.updatedAt }

    fun delete(existing: List<Project>, id: Long): List<Project> =
        existing.filterNot { it.id == id }
}
```

- [ ] **Step 4: Run — verify PASS**

Run the Step 2 command. Expected: all `ProjectOpsTest` cases pass.

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add app/src/main/java/com/rmobile/console/data/project/Project.kt app/src/test/java/com/rmobile/console/data/project/ProjectOpsTest.kt
git commit -m "app: Project model + pure ProjectOps"
```

---

## Task 2: Execute request `files`/`entryFile` + repository

**Files:** Modify `data/model/ExecuteModels.kt`, `data/RExecutionRepository.kt`; Create `app/src/test/java/com/rmobile/console/data/model/ExecFileModelTest.kt`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/model/ExecFileModelTest.kt`:
```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecFileModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `execute request serializes files and entryFile`() {
        val req = ExecuteRequest(
            code = null,
            sessionId = "default",
            files = listOf(ExecFile("main.R", "cat(1)")),
            entryFile = "main.R",
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"entryFile\":\"main.R\""))
        assertTrue(encoded.contains("\"name\":\"main.R\""))
        assertTrue(encoded.contains("\"content\":\"cat(1)\""))
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.ExecFileModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (`ExecFile` missing; `ExecuteRequest` has no `files`/`entryFile`, and `code` isn't nullable).

- [ ] **Step 3: Update the models**

In `data/model/ExecuteModels.kt`, replace the `ExecuteRequest` and add `ExecFile`:
```kotlin
@Serializable
data class ExecFile(
    val name: String,
    val content: String,
)

@Serializable
data class ExecuteRequest(
    val code: String? = null,
    val sessionId: String? = null,
    val files: List<ExecFile>? = null,
    val entryFile: String? = null,
)
```
(`code` becomes nullable so a project request omits it; the backend treats `files` as taking precedence.)

- [ ] **Step 4: Add the repository overload**

In `data/RExecutionRepository.kt`, add imports for `ExecFile` and add a method (keep the existing `run(code, ...)`):
```kotlin
    suspend fun run(
        files: List<ExecFile>,
        entryFile: String,
        sessionId: String = DEFAULT_SESSION_ID,
    ): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(sessionId = sessionId, files = files, entryFile = entryFile)) }
```

- [ ] **Step 5: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures (the `code`→nullable change still compiles everywhere; `run(code, ...)` builds `ExecuteRequest(code = code, ...)` — confirm that call site still passes `code` as the first named or positional arg; if it used positional `ExecuteRequest(code, sessionId)`, update it to `ExecuteRequest(code = code, sessionId = sessionId)`).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt \
        app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt \
        app/src/test/java/com/rmobile/console/data/model/ExecFileModelTest.kt
git commit -m "app: ExecFile + files/entryFile on ExecuteRequest + repository.run(files)"
```

---

## Task 3: ProjectStore + SettingsStore persistence

**Files:** Create `data/project/ProjectStore.kt`, `app/src/test/java/com/rmobile/console/data/project/ProjectStoreSerializationTest.kt`; Modify `data/settings/SettingsStore.kt`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/project/ProjectStoreSerializationTest.kt`:
```kotlin
package com.rmobile.console.data.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectStoreSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `projects round-trip through JSON`() {
        val projects = listOf(
            ProjectOps.newProject(1, "A", now = 1),
            ProjectOps.addFile(ProjectOps.newProject(2, "B", now = 2), "helpers.R", now = 3)!!,
        )
        val restored = json.decodeFromString<List<Project>>(json.encodeToString(projects))
        assertEquals(projects, restored)
    }
}
```

- [ ] **Step 2: Run — verify FAIL, then PASS after Step 3**

(This test only needs the models from Task 1, so it should already pass once compiled — run it after Step 3 too. Its purpose is to lock the serialization contract.) Run:
`./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectStoreSerializationTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`

- [ ] **Step 3: Create the ProjectStore interface**

Create `app/src/main/java/com/rmobile/console/data/project/ProjectStore.kt`:
```kotlin
package com.rmobile.console.data.project

/** Persistence boundary for projects + the last-open project id. */
interface ProjectStore {
    fun loadProjects(): List<Project>
    fun persistProjects(projects: List<Project>)
    fun loadLastOpenProjectId(): Long?
    fun persistLastOpenProjectId(id: Long?)
}
```

- [ ] **Step 4: Implement it in SettingsStore**

In `data/settings/SettingsStore.kt`: add imports (`com.rmobile.console.data.project.Project`, `com.rmobile.console.data.project.ProjectStore`), add `ProjectStore` to the class's implemented interfaces, and add:
```kotlin
    override fun loadProjects(): List<Project> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Project>>(raw) }.getOrDefault(emptyList())
    }

    override fun persistProjects(projects: List<Project>) {
        prefs.edit().putString(KEY_PROJECTS, json.encodeToString(projects)).apply()
    }

    override fun loadLastOpenProjectId(): Long? =
        if (prefs.contains(KEY_LAST_PROJECT)) prefs.getLong(KEY_LAST_PROJECT, -1L).takeIf { it >= 0 } else null

    override fun persistLastOpenProjectId(id: Long?) {
        if (id == null) prefs.edit().remove(KEY_LAST_PROJECT).apply()
        else prefs.edit().putLong(KEY_LAST_PROJECT, id).apply()
    }
```
Add the two keys to the companion object alongside the existing `KEY_*`:
```kotlin
        const val KEY_PROJECTS = "projects"
        const val KEY_LAST_PROJECT = "last_project_id"
```
(`json`, `prefs`, and `encodeToString`/`decodeFromString` imports already exist in the file from the saved-scripts work.)

- [ ] **Step 5: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/rmobile/console/data/project/ProjectStore.kt \
        app/src/main/java/com/rmobile/console/data/settings/SettingsStore.kt \
        app/src/test/java/com/rmobile/console/data/project/ProjectStoreSerializationTest.kt
git commit -m "app: ProjectStore + SettingsStore persistence"
```

---

## Task 4: EditorViewModel becomes project-aware

**Files:** Modify `ui/editor/EditorUiState.kt`, `ui/editor/EditorViewModel.kt`, `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`.

- [ ] **Step 1: Update the UI state**

Replace the whole `ui/editor/EditorUiState.kt` with:
```kotlin
package com.rmobile.console.ui.editor

import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.scripts.SavedScript

data class EditorUiState(
    /** The open project. */
    val project: Project = ProjectOps.newProject(id = 0, name = "Untitled", now = 0),
    /** The project library (all projects), most-recently-updated first. */
    val projects: List<Project> = emptyList(),
    /** Active file content — a mirror of the active file in [project] for the editor field. */
    val code: String = ProjectOps.activeContent(project),
    val isRunning: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val plotsBase64: List<String> = emptyList(),
    val errorMessage: String? = null,
    val timedOut: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val savedScripts: List<SavedScript> = emptyList(),
    val workspaceObjects: List<String> = emptyList(),
)
```

- [ ] **Step 2: Rewrite the ViewModel**

Replace the whole `ui/editor/EditorViewModel.kt` with:
```kotlin
package com.rmobile.console.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.history.RunHistory
import com.rmobile.console.data.model.ExecFile
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptLibrary
import com.rmobile.console.data.scripts.SavedScriptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val historyStore: HistoryStore = ServiceLocator.settingsStore,
    private val scriptStore: SavedScriptStore = ServiceLocator.settingsStore,
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState: MutableStateFlow<EditorUiState>
    val uiState: StateFlow<EditorUiState>

    init {
        val loaded = projectStore.loadProjects()
        val projects = loaded.ifEmpty { listOf(ProjectOps.newProject(now(), "Untitled", now())) }
        if (loaded.isEmpty()) projectStore.persistProjects(projects)
        val lastId = projectStore.loadLastOpenProjectId()
        val current = projects.firstOrNull { it.id == lastId } ?: projects.first()
        _uiState = MutableStateFlow(
            EditorUiState(
                project = current,
                projects = projects,
                code = ProjectOps.activeContent(current),
                history = historyStore.load(),
                savedScripts = scriptStore.loadScripts(),
            ),
        )
        uiState = _uiState.asStateFlow()
    }

    // --- editing ---

    fun onCodeChanged(code: String) {
        val updated = ProjectOps.updateActiveContent(_uiState.value.project, code, now())
        persistProject(updated)
        _uiState.update { it.copy(project = updated, code = code) }
    }

    fun switchFile(name: String) {
        val updated = ProjectOps.setActive(_uiState.value.project, name)
        persistProject(updated)
        _uiState.update { it.copy(project = updated, code = ProjectOps.activeContent(updated)) }
    }

    /** Adds a file (empty) and switches to it; sets [errorMessage] on an invalid/duplicate name. */
    fun addFile(name: String) {
        val updated = ProjectOps.addFile(_uiState.value.project, name.trim(), now())
        if (updated == null) {
            _uiState.update { it.copy(errorMessage = "Invalid or duplicate file name.") }
            return
        }
        persistProject(updated)
        _uiState.update { it.copy(project = updated, code = ProjectOps.activeContent(updated), errorMessage = null) }
    }

    fun renameFile(oldName: String, newName: String) {
        val updated = ProjectOps.renameFile(_uiState.value.project, oldName, newName.trim(), now())
        if (updated == null) {
            _uiState.update { it.copy(errorMessage = "Invalid or duplicate file name.") }
            return
        }
        persistProject(updated)
        _uiState.update { it.copy(project = updated, code = ProjectOps.activeContent(updated), errorMessage = null) }
    }

    fun deleteFile(name: String) {
        val updated = ProjectOps.deleteFile(_uiState.value.project, name, now()) ?: return
        persistProject(updated)
        _uiState.update { it.copy(project = updated, code = ProjectOps.activeContent(updated)) }
    }

    fun setEntry(name: String) {
        val updated = ProjectOps.setEntry(_uiState.value.project, name, now())
        persistProject(updated)
        _uiState.update { it.copy(project = updated) }
    }

    // --- projects library ---

    fun openProject(id: Long) {
        val target = _uiState.value.projects.firstOrNull { it.id == id } ?: return
        projectStore.persistLastOpenProjectId(target.id)
        _uiState.update { it.copy(project = target, code = ProjectOps.activeContent(target)) }
    }

    fun newProject(name: String) {
        val created = ProjectOps.newProject(now(), name.trim().ifEmpty { "Untitled" }, now())
        val updated = ProjectOps.upsert(_uiState.value.projects, created)
        projectStore.persistProjects(updated)
        projectStore.persistLastOpenProjectId(created.id)
        _uiState.update { it.copy(projects = updated, project = created, code = ProjectOps.activeContent(created)) }
    }

    fun renameProject(id: Long, name: String) {
        val target = _uiState.value.projects.firstOrNull { it.id == id } ?: return
        val renamed = target.copy(name = name.trim().ifEmpty { target.name }, updatedAt = now())
        val updated = ProjectOps.upsert(_uiState.value.projects, renamed)
        projectStore.persistProjects(updated)
        _uiState.update {
            it.copy(
                projects = updated,
                project = if (it.project.id == id) renamed else it.project,
            )
        }
    }

    /** Deletes a project; if it was open, switches to another (creating a default if none remain). */
    fun deleteProject(id: Long) {
        var remaining = ProjectOps.delete(_uiState.value.projects, id)
        if (remaining.isEmpty()) remaining = listOf(ProjectOps.newProject(now(), "Untitled", now()))
        projectStore.persistProjects(remaining)
        _uiState.update { state ->
            if (state.project.id == id) {
                val next = remaining.first()
                projectStore.persistLastOpenProjectId(next.id)
                state.copy(projects = remaining, project = next, code = ProjectOps.activeContent(next))
            } else {
                state.copy(projects = remaining)
            }
        }
    }

    private fun persistProject(project: Project) {
        val updated = ProjectOps.upsert(_uiState.value.projects, project)
        projectStore.persistProjects(updated)
        projectStore.persistLastOpenProjectId(project.id)
        _uiState.update { it.copy(projects = updated) }
    }

    // --- history / saved scripts (operate on the active file) ---

    fun restoreFromHistory(entry: HistoryEntry) = onCodeChanged(entry.code)

    fun clearHistory() {
        historyStore.persist(emptyList())
        _uiState.update { it.copy(history = emptyList()) }
    }

    fun saveCurrentScript(name: String) {
        val code = _uiState.value.code
        val trimmedName = name.trim()
        if (code.isBlank() || trimmedName.isEmpty()) return
        val timestamp = now()
        val script = SavedScript(id = timestamp, name = trimmedName, code = code, updatedAt = timestamp)
        val updated = SavedScriptLibrary.upsert(_uiState.value.savedScripts, script)
        scriptStore.persistScripts(updated)
        _uiState.update { it.copy(savedScripts = updated) }
    }

    fun loadScript(script: SavedScript) = onCodeChanged(script.code)

    fun deleteScript(id: Long) {
        val updated = SavedScriptLibrary.delete(_uiState.value.savedScripts, id)
        scriptStore.persistScripts(updated)
        _uiState.update { it.copy(savedScripts = updated) }
    }

    // --- session ---

    fun resetSession() {
        viewModelScope.launch {
            repository.reset()
                .onSuccess { _uiState.update { it.copy(workspaceObjects = emptyList()) } }
                .onFailure { t -> _uiState.update { it.copy(errorMessage = t.message ?: "Failed to reset the session.") } }
        }
    }

    // --- run ---

    fun runCode() {
        val project = _uiState.value.project
        val entryContent = project.files.firstOrNull { it.name == project.entryFileName }?.content.orEmpty()
        if (entryContent.isBlank() || _uiState.value.isRunning) return

        _uiState.update { it.copy(isRunning = true, errorMessage = null, timedOut = false) }
        recordHistory(entryContent)

        val files = project.files.map { ExecFile(it.name, it.content) }
        viewModelScope.launch {
            repository.run(files, project.entryFileName)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            stdout = response.stdout,
                            stderr = response.stderr,
                            plotsBase64 = response.plots,
                            errorMessage = response.error,
                            timedOut = response.timedOut,
                            workspaceObjects = response.workspaceObjects ?: it.workspaceObjects,
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(isRunning = false, errorMessage = t.message ?: "Failed to reach the R execution backend.", timedOut = false)
                    }
                }
        }
    }

    private fun recordHistory(code: String) {
        val updated = RunHistory.add(_uiState.value.history, HistoryEntry(code, now()))
        historyStore.persist(updated)
        _uiState.update { it.copy(history = updated) }
    }
}
```

- [ ] **Step 3: Rewrite the ViewModel test**

Replace `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt` with the version below. It adds an in-memory `ProjectStore` and updates run/history/script tests to the project model, plus file/project-op tests.
```kotlin
package com.rmobile.console.ui.editor

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var response: ExecuteResponse = ExecuteResponse(stdout = "ok"),
        var error: Throwable? = null,
        var lastRequest: ExecuteRequest? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            error?.let { throw it }
            return response
        }
        override suspend fun reset(request: ResetRequest): ResetResponse = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest): InstallResponse = InstallResponse(installed = true)
        override suspend fun packages(): PackagesResponse = PackagesResponse()
    }

    private class InMemoryHistoryStore(initial: List<HistoryEntry> = emptyList()) : HistoryStore {
        var stored: List<HistoryEntry> = initial
        override fun load() = stored
        override fun persist(entries: List<HistoryEntry>) { stored = entries }
    }

    private class InMemoryScriptStore(initial: List<SavedScript> = emptyList()) : SavedScriptStore {
        var stored: List<SavedScript> = initial
        override fun loadScripts() = stored
        override fun persistScripts(scripts: List<SavedScript>) { stored = scripts }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList()) : ProjectStore {
        var stored: List<Project> = initial
        var lastId: Long? = null
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private var counter = 100L

    private fun viewModel(
        api: FakeApi = FakeApi(),
        history: InMemoryHistoryStore = InMemoryHistoryStore(),
        scripts: InMemoryScriptStore = InMemoryScriptStore(),
        projects: InMemoryProjectStore = InMemoryProjectStore(),
    ) = EditorViewModel(RExecutionRepository(api), history, scripts, projects, now = { counter++ })

    @Test
    fun `creates a default project when none stored`() {
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals("main.R", vm.uiState.value.project.entryFileName)
        assertEquals(ProjectOps.activeContent(vm.uiState.value.project), vm.uiState.value.code)
    }

    @Test
    fun `editing updates the active file content`() {
        val vm = viewModel()
        vm.onCodeChanged("x <- 1")
        assertEquals("x <- 1", vm.uiState.value.code)
        assertEquals("x <- 1", ProjectOps.activeContent(vm.uiState.value.project))
    }

    @Test
    fun `switching files swaps the edited content`() {
        val vm = viewModel()
        vm.onCodeChanged("main code")
        vm.addFile("helpers.R")
        vm.onCodeChanged("helper code")
        vm.switchFile("main.R")
        assertEquals("main code", vm.uiState.value.code)
        vm.switchFile("helpers.R")
        assertEquals("helper code", vm.uiState.value.code)
    }

    @Test
    fun `run sends all files and the pinned entry even when a non-entry file is active`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "done"))
        val vm = viewModel(api = api)
        vm.onCodeChanged("cat(1)")                 // main.R content (entry)
        vm.addFile("helpers.R")                     // active = helpers.R, entry = main.R
        vm.onCodeChanged("f <- function() 1")       // helpers.R content
        vm.runCode()
        advanceUntilIdle()

        val req = api.lastRequest!!
        assertEquals("main.R", req.entryFile)
        assertEquals(setOf("main.R", "helpers.R"), req.files!!.map { it.name }.toSet())
        assertEquals("done", vm.uiState.value.stdout)
    }

    @Test
    fun `setEntry changes which file runs`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api = api)
        vm.addFile("helpers.R")            // helpers.R is now active (and empty)
        vm.onCodeChanged("cat(2)")         // give the new entry non-blank content (runCode guards blank entries)
        vm.setEntry("helpers.R")
        vm.runCode()
        advanceUntilIdle()
        assertEquals("helpers.R", api.lastRequest!!.entryFile)
    }

    @Test
    fun `deleting the active file falls back to a remaining file`() {
        val vm = viewModel()
        vm.addFile("helpers.R")          // active = helpers.R
        vm.deleteFile("helpers.R")
        assertEquals("main.R", vm.uiState.value.project.activeFileName)
        assertEquals(listOf("main.R"), vm.uiState.value.project.files.map { it.name })
    }

    @Test
    fun `new and open project switch the editor`() {
        val vm = viewModel()
        val firstId = vm.uiState.value.project.id
        vm.onCodeChanged("first project code")
        vm.newProject("Second")
        assertEquals("Second", vm.uiState.value.project.name)
        assertEquals(2, vm.uiState.value.projects.size)
        vm.openProject(firstId)
        assertEquals("first project code", vm.uiState.value.code)
    }

    @Test
    fun `failed run surfaces an error`() = runTest {
        val vm = viewModel(api = FakeApi(error = RuntimeException("boom")))
        vm.onCodeChanged("x")
        vm.runCode()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRunning)
        assertEquals("boom", vm.uiState.value.errorMessage)
    }

    @Test
    fun `run records history of the entry content`() = runTest {
        val history = InMemoryHistoryStore()
        val vm = viewModel(history = history)
        vm.onCodeChanged("cat(42)")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(history.stored.any { it.code == "cat(42)" })
    }

    @Test
    fun `save and load script operate on the active file`() {
        val scripts = InMemoryScriptStore()
        val vm = viewModel(scripts = scripts)
        vm.onCodeChanged("saved code")
        vm.saveCurrentScript("snippet")
        vm.onCodeChanged("other")
        vm.loadScript(scripts.stored.first())
        assertEquals("saved code", vm.uiState.value.code)
    }
}
```

- [ ] **Step 4: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures. (This will fail to COMPILE `EditorScreen.kt` if it references removed symbols — but `code`/`onCodeChanged`/`runCode`/`resetSession`/history/script methods are all preserved, so it should still compile. If a call site breaks, note it; Task 5 revisits the screen anyway.)

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: EditorViewModel becomes project-aware (files, entry, library)"
```

---

## Task 5: Editor UI — file switcher + run label + file dialogs

**Files:** Modify `ui/editor/EditorScreen.kt`. No unit test (Compose); verify by compile.

**Read `EditorScreen.kt` first.** It has an `EditorScreen(onOpenSettings, onOpenPackages, viewModel = viewModel())` composable with: a `TopAppBar` (actions incl. an overflow `DropdownMenu` with "Reset session"/"Packages"), a `Column` containing an `OutlinedTextField` bound to a local `field` synced from `uiState.code` via `LaunchedEffect(uiState.code)` and `viewModel::onCodeChanged`, a `QuickInsertBar`, a Run `Button`, and `OutputPanel`. It already imports `Row`, `LazyRow`? (check), chips, dialogs (`AlertDialog`), `DropdownMenu`/`DropdownMenuItem`.

- [ ] **Step 1: Add a param for opening the project library**

Add `onOpenProjects: () -> Unit = {}` to the `EditorScreen` signature (next to
`onOpenSettings`/`onOpenPackages`). **Give it a default of `{}`** so the current
`MainActivity` call site (not updated until Task 6) still compiles after this
task. Then add a "Projects" item to the overflow `DropdownMenu` (like the
existing "Packages" item):
```kotlin
                        DropdownMenuItem(
                            text = { Text("Projects") },
                            onClick = {
                                menuOpen = false
                                onOpenProjects()
                            },
                        )
```

- [ ] **Step 2: Add file-switcher + dialog state**

Near the other `remember { mutableStateOf(...) }` declarations at the top of `EditorScreen`, add:
```kotlin
    var showAddFile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: Add the FileSwitcher above the editor field**

In the main `Column`, immediately BEFORE the `OutlinedTextField`, insert:
```kotlin
            FileSwitcher(
                files = uiState.project.files.map { it.name },
                activeFile = uiState.project.activeFileName,
                entryFile = uiState.project.entryFileName,
                onSwitch = viewModel::switchFile,
                onAdd = { showAddFile = true },
                onSetEntry = viewModel::setEntry,
                onRename = { renameTarget = it },
                onDelete = viewModel::deleteFile,
            )
```

- [ ] **Step 4: Label the Run button with the entry file**

Find the Run `Button`'s `Text("Run")` and replace it with:
```kotlin
                    Text("Run ${uiState.project.entryFileName}")
```
(Keep the surrounding `if (uiState.isRunning) { CircularProgressIndicator(...) } else { ... }` structure — only the label text changes.)

- [ ] **Step 5: Add the dialogs at the end of the composable**

After the last existing `if (show...) { ... }` block in `EditorScreen`, add:
```kotlin
    if (showAddFile) {
        FileNameDialog(
            title = "New file",
            initial = "",
            onConfirm = {
                viewModel.addFile(it)
                showAddFile = false
            },
            onDismiss = { showAddFile = false },
        )
    }
    renameTarget?.let { target ->
        FileNameDialog(
            title = "Rename $target",
            initial = target,
            onConfirm = {
                viewModel.renameFile(target, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
```

- [ ] **Step 6: Add the FileSwitcher + FileNameDialog composables**

At the end of the file (top-level, e.g. after the last private composable), add:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSwitcher(
    files: List<String>,
    activeFile: String,
    entryFile: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onSetEntry: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files) { name ->
            var menuOpen by remember { mutableStateOf(false) }
            Column {
                FilterChip(
                    selected = name == activeFile,
                    onClick = { onSwitch(name) },
                    label = {
                        Text(if (name == entryFile) "▶ $name" else name, fontFamily = FontFamily.Monospace)
                    },
                    trailingIcon = {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "File menu")
                        }
                    },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Set as entry") }, onClick = { menuOpen = false; onSetEntry(name) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename(name) })
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = files.size > 1,
                        onClick = { menuOpen = false; onDelete(name) },
                    )
                }
            }
        }
        item {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add file")
            }
        }
    }
}

@Composable
private fun FileNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name (e.g. helpers.R)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 7: Add imports**

Add any missing imports used above (some may already be present — do not duplicate): `androidx.compose.foundation.lazy.items`, `androidx.compose.material.icons.filled.Add`, `androidx.compose.material.icons.filled.MoreVert`, `androidx.compose.material3.FilterChip`, `androidx.compose.material3.DropdownMenu`, `androidx.compose.material3.DropdownMenuItem`, `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `androidx.compose.material3.OutlinedTextField`, `androidx.compose.foundation.layout.size`, `androidx.compose.foundation.layout.Column`.

- [ ] **Step 8: Compile**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL. Fix any missing/duplicate imports and re-run.

- [ ] **Step 9: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: editor file switcher, entry badge, run label, file dialogs"
```

---

## Task 6: Project library screen + navigation

**Files:** Create `ui/projects/ProjectsScreen.kt`; Modify `MainActivity.kt`, `ui/editor/EditorScreen.kt` (only if the shared-VM wiring needs it — see below). No unit test (Compose UI shares the tested `EditorViewModel`).

- [ ] **Step 1: Hoist a shared EditorViewModel + add the PROJECTS route**

Replace the body of `MainActivity.kt`'s `AppRoot` (the `Screen` enum + `when`) with a version that hoists one `EditorViewModel` and passes it to both the editor and the projects screen:
```kotlin
private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS }

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    val editorViewModel: EditorViewModel = viewModel()

    when (screen) {
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
            onOpenProjects = { screen = Screen.PROJECTS },
            viewModel = editorViewModel,
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.EDITOR })
        Screen.PACKAGES -> PackagesScreen(onBack = { screen = Screen.EDITOR })
        Screen.PROJECTS -> ProjectsScreen(
            viewModel = editorViewModel,
            onOpenEditor = { screen = Screen.EDITOR },
        )
    }
}
```
Add imports: `androidx.lifecycle.viewmodel.compose.viewModel`, `com.rmobile.console.ui.editor.EditorViewModel`, `com.rmobile.console.ui.projects.ProjectsScreen`. (`EditorScreen`, `SettingsScreen`, `PackagesScreen` imports already exist.)

- [ ] **Step 2: Create the ProjectsScreen**

Create `app/src/main/java/com/rmobile/console/ui/projects/ProjectsScreen.kt`:
```kotlin
package com.rmobile.console.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rmobile.console.data.project.Project
import com.rmobile.console.ui.editor.EditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: EditorViewModel,
    onOpenEditor: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onOpenEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showNew = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New project")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(uiState.projects) { project ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                viewModel.openProject(project.id)
                                onOpenEditor()
                            }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = project.name + if (project.id == uiState.project.id) "  (open)" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${project.files.size} file${if (project.files.size == 1) "" else "s"} · entry ${project.entryFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renameTarget = project }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename project")
                    }
                    IconButton(onClick = { viewModel.deleteProject(project.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete project")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showNew) {
        ProjectNameDialog(
            title = "New project",
            initial = "",
            onConfirm = { viewModel.newProject(it); showNew = false; onOpenEditor() },
            onDismiss = { showNew = false },
        )
    }
    renameTarget?.let { target ->
        ProjectNameDialog(
            title = "Rename project",
            initial = target.name,
            onConfirm = { viewModel.renameProject(target.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun ProjectNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL. (`EditorScreen` now needs the `onOpenProjects` param and a `viewModel` param it's passed — both already added in Task 5 / here. If `EditorScreen`'s `viewModel` parameter default was `viewModel()`, passing an explicit instance is compatible.)

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/projects/ProjectsScreen.kt \
        app/src/main/java/com/rmobile/console/MainActivity.kt
git commit -m "app: project library screen + shared EditorViewModel navigation"
```

---

## Task 7: Full verification + docs

**Files:** Modify `CLAUDE.md`, `README.md`.

- [ ] **Step 1: Full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; 0 test failures; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: README + CLAUDE.md**

- `README.md` "Features": add "**Multi-file projects** — named projects of `.R` files that `source()` each other; a file switcher with a pinned entry, and a project library."
- `CLAUDE.md`: in the app architecture, describe `data/project/` (`Project`/`ProjectFile`/`ProjectOps`/`ProjectStore`), the project-aware `EditorViewModel` (`uiState.code` mirrors the active file; Run sends `files`+`entryFile = entryFileName`), the file switcher, and `ui/projects/ProjectsScreen` sharing the hoisted `EditorViewModel`. Remove "multi-file projects" from the "not built" list.

- [ ] **Step 3: Commit**
```bash
git add README.md CLAUDE.md
git commit -m "docs: document multi-file projects (app)"
```

---

## Done

The editor is project-based: multiple named projects, a file switcher with a pinned entry, and a project library, sending `files`/`entryFile` to the backend. Open a PR from `feat/multi-file-projects-app` (base `claude/r-app-android-version-ztmyd1`).
