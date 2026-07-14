package com.rmobile.console.ui.editor

import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.scripts.SavedScript

data class EditorUiState(
    /** The open project. */
    val project: Project = ProjectOps.newProject(id = 0, name = "Untitled", now = 0),
    /** The project library (all projects), most-recently-updated first. */
    val projects: List<Project> = emptyList(),
    /** Active file content — a mirror of the active file in [project] for the editor field. */
    val code: String = ProjectOps.activeContent(project),
    val isRunning: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val plotsBase64: List<String> = emptyList(),
    val tables: List<com.rmobile.console.data.model.RTable> = emptyList(),
    val errorMessage: String? = null,
    val timedOut: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val savedScripts: List<SavedScript> = emptyList(),
    val workspaceObjects: List<String> = emptyList(),
    val completionSymbols: List<String> = emptyList(),
    val help: HelpState? = null,
    val swapPhase: SwapPhase = SwapPhase.IDLE,
)
