package com.rmobile.console.ui.editor

/** Pure filter + type-aware sort over a table's delivered rows. */
object RTableViewOps {
    val NUMERIC_TYPES = setOf("numeric", "integer", "double", "complex")

    fun display(
        rows: List<List<String>>,
        columnTypes: List<String>,
        filter: String,
        sortColumn: Int?,
        ascending: Boolean,
    ): List<List<String>> {
        val filtered =
            if (filter.isBlank()) rows
            else rows.filter { row -> row.any { it.contains(filter, ignoreCase = true) } }

        if (sortColumn == null || sortColumn !in columnTypes.indices) return filtered

        val numeric = columnTypes[sortColumn] in NUMERIC_TYPES
        val sorted = filtered.sortedWith { a, b ->
            compareCells(a.getOrElse(sortColumn) { "" }, b.getOrElse(sortColumn) { "" }, numeric)
        }
        return if (ascending) sorted else sorted.reversed()
    }

    private fun compareCells(a: String, b: String, numeric: Boolean): Int {
        val aEmpty = a.isBlank() || a == "NA"
        val bEmpty = b.isBlank() || b == "NA"
        if (aEmpty && bEmpty) return 0
        if (aEmpty) return 1   // NA / blank sort last (ascending)
        if (bEmpty) return -1
        if (numeric) {
            val an = a.toDoubleOrNull(); val bn = b.toDoubleOrNull()
            if (an != null && bn != null) return an.compareTo(bn)
        }
        return a.compareTo(b, ignoreCase = true)
    }
}
