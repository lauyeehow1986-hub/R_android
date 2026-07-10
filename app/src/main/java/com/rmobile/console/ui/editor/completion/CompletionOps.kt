package com.rmobile.console.ui.editor.completion

/**
 * Editor state a completion is computed against. The MVP only reads the identifier
 * token ending at [cursor], but shaping the input as a context (not a bare prefix)
 * is the seam a future call-stack analyzer extends without changing call sites.
 */
data class CompletionContext(val text: String, val cursor: Int)

/** Pure completion logic — no Compose/Android types, so it is unit-testable. */
object CompletionOps {

    /** The R identifier token ([A-Za-z.][A-Za-z0-9._]*) ending at the cursor, or null. */
    fun tokenRange(ctx: CompletionContext): IntRange? {
        val c = ctx.cursor.coerceIn(0, ctx.text.length)
        var start = c
        while (start > 0 && isIdentChar(ctx.text[start - 1])) start--
        if (start == c) return null
        if (!isIdentStart(ctx.text[start])) return null
        return start until c
    }

    fun currentPrefix(ctx: CompletionContext): String {
        val r = tokenRange(ctx) ?: return ""
        return ctx.text.substring(r.first, r.last + 1)
    }

    /**
     * Case-insensitive prefix matches, ranked case-sensitive-exact-prefix first then
     * alphabetical, de-duplicated and capped at [limit]. Blank prefix -> empty.
     */
    fun suggest(prefix: String, symbols: List<String>, limit: Int): List<String> {
        if (prefix.isBlank()) return emptyList()
        val lower = prefix.lowercase()
        return symbols.asSequence()
            .filter { it.lowercase().startsWith(lower) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.startsWith(prefix) }.thenBy { it })
            .take(limit)
            .toList()
    }

    private fun isIdentStart(ch: Char) = ch.isLetter() || ch == '.'
    private fun isIdentChar(ch: Char) = ch.isLetterOrDigit() || ch == '.' || ch == '_'
}

/** A curated (non-exhaustive) list of common base-R names; GET /symbols enriches it. */
object BaseRSymbols {
    val NAMES: List<String> = listOf(
        "abs", "all", "any", "apply", "as.character", "as.data.frame", "as.factor",
        "as.integer", "as.numeric", "as.vector", "attr", "attributes", "c", "cat",
        "cbind", "ceiling", "class", "colnames", "colSums", "colMeans", "cor", "cumsum",
        "data.frame", "diff", "dim", "dimnames", "do.call", "exp", "factor", "file",
        "filter", "floor", "for", "function", "gsub", "head", "identical", "if", "ifelse",
        "is.na", "is.null", "lapply", "length", "levels", "library", "list", "lm", "log",
        "log10", "ls", "map", "match", "matrix", "max", "mean", "median", "merge", "min",
        "mode", "names", "nchar", "ncol", "nrow", "order", "paste", "paste0", "plot",
        "print", "prod", "quantile", "range", "rbind", "read.csv", "readLines", "readRDS",
        "rep", "require", "return", "rev", "rnorm", "round", "rowSums", "rowMeans",
        "rownames", "sapply", "sd", "seq", "seq_along", "seq_len", "setNames", "setdiff",
        "sort", "split", "sprintf", "sqrt", "str", "strsplit", "sub", "subset", "substr",
        "sum", "summary", "t", "table", "tail", "tapply", "tolower", "toupper", "trimws",
        "unique", "unlist", "vapply", "var", "vector", "which", "while", "write.csv",
        "writeLines", "TRUE", "FALSE", "NULL", "NA", "Inf", "NaN",
    )
}
