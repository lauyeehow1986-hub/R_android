package com.rmobile.console.ui.preview

import com.rmobile.console.data.model.RTable

data class PreviewUiState(
    val title: String = "",
    val table: RTable? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    val totalRows: Int = 0,
)
