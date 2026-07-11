package com.rmobile.console.data.settings

import android.content.Context
import com.rmobile.console.BuildConfig
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed persistence for user settings (backend URL, optional
 * API key) and run history. This is the single Android-bound persistence point;
 * pure logic lives in [BaseUrlValidator] and
 * [com.rmobile.console.data.history.RunHistory] so it stays unit-testable.
 */
class SettingsStore(context: Context) : HistoryStore, SavedScriptStore, AppSettings, ProjectStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** The backend base URL, falling back to the build-time default. */
    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.R_EXECUTION_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    /** Optional API key sent as `X-API-Key`; blank means "don't send one". */
    override var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    override var executionEngine: ExecutionEngineChoice
        get() = ExecutionEngineChoice.fromStorage(prefs.getString(KEY_ENGINE, null))
        set(value) = prefs.edit().putString(KEY_ENGINE, value.storageKey).apply()

    override val defaultBaseUrl: String get() = BuildConfig.R_EXECUTION_BASE_URL

    override fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    override fun persist(entries: List<HistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(entries)).apply()
    }

    override fun loadScripts(): List<SavedScript> {
        val raw = prefs.getString(KEY_SCRIPTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedScript>>(raw) }.getOrDefault(emptyList())
    }

    override fun persistScripts(scripts: List<SavedScript>) {
        prefs.edit().putString(KEY_SCRIPTS, json.encodeToString(scripts)).apply()
    }

    override fun loadProjects(): List<Project> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Project>>(raw) }.getOrDefault(emptyList())
    }

    override fun persistProjects(projects: List<Project>) {
        prefs.edit().putString(KEY_PROJECTS, json.encodeToString(projects)).apply()
    }

    override fun loadLastOpenProjectId(): Long? =
        if (prefs.contains(KEY_LAST_PROJECT)) prefs.getLong(KEY_LAST_PROJECT, -1L).takeIf { it >= 0 } else null

    override fun persistLastOpenProjectId(id: Long?) {
        if (id == null) prefs.edit().remove(KEY_LAST_PROJECT).apply()
        else prefs.edit().putLong(KEY_LAST_PROJECT, id).apply()
    }

    private companion object {
        const val PREFS_NAME = "r_mobile_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_ENGINE = "execution_engine"
        const val KEY_HISTORY = "run_history"
        const val KEY_SCRIPTS = "saved_scripts"
        const val KEY_PROJECTS = "projects"
        const val KEY_LAST_PROJECT = "last_project_id"
    }
}
