package com.rmobile.console.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorTextOpsTest {

    @Test
    fun `inserts at a collapsed cursor and advances past the token`() {
        val result = insertAt("xy", selStart = 1, selEnd = 1, insert = "<- ")

        assertEquals("x<- y", result.text)
        assertEquals(4, result.cursor)
    }

    @Test
    fun `caret offset lands inside a pair`() {
        val result = insertAt("", selStart = 0, selEnd = 0, insert = "()", caret = 1)

        assertEquals("()", result.text)
        assertEquals(1, result.cursor)
    }

    @Test
    fun `replaces the current selection`() {
        val result = insertAt("abcd", selStart = 1, selEnd = 3, insert = "X")

        assertEquals("aXd", result.text)
        assertEquals(2, result.cursor)
    }

    @Test
    fun `handles a reversed selection`() {
        val result = insertAt("abcd", selStart = 3, selEnd = 1, insert = "X")

        assertEquals("aXd", result.text)
        assertEquals(2, result.cursor)
    }

    @Test
    fun `clamps out-of-range selection indices`() {
        val result = insertAt("ab", selStart = -5, selEnd = 99, insert = "Z")

        assertEquals("Z", result.text)
        assertEquals(1, result.cursor)
    }
}
