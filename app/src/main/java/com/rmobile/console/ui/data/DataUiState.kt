package com.rmobile.console.ui.data

import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.model.DataFile

data class DataUiState(
    val projectName: String = "",
    val files: List<DataFile> = emptyList(),
    val isLoading: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
    val engineIsLocal: Boolean = false,
    val swapPhase: SwapPhase = SwapPhase.IDLE,
)
