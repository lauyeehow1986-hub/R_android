package com.rmobile.console.data.execution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String = "snap.bin") = SnapshotStore(File(tmp.root, name))

    @Test fun `size is zero when nothing committed`() {
        assertEquals(0, store().size())
    }

    @Test fun `begin append commit writes the full payload`() {
        val s = store()
        s.begin()
        s.append(byteArrayOf(1, 2, 3))
        s.append(byteArrayOf(4, 5))
        s.commit()
        assertEquals(5, s.size())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), s.read(0, 5))
    }

    @Test fun `read past end returns empty`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1, 2, 3)); s.commit()
        assertArrayEquals(ByteArray(0), s.read(3, 10))
        assertArrayEquals(ByteArray(0), s.read(99, 10))
    }

    @Test fun `read clamps length to available bytes`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1, 2, 3, 4, 5)); s.commit()
        assertArrayEquals(byteArrayOf(3, 4, 5), s.read(2, 100))
    }

    @Test fun `in-progress append does not change committed file until commit`() {
        val s = store()
        s.begin(); s.append("old".toByteArray()); s.commit()
        assertEquals(3, s.size())
        // A fresh begin/append cycle must not clobber the committed file mid-write.
        s.begin()
        s.append("newer-payload".toByteArray())
        assertEquals(3, s.size()) // still the old committed size
        s.commit()
        assertEquals("newer-payload".length, s.size())
    }

    @Test fun `delete removes committed file and any dangling tmp`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1)); s.commit()
        assertTrue(s.size() > 0)
        s.begin(); s.append(byteArrayOf(2, 3)) // leave a dangling tmp, no commit
        s.delete()
        assertEquals(0, s.size())
        assertFalse(File(tmp.root, "snap.bin").exists())
        assertFalse(File(tmp.root, "snap.bin.tmp").exists())
    }
}
