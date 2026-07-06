package com.rmobile.console.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun onPackageNameChanged(value: String) {
        _uiState.update { it.copy(packageName = value) }
    }

    /** Reloads the installed-package list; leaves it unchanged on failure. */
    fun refresh() {
        viewModelScope.launch {
            repository.listPackages().onSuccess { response ->
                _uiState.update { it.copy(installed = response.packages) }
            }
        }
    }

    fun install() {
        val pkg = _uiState.value.packageName.trim()
        if (pkg.isEmpty() || _uiState.value.installing) return

        _uiState.update { it.copy(installing = true, message = null, log = "", isError = false) }
        viewModelScope.launch {
            repository.install(pkg)
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

    /** Uninstalls a package from the shared library, then refreshes the list. */
    fun uninstall(packageName: String) {
        viewModelScope.launch {
            repository.uninstall(packageName)
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
}
