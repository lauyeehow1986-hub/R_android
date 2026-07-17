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
    fun `clean stdout has no sentinel bytes`() {
        val r = OutputAssembler.assemble("a${P}b${T}c", listOf("PNG"), listOf(table(1)))
        assertFalse(r.cleanStdout.contains(P))
        assertFalse(r.cleanStdout.contains(T))
        assertEquals("abc", r.cleanStdout)
    }
}
