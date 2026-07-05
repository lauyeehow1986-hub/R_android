package com.rmobile.console.ui.editor

import com.rmobile.console.data.history.HistoryEntry

data class EditorUiState(
    val code: String = "# Write R code and tap Run\nsummary(cars)\nplot(cars)\n",
    val isRunning: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val plotsBase64: List<String> = emptyList(),
    val errorMessage: String? = null,
    /** True when the last run hit the backend's execution timeout. */
    val timedOut: Boolean = false,
    /** Past runs, newest first. Backed by persistent storage. */
    val history: List<HistoryEntry> = emptyList(),
)
