package com.rmobile.console.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.history.RunHistory
import com.rmobile.console.data.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val historyStore: HistoryStore = ServiceLocator.settingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState(history = historyStore.load()))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun onCodeChanged(code: String) {
        _uiState.update { it.copy(code = code) }
    }

    /** Load a snippet from history back into the editor. */
    fun restoreFromHistory(entry: HistoryEntry) {
        _uiState.update { it.copy(code = entry.code) }
    }

    fun clearHistory() {
        historyStore.persist(emptyList())
        _uiState.update { it.copy(history = emptyList()) }
    }

    fun runCode() {
        val code = _uiState.value.code
        if (code.isBlank() || _uiState.value.isRunning) return

        _uiState.update { it.copy(isRunning = true, errorMessage = null, timedOut = false) }
        recordHistory(code)

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
                            timedOut = response.timedOut,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = throwable.message ?: "Failed to reach the R execution backend.",
                            timedOut = false,
                        )
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
