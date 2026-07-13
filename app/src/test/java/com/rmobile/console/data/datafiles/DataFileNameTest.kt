package com.rmobile.console.data.datafiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataFileNameTest {
    @Test fun `keeps a normal name`() = assertEquals("sales.csv", DataFileName.sanitize("sales.csv"))
    @Test fun `strips directory components`() = assertEquals("a.csv", DataFileName.sanitize("/tmp/x/a.csv"))
    @Test fun `drops disallowed characters`() = assertEquals("ab.csv", DataFileName.sanitize("a b.csv"))
    @Test fun `rejects empty`() = assertNull(DataFileName.sanitize("   "))
    @Test fun `rejects reserved names`() {
        assertNull(DataFileName.sanitize("script.R"))
        assertNull(DataFileName.sanitize("objects.txt"))
        assertNull(DataFileName.sanitize("main.R"))
        assertNull(DataFileName.sanitize("plot001.png"))
        assertNull(DataFileName.sanitize("table012.json"))
    }
}
