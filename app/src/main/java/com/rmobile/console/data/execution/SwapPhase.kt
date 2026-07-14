package com.rmobile.console.data.execution

/** Progress phases for a Local project (session) swap, surfaced to the UI. */
enum class SwapPhase {
    IDLE,
    SAVING_WORKSPACE,
    LOADING_WORKSPACE,
    RESTORING_LIBRARY,
    RESTORE_FAILED;

    companion object {
        fun fromName(name: String): SwapPhase =
            entries.firstOrNull { it.name == name } ?: IDLE
    }
}
