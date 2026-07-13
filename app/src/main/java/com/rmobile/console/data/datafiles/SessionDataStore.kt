package com.rmobile.console.data.datafiles

import com.rmobile.console.data.model.DataFile
import java.io.InputStream

/** Engine-agnostic upload source. openStream is reopened per read (never buffered whole). */
class DataUpload(val name: String, val size: Long, val openStream: () -> InputStream)

/** Where a session's uploaded data files live. Remote = backend; Local = on-device storage. */
interface SessionDataStore {
    suspend fun list(sessionId: String): Result<List<DataFile>>
    suspend fun save(sessionId: String, upload: DataUpload): Result<Unit>
    suspend fun delete(sessionId: String, name: String): Result<Boolean>
}

/** Pure filename policy, mirroring the backend: basename, keep [A-Za-z0-9._-], reject empty/reserved. */
object DataFileName {
    private val reserved = setOf("script.R", "objects.txt", "main.R")
    private val reservedPattern = Regex("^(plot[0-9]+\\.png|table[0-9]+\\.json)$")

    fun sanitize(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (cleaned.isBlank()) return null
        if (cleaned in reserved || reservedPattern.matches(cleaned)) return null
        return cleaned
    }
}
