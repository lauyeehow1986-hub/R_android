package com.rmobile.console.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.datafiles.DataUpload
import com.rmobile.console.data.datafiles.SessionDataStore
import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DataViewModel(
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val dataStoreProvider: (ExecutionEngineChoice) -> SessionDataStore = { ServiceLocator.dataStoreFor(it) },
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val swapProgress: StateFlow<SwapPhase> = ServiceLocator.swapProgress,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataUiState())
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()
    private var session: String = RExecutionRepository.DEFAULT_SESSION_ID
    private var engineChoice: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL

    init {
        resolveContext(); refresh()
        viewModelScope.launch { swapProgress.collect { phase -> _uiState.update { it.copy(swapPhase = phase) } } }
    }

    private fun resolveContext() {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() } ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        engineChoice = ExecutionEngineChoice.resolve(active?.engine, defaultEngine())
        _uiState.update { it.copy(projectName = active?.name ?: "", engineIsLocal = engineChoice == ExecutionEngineChoice.LOCAL) }
    }

    /** Re-resolve engine/session and reload when the screen appears. */
    fun onShown() { resolveContext(); refresh() }

    /** True if a file this big risks OOM on the in-memory Local engine. */
    fun warnLargeFile(size: Long): Boolean = engineChoice == ExecutionEngineChoice.LOCAL && size > 200L * 1024 * 1024

    /** Reloads the file list; leaves it unchanged on failure. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            dataStoreProvider(engineChoice).list(session)
                .onSuccess { files -> _uiState.update { it.copy(files = files, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.message ?: "Failed to load files.") } }
        }
    }

    fun upload(upload: DataUpload) {
        if (_uiState.value.uploading) return
        _uiState.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            dataStoreProvider(engineChoice).save(session, upload)
                .onSuccess {
                    _uiState.update { it.copy(uploading = false) }
                    refresh()
                }
                .onFailure { t -> _uiState.update { it.copy(uploading = false, error = t.message ?: "Upload failed.") } }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            dataStoreProvider(engineChoice).delete(session, name)
                .onSuccess { removed ->
                    if (removed) refresh()
                    else _uiState.update { it.copy(error = "$name was not deleted.") }
                }
                .onFailure { t -> _uiState.update { it.copy(error = t.message ?: "Delete failed.") } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
