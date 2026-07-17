package com.rmobile.console.data.project

import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.serialization.Serializable

@Serializable
data class ProjectFile(val name: String, val content: String)

@Serializable
data class Project(
    val id: Long,
    val name: String,
    val files: List<ProjectFile>,
    val activeFileName: String,
    val entryFileName: String,
    val updatedAt: Long,
    val engine: ExecutionEngineChoice? = null,
    val sharedLibrary: Boolean = false,
)

/**
 * Pure operations on projects and the project list — no Android or persistence,
 * so they can be unit-tested. All return new immutable values; file-mutating ops
 * return null when the request is invalid (bad name, duplicate, missing file,
 * or deleting the last file) so callers can surface an error.
 */
object ProjectOps {

    const val MAX_PROJECTS = 100
    val FILE_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private const val SAMPLE = "# Write R code and tap Run\nsummary(cars)\nplot(cars)\n"

    fun isValidFileName(name: String): Boolean = FILE_NAME_REGEX.matches(name)

    fun newProject(id: Long, name: String, now: Long, engine: ExecutionEngineChoice? = null): Project =
        Project(
            id = id,
            name = name,
            files = listOf(ProjectFile("main.R", SAMPLE)),
            activeFileName = "main.R",
            entryFileName = "main.R",
            updatedAt = now,
            engine = engine,
        )

    fun activeContent(project: Project): String =
        project.files.firstOrNull { it.name == project.activeFileName }?.content ?: ""

    fun updateActiveContent(project: Project, content: String, now: Long): Project =
        project.copy(
            files = project.files.map {
                if (it.name == project.activeFileName) it.copy(content = content) else it
            },
            updatedAt = now,
        )

    fun setActive(project: Project, name: String): Project =
        if (project.files.any { it.name == name }) project.copy(activeFileName = name) else project

    fun setEntry(project: Project, name: String, now: Long): Project =
        if (project.files.any { it.name == name }) project.copy(entryFileName = name, updatedAt = now) else project

    fun addFile(project: Project, name: String, now: Long): Project? {
        if (!isValidFileName(name) || project.files.any { it.name == name }) return null
        return project.copy(
            files = project.files + ProjectFile(name, ""),
            activeFileName = name,
            updatedAt = now,
        )
    }

    fun renameFile(project: Project, oldName: String, newName: String, now: Long): Project? {
        if (!isValidFileName(newName)) return null
        if (project.files.none { it.name == oldName }) return null
        if (project.files.any { it.name == newName }) return null
        return project.copy(
            files = project.files.map { if (it.name == oldName) it.copy(name = newName) else it },
            activeFileName = if (project.activeFileName == oldName) newName else project.activeFileName,
            entryFileName = if (project.entryFileName == oldName) newName else project.entryFileName,
            updatedAt = now,
        )
    }

    fun deleteFile(project: Project, name: String, now: Long): Project? {
        if (project.files.size <= 1 || project.files.none { it.name == name }) return null
        val remaining = project.files.filterNot { it.name == name }
        val fallback = remaining.first().name
        return project.copy(
            files = remaining,
            activeFileName = if (project.activeFileName == name) fallback else project.activeFileName,
            entryFileName = if (project.entryFileName == name) fallback else project.entryFileName,
            updatedAt = now,
        )
    }

    fun upsert(existing: List<Project>, project: Project): List<Project> =
        (existing.filterNot { it.id == project.id } + project).sortedByDescending { it.updatedAt }

    fun delete(existing: List<Project>, id: Long): List<Project> =
        existing.filterNot { it.id == id }
}
