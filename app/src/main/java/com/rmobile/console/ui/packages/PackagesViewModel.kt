package com.rmobile.console.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val engineProvider: (ExecutionEngineChoice) -> ExecutionEngine = { ServiceLocator.engineFor(it) },
    private val swapProgress: StateFlow<SwapPhase> = ServiceLocator.swapProgress,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    private var session: String = RExecutionRepository.DEFAULT_SESSION_ID
    private var engineChoice: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL
    private var activeProject: Project? = null
    private var libraryKey: String = RExecutionRepository.DEFAULT_SESSION_ID

    init {
        resolveContext()
        refresh()
        viewModelScope.launch { swapProgress.collect { phase -> _uiState.update { it.copy(swapPhase = phase) } } }
    }

    /**
     * Re-reads the active project's session and the current engine. Both can
     * change while this ViewModel lives (the engine via Settings, the project via
     * the project library), and this ViewModel outlives navigation, so callers
     * invoke [onShown] each time the Packages screen appears to avoid stale state.
     */
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

    /** Call when the Packages screen becomes visible: re-resolve engine/session and reload the list. */
    fun onShown() {
        resolveContext()
        refresh()
    }

    fun onPackageNameChanged(value: String) {
        _uiState.update { it.copy(packageName = value) }
    }

    /** Reloads the installed-package list; leaves it unchanged on failure. */
    fun refresh() {
        viewModelScope.launch {
            engineProvider(engineChoice).listPackages(session, libraryKey).onSuccess { response ->
                _uiState.update { it.copy(installed = response.packages) }
            }
        }
    }

    fun install() {
        val pkg = _uiState.value.packageName.trim()
        if (pkg.isEmpty() || _uiState.value.installing) return

        _uiState.update { it.copy(installing = true, message = null, log = "", isError = false) }
        viewModelScope.launch {
            engineProvider(engineChoice).install(InstallRequest(pkg, session), libraryKey)
                .onSuccess { response ->
                    val log = listOf(response.stdout, response.stderr)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    val message = if (response.installed) {
                        "Installed $pkg."
                    } else {
                        buildString {
                            append(response.error ?: "$pkg was not installed.")
                            response.systemRequirements?.takeIf { it.isNotBlank() }?.let {
                                append("\nNeeds system packages: ").append(it)
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(installing = false, isError = !response.installed, message = message, log = log)
                    }
                    if (response.installed) refresh()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(installing = false, isError = true, message = throwable.message ?: "Install request failed.")
                    }
                }
        }
    }

    /** Uninstalls a package from the active project's library, then refreshes the list. */
    fun uninstall(packageName: String) {
        viewModelScope.launch {
            engineProvider(engineChoice).uninstall(UninstallRequest(packageName, session), libraryKey)
                .onSuccess { response ->
                    if (response.removed) {
                        _uiState.update { it.copy(isError = false, message = "Removed $packageName.") }
                        refresh()
                    } else {
                        _uiState.update {
                            it.copy(isError = true, message = response.error ?: "$packageName was not removed.")
                        }
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isError = true, message = throwable.message ?: "Uninstall failed.") }
                }
        }
    }

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

    fun importLegacy() {
        viewModelScope.launch {
            repository.importLegacy(session)
                .onSuccess { r ->
                    _uiState.update { it.copy(isError = false, message = "Imported ${r.imported} package(s).") }
                    refresh()
                }
                .onFailure { t -> _uiState.update { it.copy(isError = true, message = t.message ?: "Import failed.") } }
        }
    }
}
