package com.rmobile.console.data.settings

/**
 * The mutable user-settings surface the Settings screen needs. An interface so
 * `SettingsViewModel` can be unit-tested without Android SharedPreferences;
 * [SettingsStore] is the production implementation.
 */
interface AppSettings {
    var baseUrl: String
    var apiKey: String
    var executionEngine: ExecutionEngineChoice
    val defaultBaseUrl: String
}
