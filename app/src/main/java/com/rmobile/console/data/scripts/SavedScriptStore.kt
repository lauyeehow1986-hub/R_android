package com.rmobile.console.data.scripts

/**
 * Persistence boundary for saved scripts. An interface so [EditorViewModel] can
 * be unit-tested with an in-memory fake instead of Android SharedPreferences.
 */
interface SavedScriptStore {
    fun loadScripts(): List<SavedScript>
    fun persistScripts(scripts: List<SavedScript>)
}
