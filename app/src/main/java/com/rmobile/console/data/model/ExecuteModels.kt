package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteRequest(
    val code: String,
    val sessionId: String? = null,
)

@Serializable
data class ExecuteResponse(
    val stdout: String = "",
    val stderr: String = "",
    /** Base64-encoded PNG images, one per plot device page produced by the script. */
    val plots: List<String> = emptyList(),
    val error: String? = null,
    val timedOut: Boolean = false,
    /** Global-env object names after a successful run; null when the run errored (state unchanged). */
    val workspaceObjects: List<String>? = null,
)

@Serializable
data class ResetRequest(
    val sessionId: String? = null,
)

@Serializable
data class ResetResponse(
    val ok: Boolean = false,
)
