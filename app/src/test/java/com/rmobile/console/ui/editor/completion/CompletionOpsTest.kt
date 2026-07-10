package com.rmobile.console.ui.editor.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionOpsTest {

    @Test
    fun `tokenRange finds the identifier ending at the cursor`() {
        val ctx = CompletionContext("x <- mea", 8) // cursor at end of "mea"
        assertEquals(5 until 8, CompletionOps.tokenRange(ctx))
        assertEquals("mea", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `tokenRange handles dotted names`() {
        val ctx = CompletionContext("read.cs", 7)
        assertEquals("read.cs", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `tokenRange is null at a boundary`() {
        assertNull(CompletionOps.tokenRange(CompletionContext("mean(", 5)))
        assertNull(CompletionOps.tokenRange(CompletionContext("a + ", 4)))
    }

    @Test
    fun `tokenRange ignores text after the cursor`() {
        val ctx = CompletionContext("means", 3) // cursor after "mea"
        assertEquals("mea", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `suggest ranks exact-prefix before alphabetical and is case-insensitive`() {
        val symbols = listOf("median", "Mean", "mean", "meanX", "sum")
        val out = CompletionOps.suggest("mea", symbols, limit = 10)
        assertEquals("mean", out[0])
        assertTrue(out.contains("Mean"))
        assertTrue(out.indexOf("mean") < out.indexOf("Mean"))
    }

    @Test
    fun `suggest caps, dedups, and empty prefix yields nothing`() {
        val symbols = listOf("aa", "aa", "ab", "ac", "ad")
        assertEquals(listOf("aa", "ab"), CompletionOps.suggest("a", symbols, limit = 2))
        assertTrue(CompletionOps.suggest("", symbols, limit = 5).isEmpty())
        assertTrue(CompletionOps.suggest("   ", symbols, limit = 5).isEmpty())
    }

    @Test
    fun `BaseRSymbols contains common names`() {
        assertTrue(BaseRSymbols.NAMES.contains("mean"))
        assertTrue(BaseRSymbols.NAMES.contains("data.frame"))
        assertTrue(BaseRSymbols.NAMES.contains("library"))
    }
}
