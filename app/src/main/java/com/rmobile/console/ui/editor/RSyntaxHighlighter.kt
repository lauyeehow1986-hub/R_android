package com.rmobile.console.ui.editor

/**
 * Minimal R tokenizer for editor syntax highlighting. Pure (no Compose/Android
 * types) so it can be unit-tested. A single ordered regex pass guarantees that,
 * e.g., a keyword inside a comment or string is not separately recolored.
 */
object RSyntaxHighlighter {

    enum class TokenType { COMMENT, STRING, NUMBER, KEYWORD }

    /** A highlighted span: [start, end) into the source and its [type]. */
    data class Token(val start: Int, val end: Int, val type: TokenType)

    private val KEYWORDS = setOf(
        "if", "else", "for", "while", "repeat", "function", "return", "in",
        "next", "break", "TRUE", "FALSE", "NULL", "NA", "Inf", "NaN",
        "library", "require",
    )

    // Order matters: comments and strings are matched before keywords/numbers so
    // their contents are never re-tokenized.
    private val pattern = Regex(
        buildString {
            append("(?<comment>#[^\\n]*)")
            append("|(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
            append("|(?<number>\\b\\d+(?:\\.\\d+)?[Li]?\\b)")
            append("|(?<keyword>\\b(?:${KEYWORDS.joinToString("|")})\\b)")
        },
    )

    fun tokenize(code: String): List<Token> = pattern.findAll(code).mapNotNull { match ->
        val type = when {
            match.groups["comment"] != null -> TokenType.COMMENT
            match.groups["string"] != null -> TokenType.STRING
            match.groups["number"] != null -> TokenType.NUMBER
            match.groups["keyword"] != null -> TokenType.KEYWORD
            else -> null
        }
        type?.let { Token(match.range.first, match.range.last + 1, it) }
    }.toList()
}
