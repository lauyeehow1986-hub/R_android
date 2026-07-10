package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SymbolsResponse(
    val symbols: List<String> = emptyList(),
)

@Serializable
data class HelpRequest(
    val topic: String,
    val sessionId: String? = null,
)

@Serializable
data class HelpResponse(
    val topic: String = "",
    val packageName: String? = null,
    val text: String = "",
    val found: Boolean = false,
)
