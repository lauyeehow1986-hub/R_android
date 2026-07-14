package com.rmobile.console.data.execution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyLocalStateMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray) = File(tmp.root, name).writeBytes(bytes)

    @Test fun `renames legacy workspace and library to the session names`() {
        write("webr-workspace.RData", byteArrayOf(1, 2, 3))
        write("webr-library.tar.gz", byteArrayOf(4, 5))

        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")

        assertFalse(File(tmp.root, "webr-workspace.RData").exists())
        assertFalse(File(tmp.root, "webr-library.tar.gz").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(tmp.root, "webr-workspace-proj-7.RData").readBytes())
        assertArrayEquals(byteArrayOf(4, 5), File(tmp.root, "webr-library-proj-7.tar.gz").readBytes())
    }

    @Test fun `no-op when there is no last-open session`() {
        write("webr-workspace.RData", byteArrayOf(1))
        LegacyLocalStateMigration.migrate(tmp.root, null)
        assertTrue(File(tmp.root, "webr-workspace.RData").exists())
    }

    @Test fun `does not overwrite an existing per-session file`() {
        write("webr-workspace.RData", byteArrayOf(9))
        write("webr-workspace-proj-7.RData", byteArrayOf(1, 1)) // already migrated / newer
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")
        // Existing target is preserved; legacy left as-is (not clobbered, not deleted).
        assertArrayEquals(byteArrayOf(1, 1), File(tmp.root, "webr-workspace-proj-7.RData").readBytes())
        assertTrue(File(tmp.root, "webr-workspace.RData").exists())
    }

    @Test fun `idempotent second run is a no-op`() {
        write("webr-library.tar.gz", byteArrayOf(4))
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7")
        LegacyLocalStateMigration.migrate(tmp.root, "proj-7") // legacy already gone
        assertArrayEquals(byteArrayOf(4), File(tmp.root, "webr-library-proj-7.tar.gz").readBytes())
    }
}
