package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProjectSessionTest {
    private fun project(id: Long, name: String) =
        Project(id = id, name = name, files = emptyList(), activeFileName = "", entryFileName = "", updatedAt = 0)

    private fun project(id: Long, name: String, shared: Boolean) =
        Project(id = id, name = name, files = emptyList(), activeFileName = "",
                entryFileName = "", updatedAt = 0, sharedLibrary = shared)

    @Test fun `derives proj-prefixed id`() {
        assertEquals("proj-42", ProjectSession.of(project(42, "Anything")))
    }

    @Test fun `is stable across a rename`() {
        assertEquals(ProjectSession.of(project(7, "Old")), ProjectSession.of(project(7, "New")))
    }

    @Test fun `distinct ids yield distinct sessions`() {
        assertNotEquals(ProjectSession.of(project(1, "A")), ProjectSession.of(project(2, "A")))
    }

    @Test fun `libraryKey is the project session when not shared`() {
        assertEquals("proj-5", ProjectSession.libraryKey(project(5, "P", false)))
    }

    @Test fun `libraryKey is the shared key when shared`() {
        assertEquals(ProjectSession.SHARED_LIBRARY_KEY,
            ProjectSession.libraryKey(project(5, "P", true)))
    }

    @Test fun `shared key is the literal string shared`() {
        assertEquals("shared", ProjectSession.SHARED_LIBRARY_KEY)
    }
}
