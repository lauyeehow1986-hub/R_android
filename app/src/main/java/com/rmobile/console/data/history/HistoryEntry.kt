package com.rmobile.console.data.history

import kotlinx.serialization.Serializable

/** A single past run: the submitted code and when it was run (epoch millis). */
@Serializable
data class HistoryEntry(
    val code: String,
    val timestampMillis: Long,
)

/**
 * Pure list operations for the run history, kept free of Android/persistence so
 * they can be unit-tested on the JVM. Newest entry is always first.
 */
object RunHistory {
    const val MAX_ENTRIES = 25

    /**
     * Prepends [entry] to [existing], collapsing a consecutive duplicate of the
     * same code (re-running the same snippet just bumps it to the top) and
     * capping the list at [max].
     */
    fun add(existing: List<HistoryEntry>, entry: HistoryEntry, max: Int = MAX_ENTRIES): List<HistoryEntry> {
        val deduped = existing.filterNot { it.code == entry.code }
        return (listOf(entry) + deduped).take(max)
    }
}
