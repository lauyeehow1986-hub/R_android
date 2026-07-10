package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecFile(
    val name: String,
    val content: String,
)

@Serializable
data class ExecuteRequest(
    val code: String? = null,
    val sessionId: String? = null,
    val files: List<ExecFile>? = null,
    val entryFile: String? = null,
)

@Serializable
data class RTable(
    val columns: List<String> = emptyList(),
    val columnTypes: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val totalRows: Int = 0,
)

@Serializable
data class ExecuteResponse(
    val stdout: String = "",
    val stderr: String = "",
    /** Base64-encoded PNG images, one per plot device page produced by the script. */
    val plots: List<String> = emptyList(),
    /** Data frames / matrices printed at the script's top level, as tables. */
    val tables: List<RTable> = emptyList(),
    val error: String? = null,
    val timedOut: Boolean = false,
    /** Global-env object names after a successful run; null when the run errored (state unchanged). */
    val workspaceObjects: List<String>? = null,
)

@Serializable
data class ResetRequest(
    val sessionId: String? = null,
    val purgePackages: Boolean = false,
)

@Serializable
data class ResetResponse(
    val ok: Boolean = false,
)
