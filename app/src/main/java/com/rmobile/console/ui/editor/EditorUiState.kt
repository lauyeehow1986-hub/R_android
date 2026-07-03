package com.rmobile.console.ui.editor

data class EditorUiState(
    val code: String = "# Write R code and tap Run\nsummary(cars)\nplot(cars)\n",
    val isRunning: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val plotsBase64: List<String> = emptyList(),
    val errorMessage: String? = null,
)
