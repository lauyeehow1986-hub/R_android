package com.rmobile.console.data.history

/**
 * Persistence boundary for run history. An interface so the [EditorViewModel]
 * can be unit-tested with an in-memory fake instead of Android SharedPreferences.
 */
interface HistoryStore {
    fun load(): List<HistoryEntry>
    fun persist(entries: List<HistoryEntry>)
}
