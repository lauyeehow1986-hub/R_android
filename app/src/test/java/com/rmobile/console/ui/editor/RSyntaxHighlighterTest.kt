package com.rmobile.console.ui.editor

import com.rmobile.console.ui.editor.RSyntaxHighlighter.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RSyntaxHighlighterTest {

    private fun typesIn(code: String) = RSyntaxHighlighter.tokenize(code).map { it.type }

    @Test
    fun `classifies comments strings numbers and keywords`() {
        val tokens = RSyntaxHighlighter.tokenize("x <- 42 # note")
        assertTrue(tokens.any { it.type == TokenType.NUMBER })
        assertTrue(tokens.any { it.type == TokenType.COMMENT })

        assertTrue(typesIn("if (TRUE) 1").contains(TokenType.KEYWORD))
        assertTrue(typesIn("s <- \"hello\"").contains(TokenType.STRING))
    }

    @Test
    fun `does not tokenize keywords inside a comment`() {
        val tokens = RSyntaxHighlighter.tokenize("# if for while function")
        assertEquals(listOf(TokenType.COMMENT), tokens.map { it.type })
    }

    @Test
    fun `does not tokenize keywords inside a string`() {
        val tokens = RSyntaxHighlighter.tokenize("x <- \"if for 42\"")
        assertEquals(listOf(TokenType.STRING), tokens.map { it.type })
    }

    @Test
    fun `token ranges map back to the source text`() {
        val code = "n <- 100"
        val number = RSyntaxHighlighter.tokenize(code).single { it.type == TokenType.NUMBER }
        assertEquals("100", code.substring(number.start, number.end))
    }
}
