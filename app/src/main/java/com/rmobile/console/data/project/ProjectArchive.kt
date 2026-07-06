package com.rmobile.console.data.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Serializes a [Project] to / from a `.zip` of its `.R` files plus a
 * `.rmobile-project.json` manifest. Pure (no Android) so it's unit-testable.
 */
object ProjectArchive {

    private const val MANIFEST = ".rmobile-project.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(val name: String, val entryFileName: String, val activeFileName: String)

    fun export(project: Project): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            project.files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifest = Manifest(project.name, project.entryFileName, project.activeFileName)
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Rebuilds a [Project] from [bytes]. Non-manifest entries become files (names
     * validated with [ProjectOps.isValidFileName], which also blocks path names);
     * the manifest supplies name/entry/active when present, else a fallback is
     * used. Returns null when the bytes aren't a readable zip, contain no files,
     * or contain an invalid file name. [id]/[now] are assigned by the caller.
     */
    fun import(bytes: ByteArray, id: Long, now: Long, fallbackName: String): Project? {
        val files = mutableListOf<ProjectFile>()
        var manifest: Manifest? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zip.readBytes().toString(Charsets.UTF_8) // reads to end of this entry
                        if (entry.name == MANIFEST) {
                            manifest = runCatching { json.decodeFromString<Manifest>(content) }.getOrNull()
                        } else {
                            if (!ProjectOps.isValidFileName(entry.name)) return null
                            files.add(ProjectFile(entry.name, content))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (files.isEmpty()) return null

        val names = files.map { it.name }.toSet()
        val defaultEntry = if ("main.R" in names) "main.R" else files.first().name
        val name = manifest?.name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { "Imported project" }
        val entryFile = manifest?.entryFileName?.takeIf { it in names } ?: defaultEntry
        val activeFile = manifest?.activeFileName?.takeIf { it in names } ?: entryFile

        return Project(
            id = id,
            name = name,
            files = files,
            activeFileName = activeFile,
            entryFileName = entryFile,
            updatedAt = now,
        )
    }
}
