package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {

    private fun project() = Project(
        id = 1,
        name = "Demo",
        files = listOf(
            ProjectFile("main.R", "source('helpers.R'); cat(f())"),
            ProjectFile("helpers.R", "f <- function() 1"),
        ),
        activeFileName = "helpers.R",
        entryFileName = "main.R",
        updatedAt = 1,
    )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (n, c) ->
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `export then import round-trips files, entry, active, and name`() {
        val bytes = ProjectArchive.export(project())
        val restored = ProjectArchive.import(bytes, id = 9, now = 99, fallbackName = "ignored")!!
        assertEquals("Demo", restored.name)
        assertEquals(project().files.toSet(), restored.files.toSet())
        assertEquals("main.R", restored.entryFileName)
        assertEquals("helpers.R", restored.activeFileName)
        assertEquals(9L, restored.id)
        assertEquals(99L, restored.updatedAt)
    }

    @Test
    fun `manifest-less zip uses the fallback name and a main entry`() {
        val bytes = zipOf("main.R" to "cat(1)", "util.R" to "x <- 1")
        val p = ProjectArchive.import(bytes, id = 1, now = 1, fallbackName = "Fallback")!!
        assertEquals("Fallback", p.name)
        assertEquals("main.R", p.entryFileName)
        assertEquals("main.R", p.activeFileName)
        assertEquals(setOf("main.R", "util.R"), p.files.map { it.name }.toSet())
    }

    @Test
    fun `an invalid file name is rejected`() {
        assertNull(ProjectArchive.import(zipOf("../evil.R" to "x"), 1, 1, "x"))
    }

    @Test
    fun `garbage bytes return null`() {
        assertNull(ProjectArchive.import(byteArrayOf(1, 2, 3, 4), 1, 1, "x"))
    }

    @Test
    fun `a zip with no files returns null`() {
        assertNull(ProjectArchive.import(zipOf(), 1, 1, "x"))
    }
}
