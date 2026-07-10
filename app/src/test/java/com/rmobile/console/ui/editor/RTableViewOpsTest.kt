package com.rmobile.console.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class RTableViewOpsTest {
    private val rows = listOf(listOf("10", "b"), listOf("9", "a"), listOf("100", "c"))
    private val types = listOf("numeric", "character")

    @Test fun `numeric column sorts numerically not lexically`() {
        val out = RTableViewOps.display(rows, types, "", 0, true)
        assertEquals(listOf("9", "10", "100"), out.map { it[0] })
    }

    @Test fun `string column sorts lexically`() {
        val out = RTableViewOps.display(rows, types, "", 1, true)
        assertEquals(listOf("a", "b", "c"), out.map { it[1] })
    }

    @Test fun `descending reverses order`() {
        val out = RTableViewOps.display(rows, types, "", 0, false)
        assertEquals(listOf("100", "10", "9"), out.map { it[0] })
    }

    @Test fun `filter narrows rows case-insensitively`() {
        val out = RTableViewOps.display(rows, types, "B", null, true)
        assertEquals(1, out.size)
        assertEquals("b", out[0][1])
    }

    @Test fun `NA sorts last ascending`() {
        val withNa = rows + listOf(listOf("NA", "z"))
        val out = RTableViewOps.display(withNa, types, "", 0, true)
        assertEquals("NA", out.last()[0])
    }

    @Test fun `null sort column returns filtered order unchanged`() {
        val out = RTableViewOps.display(rows, types, "", null, true)
        assertEquals(rows, out)
    }
}
