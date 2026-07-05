package com.rmobile.console.ui.editor

import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.scripts.SavedScript

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
    /** User-named saved scripts, most-recently-updated first. */
    val savedScripts: List<SavedScript> = emptyList(),
    /** Names of objects in the backend session's global env; empty when unknown/cleared. */
    val workspaceObjects: List<String> = emptyList(),
)
