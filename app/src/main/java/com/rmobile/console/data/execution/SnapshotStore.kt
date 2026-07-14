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
    fun size(): Int = if (file.exists()) file.length().toInt() else 0

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

    /** Atomically promote the tmp to the committed file. */
    fun commit() {
        if (tmp.exists()) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Remove the committed file and any dangling tmp. */
    fun delete() {
        file.delete()
        tmp.delete()
    }
}
