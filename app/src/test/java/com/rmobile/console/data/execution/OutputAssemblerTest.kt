package com.rmobile.console.data.execution

import com.rmobile.console.data.model.RTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAssemblerTest {
    private val P = OutputAssembler.PLOT_MARKER
    private val T = OutputAssembler.TABLE_MARKER
    private fun table(n: Int) = RTable(columns = listOf("c"), columnTypes = listOf("int"), rows = emptyList(), totalRows = n)

    @Test
    fun `plot then text (praise case) orders plot first`() {
        val r = OutputAssembler.assemble("${P}Everything is A-OK!\n", listOf("PNG"), emptyList())
        assertTrue(r.ordered)
        assertEquals("Everything is A-OK!\n", r.cleanStdout)
        assertEquals(2, r.chunks.size)
        assertEquals(OutputChunk.Plot("PNG"), r.chunks[0])
        assertEquals(OutputChunk.Text("Everything is A-OK!\n"), r.chunks[1])
    }

    @Test
    fun `text then plot keeps text first`() {
        val r = OutputAssembler.assemble("hello\n$P", listOf("PNG"), emptyList())
        assertTrue(r.ordered)
        assertEquals(listOf(OutputChunk.Text("hello\n"), OutputChunk.Plot("PNG")), r.chunks)
    }

    @Test
    fun `table interleaves between text`() {
        val tbl = table(3)
        val r = OutputAssembler.assemble("${P}mid$T", listOf("PNG"), listOf(tbl))
        assertTrue(r.ordered)
        assertEquals(listOf(OutputChunk.Plot("PNG"), OutputChunk.Text("mid"), OutputChunk.Table(tbl)), r.chunks)
    }

    @Test
    fun `no markers falls back and preserves stdout`() {
        val r = OutputAssembler.assemble("just text", listOf("PNG"), emptyList())
        assertFalse(r.ordered)
        assertEquals("just text", r.cleanStdout)
        assertTrue(r.chunks.isEmpty())
    }

    @Test
    fun `count mismatch falls back but still strips markers`() {
        val r = OutputAssembler.assemble("$P$P", listOf("PNG"), emptyList())
        assertFalse(r.ordered)
        assertEquals("", r.cleanStdout)
    }

    @Test
    fun `markers are wrapped in STX and ETX control chars`() {
        assertEquals("\u0002RMOBILE:PLOT\u0003", OutputAssembler.PLOT_MARKER)
        assertEquals("\u0002RMOBILE:TABLE\u0003", OutputAssembler.TABLE_MARKER)
    }

    @Test
    fun `clean stdout has no sentinel control bytes`() {
        val r = OutputAssembler.assemble("a${P}b${T}c", listOf("PNG"), listOf(table(1)))
        val stx = ""
        val etx = ""
        assertFalse(r.cleanStdout.contains(stx))
        assertFalse(r.cleanStdout.contains(etx))
        assertEquals("abc", r.cleanStdout)
    }

    @Test
    fun `two plots with matching count are ordered`() {
        val r = OutputAssembler.assemble("${P}a${P}b", listOf("P1", "P2"), emptyList())
        assertTrue(r.ordered)
        assertEquals(
            listOf(OutputChunk.Plot("P1"), OutputChunk.Text("a"), OutputChunk.Plot("P2"), OutputChunk.Text("b")),
            r.chunks,
        )
    }

    @Test
    fun `table overflow falls back`() {
        val r = OutputAssembler.assemble("$T$T", emptyList(), listOf(table(1)))
        assertFalse(r.ordered)
    }
}
