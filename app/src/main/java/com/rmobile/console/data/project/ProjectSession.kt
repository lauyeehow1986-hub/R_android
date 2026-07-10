package com.rmobile.console.data.project

/**
 * Maps a project to its backend session id. A project's [Project.id] is a
 * positive Long, so the derived id already satisfies the backend's
 * `[A-Za-z0-9_-]` session-id sanitizer and is stable across renames.
 */
object ProjectSession {
    fun of(project: Project): String = "proj-${project.id}"
}
