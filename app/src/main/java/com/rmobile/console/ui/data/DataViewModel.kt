package com.rmobile.console.ui.data

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
import okhttp3.MultipartBody

class DataViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataUiState())
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()

    private val session: String

    init {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        _uiState.value = _uiState.value.copy(projectName = active?.name ?: "")
        refresh()
    }

    /** Reloads the file list; leaves it unchanged on failure. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.listDataFiles(session)
                .onSuccess { files -> _uiState.update { it.copy(files = files, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.message ?: "Failed to load files.") } }
        }
    }

    fun upload(part: MultipartBody.Part) {
        if (_uiState.value.uploading) return
        _uiState.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            repository.uploadFile(session, part)
                .onSuccess {
                    _uiState.update { it.copy(uploading = false) }
                    refresh()
                }
                .onFailure { t -> _uiState.update { it.copy(uploading = false, error = t.message ?: "Upload failed.") } }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            repository.deleteDataFile(name, session)
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
