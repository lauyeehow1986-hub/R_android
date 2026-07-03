package com.rmobile.console.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun onCodeChanged(code: String) {
        _uiState.update { it.copy(code = code) }
    }

    fun runCode() {
        val code = _uiState.value.code
        if (code.isBlank() || _uiState.value.isRunning) return

        _uiState.update { it.copy(isRunning = true, errorMessage = null) }
        viewModelScope.launch {
            repository.run(code)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            stdout = response.stdout,
                            stderr = response.stderr,
                            plotsBase64 = response.plots,
                            errorMessage = response.error,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = throwable.message ?: "Failed to reach the R execution backend.",
                        )
                    }
                }
        }
    }
}
