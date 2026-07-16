package com.rmobile.console.ui

import com.rmobile.console.data.execution.SwapPhase

/**
 * Human label for a Local session-swap phase, or null when idle (nothing to show).
 * Shared by every screen that can trigger a project (session) swap, so the
 * "Switching project…" wording stays consistent.
 */
fun swapPhaseLabel(phase: SwapPhase): String? = when (phase) {
    SwapPhase.IDLE -> null
    SwapPhase.SAVING_WORKSPACE -> "Switching project… saving workspace"
    SwapPhase.LOADING_WORKSPACE -> "Switching project… loading workspace"
    SwapPhase.RESTORING_LIBRARY -> "Switching project… restoring packages"
    SwapPhase.RESTORE_FAILED -> "Couldn't load saved workspace — starting empty"
}
