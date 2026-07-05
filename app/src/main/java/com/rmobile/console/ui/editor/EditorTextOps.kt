package com.rmobile.console.ui.editor

/** Result of a text insertion: the new text and where the caret should land. */
data class InsertResult(val text: String, val cursor: Int)

/**
 * Inserts [insert] into [text], replacing the current selection
 * `[selStart, selEnd)`, and returns the new text plus caret position. [caret] is
 * where the caret lands relative to the start of the inserted string (e.g. `1`
 * to sit between the parens of `()`). Pure so it can be unit-tested without
 * Compose.
 */
fun insertAt(
    text: String,
    selStart: Int,
    selEnd: Int,
    insert: String,
    caret: Int = insert.length,
): InsertResult {
    val start = minOf(selStart, selEnd).coerceIn(0, text.length)
    val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val newText = text.replaceRange(start, end, insert)
    return InsertResult(newText, start + caret.coerceIn(0, insert.length))
}
