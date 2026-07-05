package com.rmobile.console.data.settings

import android.content.Context
import com.rmobile.console.BuildConfig
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed persistence for user settings (backend URL, optional
 * API key) and run history. This is the single Android-bound persistence point;
 * pure logic lives in [BaseUrlValidator] and
 * [com.rmobile.console.data.history.RunHistory] so it stays unit-testable.
 */
class SettingsStore(context: Context) : HistoryStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** The backend base URL, falling back to the build-time default. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.R_EXECUTION_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    /** Optional API key sent as `X-API-Key`; blank means "don't send one". */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    val defaultBaseUrl: String get() = BuildConfig.R_EXECUTION_BASE_URL

    override fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    override fun persist(entries: List<HistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(entries)).apply()
    }

    private companion object {
        const val PREFS_NAME = "r_mobile_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_HISTORY = "run_history"
    }
}
