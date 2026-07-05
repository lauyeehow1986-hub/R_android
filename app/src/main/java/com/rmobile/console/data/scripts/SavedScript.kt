package com.rmobile.console.data.scripts

import kotlinx.serialization.Serializable

/** A user-named, persisted R script. */
@Serializable
data class SavedScript(
    val id: Long,
    val name: String,
    val code: String,
    val updatedAt: Long,
)

/**
 * Pure list operations for the saved-script library, kept free of Android and
 * persistence so they can be unit-tested. The list is always sorted
 * most-recently-updated first.
 */
object SavedScriptLibrary {

    /** Inserts [script], or replaces an existing one with the same id. */
    fun upsert(existing: List<SavedScript>, script: SavedScript): List<SavedScript> =
        (existing.filterNot { it.id == script.id } + script).sortedByDescending { it.updatedAt }

    fun delete(existing: List<SavedScript>, id: Long): List<SavedScript> =
        existing.filterNot { it.id == id }
}
