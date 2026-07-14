package com.rmobile.console.data.execution

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A byte-oriented, single-file chunk store for streaming a snapshot between Kotlin
 * and the WebR VFS across the string-only JS bridge. Writes go to a sibling `.tmp`
 * and are atomically promoted on [commit], so a partial/failed write never clobbers
 * the last good snapshot. Pure `java.io` (no Android types) so it is JVM-unit-testable;
 * base64 encoding stays at the bridge boundary in WebRController.
 */
class SnapshotStore(private val file: File) {
    private val tmp = File(file.parentFile, file.name + ".tmp")

    /** Committed size in bytes (0 if nothing committed). Int to match the JS bridge contract. */
    fun size(): Int = file.length().toInt()

    /** Bytes [offset, offset+length), clamped to end; empty if offset is at/after end. */
    fun read(offset: Int, length: Int): ByteArray {
        val total = size()
        if (offset >= total) return ByteArray(0)
        val end = minOf(offset + length, total)
        val buf = ByteArray(end - offset)
        RandomAccessFile(file, "r").use { it.seek(offset.toLong()); it.readFully(buf) }
        return buf
    }

    /** Start a fresh write, discarding any prior in-progress tmp. */
    fun begin() {
        tmp.delete()
        tmp.parentFile?.mkdirs()
        tmp.createNewFile()
    }

    fun append(bytes: ByteArray) {
        FileOutputStream(tmp, true).use { it.write(bytes) }
    }

    /** Atomically promote the tmp to the committed file, replacing any prior snapshot. */
    fun commit() {
        if (!tmp.exists()) return
        // REPLACE_EXISTING is an atomic rename on the same filesystem (tmp is a sibling
        // of file), so a failed promote throws WITHOUT having first destroyed the last
        // good snapshot — unlike delete()+renameTo(), which had a window where a rename
        // failure lost both. Works on Android (POSIX rename) and on the Windows JVM test
        // host (where bare File.renameTo won't overwrite an existing target).
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** Remove the committed file and any dangling tmp. */
    fun delete() {
        file.delete()
        tmp.delete()
    }
}
