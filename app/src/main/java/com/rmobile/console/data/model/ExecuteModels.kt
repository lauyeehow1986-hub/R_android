package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteRequest(
    val code: String,
)

@Serializable
data class ExecuteResponse(
    val stdout: String = "",
    val stderr: String = "",
    /** Base64-encoded PNG images, one per plot device page produced by the script. */
    val plots: List<String> = emptyList(),
    val error: String? = null,
    val timedOut: Boolean = false,
)
