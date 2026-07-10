package com.rmobile.console.ui.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeTest {
    @Test fun `bytes under 1 KB`() = assertEquals("512 B", formatByteSize(512))
    @Test fun `zero bytes`() = assertEquals("0 B", formatByteSize(0))
    @Test fun `exact kilobyte has no decimal`() = assertEquals("1 KB", formatByteSize(1024))
    @Test fun `kilobytes with one decimal`() = assertEquals("1.5 KB", formatByteSize(1536))
    @Test fun `exact megabyte`() = assertEquals("1 MB", formatByteSize(1024L * 1024))
    @Test fun `exact gigabyte`() = assertEquals("1 GB", formatByteSize(1024L * 1024 * 1024))
    @Test fun `negative is clamped`() = assertEquals("0 B", formatByteSize(-5))
}
