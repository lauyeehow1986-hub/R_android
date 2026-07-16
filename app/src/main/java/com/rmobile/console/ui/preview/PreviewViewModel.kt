package com.rmobile.console.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PreviewViewModel(
    private val defaultEngine: () -> ExecutionEngineChoice = { ServiceLocator.settingsStore.executionEngine },
    private val engineProvider: (ExecutionEngineChoice) -> ExecutionEngine = { ServiceLocator.engineFor(it) },
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val swapProgress: StateFlow<SwapPhase> = ServiceLocator.swapProgress,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private var session: String = RExecutionRepository.DEFAULT_SESSION_ID
    private var engineChoice: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL

    init {
        resolveSession()
        viewModelScope.launch { swapProgress.collect { phase -> _uiState.update { it.copy(swapPhase = phase) } } }
    }

    private fun resolveSession() {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        engineChoice = ExecutionEngineChoice.resolve(active?.engine, defaultEngine())
    }

    /** Loads a preview of [name] (a file or a workspace object per [source]). */
    fun load(source: String, name: String) {
        resolveSession()
        _uiState.value = PreviewUiState(title = name, isLoading = true)
        viewModelScope.launch {
            engineProvider(engineChoice).preview(PreviewRequest(source, name, session))
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            table = resp.table,
                            error = resp.error,
                            truncated = resp.truncated,
                            totalRows = resp.table?.totalRows ?: 0,
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, error = t.message ?: "Preview failed.") }
                }
        }
    }
}
