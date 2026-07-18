package com.rmobile.console.data.execution

import com.rmobile.console.data.model.RTable

/** One ordered piece of a run's output. */
sealed interface OutputChunk {
    data class Text(val text: String) : OutputChunk
    data class Plot(val base64Png: String) : OutputChunk
    data class Table(val table: RTable) : OutputChunk
}

/** Result of [OutputAssembler.assemble]. */
data class AssembledOutput(
    val cleanStdout: String,
    val chunks: List<OutputChunk>,
    val ordered: Boolean,
)

/**
 * Builds an ordered [OutputChunk] list from a marker-bearing stdout plus the run's
 * ordered plots and tables.
 *
 * PARITY CONTRACT: [PLOT_MARKER] / [TABLE_MARKER] must match, byte-for-byte, the markers
 * emitted by `app/src/main/assets/webr/harness.R` and the backend `plumber.R` wrapper.
 * Change all three together.
 */
object OutputAssembler {
    const val PLOT_MARKER = "RMOBILE:PLOT"
    const val TABLE_MARKER = "RMOBILE:TABLE"

    fun assemble(
        stdoutWithMarkers: String,
        plots: List<String>,
        tables: List<RTable>,
    ): AssembledOutput {
        val cleanStdout = stdoutWithMarkers
            .replace(PLOT_MARKER, "")
            .replace(TABLE_MARKER, "")
        val markersFound = stdoutWithMarkers.contains(PLOT_MARKER) ||
            stdoutWithMarkers.contains(TABLE_MARKER)
        if (!markersFound) return AssembledOutput(cleanStdout, emptyList(), ordered = false)

        val chunks = mutableListOf<OutputChunk>()
        val textBuf = StringBuilder()
        var plotIdx = 0
        var tableIdx = 0
        var overflow = false
        var rest = stdoutWithMarkers

        fun flushText() {
            val t = textBuf.toString()
            if (t.isNotBlank()) chunks.add(OutputChunk.Text(t))
            textBuf.setLength(0)
        }

        while (rest.isNotEmpty()) {
            val pAt = rest.indexOf(PLOT_MARKER)
            val tAt = rest.indexOf(TABLE_MARKER)
            if (pAt < 0 && tAt < 0) { textBuf.append(rest); break }
            val plotFirst = tAt < 0 || (pAt in 0 until tAt)
            if (plotFirst) {
                textBuf.append(rest.substring(0, pAt)); flushText()
                if (plotIdx < plots.size) chunks.add(OutputChunk.Plot(plots[plotIdx])) else overflow = true
                plotIdx++
                rest = rest.substring(pAt + PLOT_MARKER.length)
            } else {
                textBuf.append(rest.substring(0, tAt)); flushText()
                if (tableIdx < tables.size) chunks.add(OutputChunk.Table(tables[tableIdx])) else overflow = true
                tableIdx++
                rest = rest.substring(tAt + TABLE_MARKER.length)
            }
        }
        flushText()

        val ordered = !overflow && plotIdx == plots.size && tableIdx == tables.size
        return AssembledOutput(cleanStdout, chunks, ordered)
    }
}
