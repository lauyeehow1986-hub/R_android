package com.rmobile.console.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.settings.AppSettings
import com.rmobile.console.data.settings.BaseUrlValidator
import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result of a "Test connection" attempt. */
sealed interface ConnectionTest {
    data object None : ConnectionTest
    data object Testing : ConnectionTest
    data class Ok(val message: String) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val defaultBaseUrl: String = "",
    val urlError: String? = null,
    val saved: Boolean = false,
    val connectionTest: ConnectionTest = ConnectionTest.None,
    val executionEngine: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL,
)

class SettingsViewModel(
    private val store: AppSettings = ServiceLocator.settingsStore,
    private val probeHealth: suspend (baseUrl: String, apiKey: String) -> Result<Unit> =
        NetworkModule::probeHealth,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrl = store.baseUrl,
            apiKey = store.apiKey,
            defaultBaseUrl = store.defaultBaseUrl,
            executionEngine = store.executionEngine,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onBaseUrlChanged(value: String) {
        _uiState.update { it.copy(baseUrl = value, urlError = null, saved = false, connectionTest = ConnectionTest.None) }
    }

    fun onApiKeyChanged(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, connectionTest = ConnectionTest.None) }
    }

    fun setEngine(choice: ExecutionEngineChoice) {
        store.executionEngine = choice
        _uiState.update { it.copy(executionEngine = choice) }
    }

    fun resetToDefault() {
        _uiState.update {
            it.copy(baseUrl = store.defaultBaseUrl, urlError = null, saved = false, connectionTest = ConnectionTest.None)
        }
    }

    /** Validates and persists settings, applying them to the network layer. */
    fun save() {
        val current = _uiState.value
        val normalized = BaseUrlValidator.normalize(current.baseUrl)
        if (normalized == null) {
            _uiState.update { it.copy(urlError = "Enter a valid http(s) URL, e.g. http://192.168.1.5:8000/") }
            return
        }

        val apiKey = current.apiKey.trim()
        store.baseUrl = normalized
        store.apiKey = apiKey
        NetworkModule.updateConfig(normalized, apiKey)

        _uiState.update { it.copy(baseUrl = normalized, apiKey = apiKey, urlError = null, saved = true) }
    }

    /** Pings the entered backend's /health without persisting anything. */
    fun testConnection() {
        val current = _uiState.value
        val normalized = BaseUrlValidator.normalize(current.baseUrl)
        if (normalized == null) {
            _uiState.update { it.copy(urlError = "Enter a valid http(s) URL, e.g. http://192.168.1.5:8000/") }
            return
        }

        _uiState.update { it.copy(connectionTest = ConnectionTest.Testing) }
        viewModelScope.launch {
            val result = probeHealth(normalized, current.apiKey.trim())
            _uiState.update {
                it.copy(
                    connectionTest = result.fold(
                        onSuccess = { ConnectionTest.Ok("Backend reachable.") },
                        onFailure = { t -> ConnectionTest.Failed(t.message ?: "Could not reach the backend.") },
                    ),
                )
            }
        }
    }
}
