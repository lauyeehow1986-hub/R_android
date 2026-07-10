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
import com.rmobile.console.data.project.ProjectArchive
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptLibrary
import com.rmobile.console.data.scripts.SavedScriptStore
import com.rmobile.console.ui.editor.completion.BaseRSymbols
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

    private var symbolIndex: List<String> = emptyList()
    private var packageNames: List<String> = emptyList()

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
        refreshSymbols()
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
        refreshSymbols()
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

    fun deleteProject(id: Long) {
        _uiState.value.projects.firstOrNull { it.id == id }?.let { victim ->
            viewModelScope.launch { repository.reset(ProjectSession.of(victim), purgePackages = true) }
        }
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

    /** Imports a project from a `.zip`'s bytes, adding it to the library and opening it. */
    fun importProject(bytes: ByteArray, fallbackName: String) {
        val imported = ProjectArchive.import(bytes, now(), now(), fallbackName)
        if (imported == null) {
            _uiState.update { it.copy(errorMessage = "Couldn't import — not a valid project zip.") }
            return
        }
        val updated = ProjectOps.upsert(_uiState.value.projects, imported)
        projectStore.persistProjects(updated)
        projectStore.persistLastOpenProjectId(imported.id)
        _uiState.update {
            it.copy(
                projects = updated,
                project = imported,
                code = ProjectOps.activeContent(imported),
                errorMessage = null,
            )
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
        val session = ProjectSession.of(_uiState.value.project)
        viewModelScope.launch {
            repository.reset(session)
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
        val session = ProjectSession.of(project)
        viewModelScope.launch {
            repository.run(files, project.entryFileName, session)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            stdout = response.stdout,
                            stderr = response.stderr,
                            plotsBase64 = response.plots,
                            tables = response.tables,
                            errorMessage = response.error,
                            timedOut = response.timedOut,
                            workspaceObjects = response.workspaceObjects ?: it.workspaceObjects,
                        )
                    }
                    recomputeSymbols()
                    refreshSymbols()
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(isRunning = false, errorMessage = t.message ?: "Failed to reach the R execution backend.", timedOut = false, tables = emptyList())
                    }
                }
        }
    }

    private fun recordHistory(code: String) {
        val updated = RunHistory.add(_uiState.value.history, HistoryEntry(code, now()))
        historyStore.persist(updated)
        _uiState.update { it.copy(history = updated) }
    }

    // --- code assist ---

    private fun recomputeSymbols() {
        val assembled = (BaseRSymbols.NAMES + _uiState.value.workspaceObjects + packageNames + symbolIndex)
            .distinct()
        _uiState.update { it.copy(completionSymbols = assembled) }
    }

    /** Refresh the cached symbol index + package names for the active project's session. */
    fun refreshSymbols() {
        val session = ProjectSession.of(_uiState.value.project)
        viewModelScope.launch {
            symbolIndex = repository.listSymbols(session).getOrNull().orEmpty()
            packageNames = repository.listPackages(session).getOrNull()?.packages.orEmpty()
            recomputeSymbols()
        }
    }

    fun showHelp(topic: String) {
        val t = topic.trim()
        if (t.isEmpty()) return
        val session = ProjectSession.of(_uiState.value.project)
        _uiState.update { it.copy(help = HelpState.Loading(t)) }
        viewModelScope.launch {
            repository.help(t, session)
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(help = if (resp.found) HelpState.Loaded(resp) else HelpState.NotFound(t))
                    }
                }
                .onFailure { th ->
                    _uiState.update { it.copy(help = HelpState.Error(t, th.message ?: "Failed to load help.")) }
                }
        }
    }

    fun dismissHelp() = _uiState.update { it.copy(help = null) }
}
