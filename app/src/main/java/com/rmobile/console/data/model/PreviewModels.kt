package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PreviewRequest(
    val source: String,          // "file" or "object"
    val name: String,
    val sessionId: String? = null,
)

@Serializable
data class PreviewResponse(
    val table: RTable? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)
