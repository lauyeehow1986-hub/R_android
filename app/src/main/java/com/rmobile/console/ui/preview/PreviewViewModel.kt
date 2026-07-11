package com.rmobile.console.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PreviewViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val session: String

    init {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
    }

    /** Loads a preview of [name] (a file or a workspace object per [source]). */
    fun load(source: String, name: String) {
        _uiState.value = PreviewUiState(title = name, isLoading = true)
        viewModelScope.launch {
            repository.preview(source, name, session)
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
