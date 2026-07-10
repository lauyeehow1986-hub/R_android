package com.rmobile.console.ui.editor

import com.rmobile.console.data.model.HelpResponse

/** UI state for the R-help bottom sheet. A single frame (see spec's back-stack seam). */
sealed interface HelpState {
    data class Loading(val topic: String) : HelpState
    data class Loaded(val response: HelpResponse) : HelpState
    data class NotFound(val topic: String) : HelpState
    data class Error(val topic: String, val message: String) : HelpState
}
