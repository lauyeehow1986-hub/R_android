package com.rmobile.console.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotNamingTest {
    @Test fun `sanitize keeps allowed chars and blanks fall back to default`() {
        assertEquals("proj-5", SnapshotNaming.sanitize("proj-5"))
        assertEquals("A_b-9", SnapshotNaming.sanitize("A_b-9"))
        assertEquals("default", SnapshotNaming.sanitize(""))
        assertEquals("default", SnapshotNaming.sanitize("///"))
    }

    @Test fun `sanitize strips disallowed characters`() {
        assertEquals("projX1", SnapshotNaming.sanitize("proj.X!1"))
    }

    @Test fun `fileName builds per-kind extensions`() {
        assertEquals("webr-workspace-proj-5.RData", SnapshotNaming.fileName("workspace", "proj-5"))
        assertEquals("webr-library-proj-5.tar.gz", SnapshotNaming.fileName("library", "proj-5"))
    }

    @Test fun `fileName sanitizes the session id`() {
        assertEquals("webr-workspace-projX5.RData", SnapshotNaming.fileName("workspace", "proj.X5"))
    }

    @Test fun `fileName rejects an unknown kind`() {
        assertThrows(IllegalStateException::class.java) { SnapshotNaming.fileName("bogus", "proj-5") }
    }
}
