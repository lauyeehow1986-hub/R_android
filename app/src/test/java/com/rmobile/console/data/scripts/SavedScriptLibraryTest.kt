package com.rmobile.console.data.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedScriptLibraryTest {

    @Test
    fun `upsert adds and keeps most-recent first`() {
        val a = SavedScript(id = 1, name = "a", code = "1", updatedAt = 100)
        val b = SavedScript(id = 2, name = "b", code = "2", updatedAt = 200)

        val result = SavedScriptLibrary.upsert(listOf(a), b)

        assertEquals(listOf(b, a), result)
    }

    @Test
    fun `upsert replaces an entry with the same id`() {
        val original = SavedScript(id = 1, name = "a", code = "old", updatedAt = 100)
        val edited = SavedScript(id = 1, name = "a", code = "new", updatedAt = 300)

        val result = SavedScriptLibrary.upsert(listOf(original), edited)

        assertEquals(1, result.size)
        assertEquals("new", result.single().code)
    }

    @Test
    fun `delete removes by id`() {
        val a = SavedScript(id = 1, name = "a", code = "1", updatedAt = 100)
        val b = SavedScript(id = 2, name = "b", code = "2", updatedAt = 200)

        assertEquals(listOf(b), SavedScriptLibrary.delete(listOf(b, a), id = 1))
    }
}
