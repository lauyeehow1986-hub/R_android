package com.rmobile.console.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

class RunHistoryTest {

    @Test
    fun `add prepends newest entry`() {
        val existing = listOf(HistoryEntry("old", 1))
        val result = RunHistory.add(existing, HistoryEntry("new", 2))

        assertEquals(listOf(HistoryEntry("new", 2), HistoryEntry("old", 1)), result)
    }

    @Test
    fun `add collapses duplicate code to the top`() {
        val existing = listOf(HistoryEntry("a", 1), HistoryEntry("b", 2))
        val result = RunHistory.add(existing, HistoryEntry("b", 3))

        assertEquals(listOf(HistoryEntry("b", 3), HistoryEntry("a", 1)), result)
    }

    @Test
    fun `add caps the list at max`() {
        val existing = (1..5).map { HistoryEntry("code$it", it.toLong()) }
        val result = RunHistory.add(existing, HistoryEntry("newest", 99), max = 3)

        assertEquals(3, result.size)
        assertEquals("newest", result.first().code)
        // Keeps the newest `max` by position (prepended entry + first two), drops the tail.
        assertEquals("code2", result.last().code)
    }
}
