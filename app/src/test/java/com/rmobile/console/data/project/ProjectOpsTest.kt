package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {

    private fun project() = ProjectOps.newProject(id = 1, name = "P", now = 1)

    @Test
    fun `new project has one entry+active main file`() {
        val p = project()
        assertEquals(listOf("main.R"), p.files.map { it.name })
        assertEquals("main.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
    }

    @Test
    fun `activeContent returns the active file content`() {
        val p = project().copy(files = listOf(ProjectFile("main.R", "x <- 1")), activeFileName = "main.R")
        assertEquals("x <- 1", ProjectOps.activeContent(p))
    }

    @Test
    fun `updateActiveContent replaces only the active file`() {
        val p = ProjectOps.updateActiveContent(project(), "new code", now = 2)
        assertEquals("new code", ProjectOps.activeContent(p))
        assertEquals(2, p.updatedAt)
    }

    @Test
    fun `addFile appends an empty file and makes it active`() {
        val p = ProjectOps.addFile(project(), "helpers.R", now = 2)!!
        assertEquals(listOf("main.R", "helpers.R"), p.files.map { it.name })
        assertEquals("helpers.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
    }

    @Test
    fun `addFile rejects invalid or duplicate names`() {
        assertNull(ProjectOps.addFile(project(), "../evil", now = 2))
        assertNull(ProjectOps.addFile(project(), "main.R", now = 2))
    }

    @Test
    fun `renameFile updates active and entry references`() {
        val p = ProjectOps.renameFile(project(), "main.R", "app.R", now = 2)!!
        assertEquals(listOf("app.R"), p.files.map { it.name })
        assertEquals("app.R", p.activeFileName)
        assertEquals("app.R", p.entryFileName)
    }

    @Test
    fun `deleteFile reassigns active and entry, and rejects the last file`() {
        val two = ProjectOps.addFile(project(), "helpers.R", now = 2)!!
        val withEntry = ProjectOps.setEntry(two, "helpers.R", now = 3)
        val p = ProjectOps.deleteFile(withEntry, "helpers.R", now = 4)!!
        assertEquals(listOf("main.R"), p.files.map { it.name })
        assertEquals("main.R", p.activeFileName)
        assertEquals("main.R", p.entryFileName)
        assertNull(ProjectOps.deleteFile(p, "main.R", now = 5))
    }

    @Test
    fun `setEntry and setActive only accept existing files`() {
        val p = ProjectOps.addFile(project(), "helpers.R", now = 2)!!
        assertEquals("helpers.R", ProjectOps.setEntry(p, "helpers.R", now = 3).entryFileName)
        assertEquals("main.R", ProjectOps.setEntry(p, "ghost.R", now = 3).entryFileName)
        assertEquals("main.R", ProjectOps.setActive(p, "main.R").activeFileName)
    }

    @Test
    fun `project list upsert replaces by id and sorts by updatedAt desc`() {
        val a = ProjectOps.newProject(1, "A", now = 100)
        val b = ProjectOps.newProject(2, "B", now = 200)
        assertEquals(listOf(2L, 1L), ProjectOps.upsert(listOf(a), b).map { it.id })
        val a2 = a.copy(name = "A2", updatedAt = 300)
        val result = ProjectOps.upsert(listOf(a, b), a2)
        assertEquals(2, result.size)
        assertEquals("A2", result.first { it.id == 1L }.name)
    }

    @Test
    fun `project list delete removes by id`() {
        val a = ProjectOps.newProject(1, "A", now = 100)
        val b = ProjectOps.newProject(2, "B", now = 200)
        assertEquals(listOf(2L), ProjectOps.delete(listOf(b, a), id = 1).map { it.id })
    }
}
