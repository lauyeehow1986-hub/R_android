package com.rmobile.console.data.datafiles

import com.rmobile.console.data.model.DataFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stores a session's data files under baseDir/<sessionId>/<name> on device. */
class LocalSessionDataStore(private val baseDir: File) : SessionDataStore {

    private fun sessionDir(sessionId: String): File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }
        return File(baseDir, safe)
    }

    override suspend fun list(sessionId: String): Result<List<DataFile>> = withContext(Dispatchers.IO) {
        runCatching {
            (sessionDir(sessionId).listFiles() ?: emptyArray())
                .filter { it.isFile }
                .map { DataFile(it.name, it.length()) }
                .sortedBy { it.name }
        }
    }

    override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val name = DataFileName.sanitize(upload.name) ?: error("Invalid or reserved filename.")
            val dir = sessionDir(sessionId).apply { mkdirs() }
            upload.openStream().use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
            Unit
        }
    }

    override suspend fun delete(sessionId: String, name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val safe = DataFileName.sanitize(name) ?: return@runCatching false
            val f = File(sessionDir(sessionId), safe)
            f.exists() && f.delete()
        }
    }
}
