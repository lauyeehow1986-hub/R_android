package com.rmobile.console.data.project

/** Persistence boundary for projects + the last-open project id. */
interface ProjectStore {
    fun loadProjects(): List<Project>
    fun persistProjects(projects: List<Project>)
    fun loadLastOpenProjectId(): Long?
    fun persistLastOpenProjectId(id: Long?)
}
