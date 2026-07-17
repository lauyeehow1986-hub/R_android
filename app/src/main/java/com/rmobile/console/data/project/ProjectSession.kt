package com.rmobile.console.data.project

/**
 * Maps a project to its backend session id. A project's [Project.id] is a
 * positive Long, so the derived id already satisfies the backend's
 * `[A-Za-z0-9_-]` session-id sanitizer and is stable across renames.
 */
object ProjectSession {
    /** The library key shared by every project that opts in. Kept in sync with
     *  bridge.js `SHARED_LIBRARY_KEY`. */
    const val SHARED_LIBRARY_KEY = "shared"

    fun of(project: Project): String = "proj-${project.id}"

    /** Which package library a project uses: the shared one when it opted in, else its own. */
    fun libraryKey(project: Project): String =
        if (project.sharedLibrary) SHARED_LIBRARY_KEY else of(project)
}
