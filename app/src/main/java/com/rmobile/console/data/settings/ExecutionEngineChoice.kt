package com.rmobile.console.data.settings

/** Which engine runs R. Local (on-device WebR) is the privacy-preserving default. */
enum class ExecutionEngineChoice(val storageKey: String) {
    LOCAL("local"),
    REMOTE("remote");

    companion object {
        fun fromStorage(value: String?): ExecutionEngineChoice =
            entries.firstOrNull { it.storageKey == value } ?: LOCAL
    }
}
