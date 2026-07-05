package com.rmobile.console.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Theme-supplied colors for each highlighted token kind. */
data class RSyntaxColors(
    val comment: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
)

/**
 * Colorizes R source in an editor field. Highlighting only adds [SpanStyle]s —
 * it never changes the text — so the offset mapping is the identity.
 */
class RCodeVisualTransformation(private val colors: RSyntaxColors) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = AnnotatedString.Builder(text.text).apply {
            RSyntaxHighlighter.tokenize(text.text).forEach { token ->
                addStyle(SpanStyle(color = colorFor(token.type)), token.start, token.end)
            }
        }.toAnnotatedString()

        return TransformedText(annotated, OffsetMapping.Identity)
    }

    private fun colorFor(type: RSyntaxHighlighter.TokenType): Color = when (type) {
        RSyntaxHighlighter.TokenType.COMMENT -> colors.comment
        RSyntaxHighlighter.TokenType.STRING -> colors.string
        RSyntaxHighlighter.TokenType.NUMBER -> colors.number
        RSyntaxHighlighter.TokenType.KEYWORD -> colors.keyword
    }
}
