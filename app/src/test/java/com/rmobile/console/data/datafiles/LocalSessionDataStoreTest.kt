package com.rmobile.console.data.datafiles

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class LocalSessionDataStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LocalSessionDataStore(tmp.root)
    private fun upload(name: String, bytes: ByteArray) =
        DataUpload(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    @Test fun `save then list reports the file and size`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "hello".toByteArray())).getOrThrow()
        val files = s.list("proj-1").getOrThrow()
        assertEquals(1, files.size)
        assertEquals("a.csv", files[0].name)
        assertEquals(5L, files[0].size)
    }
    @Test fun `sessions are isolated`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "x".toByteArray())).getOrThrow()
        assertTrue(s.list("proj-2").getOrThrow().isEmpty())
    }
    @Test fun `delete removes the file`() = runTest {
        val s = store()
        s.save("proj-1", upload("a.csv", "x".toByteArray())).getOrThrow()
        assertTrue(s.delete("proj-1", "a.csv").getOrThrow())
        assertTrue(s.list("proj-1").getOrThrow().isEmpty())
    }
    @Test fun `delete missing returns false`() = runTest {
        assertFalse(store().delete("proj-1", "nope.csv").getOrThrow())
    }
    @Test fun `rejects a reserved filename`() = runTest {
        val r = store().save("proj-1", upload("script.R", "x".toByteArray()))
        assertTrue(r.isFailure)
    }
}
