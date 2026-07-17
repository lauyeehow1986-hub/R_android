package com.rmobile.console.ui.packages

import com.rmobile.console.data.execution.SwapPhase

data class PackagesUiState(
    val installed: List<String> = emptyList(),
    val packageName: String = "",
    val installing: Boolean = false,
    val message: String? = null,
    val log: String = "",
    val isError: Boolean = false,
    val projectName: String = "",
    val engineIsLocal: Boolean = false,
    val sharedLibrary: Boolean = false,
    val swapPhase: SwapPhase = SwapPhase.IDLE,
)
