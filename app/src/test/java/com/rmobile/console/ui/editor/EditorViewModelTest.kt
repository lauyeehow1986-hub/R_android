package com.rmobile.console.ui.editor

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ImportLegacyRequest
import com.rmobile.console.data.model.ImportLegacyResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectArchive
import com.rmobile.console.data.project.ProjectFile
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var response: ExecuteResponse = ExecuteResponse(stdout = "ok"),
        var error: Throwable? = null,
        var lastRequest: ExecuteRequest? = null,
        var lastReset: ResetRequest? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            error?.let { throw it }
            return response
        }
        override suspend fun reset(request: ResetRequest): ResetResponse {
            lastReset = request
            return ResetResponse(ok = true)
        }
        override suspend fun install(request: InstallRequest): InstallResponse = InstallResponse(installed = true)
        override suspend fun uninstall(request: UninstallRequest): UninstallResponse = UninstallResponse(removed = true)
        override suspend fun packages(sessionId: String): PackagesResponse = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest): ImportLegacyResponse = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) =
            com.rmobile.console.data.model.HelpResponse()
    }

    private class InMemoryHistoryStore(initial: List<HistoryEntry> = emptyList()) : HistoryStore {
        var stored: List<HistoryEntry> = initial
        override fun load() = stored
        override fun persist(entries: List<HistoryEntry>) { stored = entries }
    }

    private class InMemoryScriptStore(initial: List<SavedScript> = emptyList()) : SavedScriptStore {
        var stored: List<SavedScript> = initial
        override fun loadScripts() = stored
        override fun persistScripts(scripts: List<SavedScript>) { stored = scripts }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList()) : ProjectStore {
        var stored: List<Project> = initial
        var lastId: Long? = null
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private var counter = 100L

    private fun viewModel(
        api: FakeApi = FakeApi(),
        history: InMemoryHistoryStore = InMemoryHistoryStore(),
        scripts: InMemoryScriptStore = InMemoryScriptStore(),
        projects: InMemoryProjectStore = InMemoryProjectStore(),
    ) = EditorViewModel(RExecutionRepository(api), history, scripts, projects, now = { counter++ })

    @Test
    fun `creates a default project when none stored`() {
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals("main.R", vm.uiState.value.project.entryFileName)
        assertEquals(ProjectOps.activeContent(vm.uiState.value.project), vm.uiState.value.code)
    }

    @Test
    fun `editing updates the active file content`() {
        val vm = viewModel()
        vm.onCodeChanged("x <- 1")
        assertEquals("x <- 1", vm.uiState.value.code)
        assertEquals("x <- 1", ProjectOps.activeContent(vm.uiState.value.project))
    }

    @Test
    fun `switching files swaps the edited content`() {
        val vm = viewModel()
        vm.onCodeChanged("main code")
        vm.addFile("helpers.R")
        vm.onCodeChanged("helper code")
        vm.switchFile("main.R")
        assertEquals("main code", vm.uiState.value.code)
        vm.switchFile("helpers.R")
        assertEquals("helper code", vm.uiState.value.code)
    }

    @Test
    fun `run sends all files and the pinned entry even when a non-entry file is active`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "done"))
        val vm = viewModel(api = api)
        vm.onCodeChanged("cat(1)")
        vm.addFile("helpers.R")
        vm.onCodeChanged("f <- function() 1")
        vm.runCode()
        advanceUntilIdle()

        val req = api.lastRequest!!
        assertEquals("main.R", req.entryFile)
        assertEquals(setOf("main.R", "helpers.R"), req.files!!.map { it.name }.toSet())
        assertEquals("done", vm.uiState.value.stdout)
    }

    @Test
    fun `setEntry changes which file runs`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api = api)
        vm.addFile("helpers.R")            // helpers.R is now active (and empty)
        vm.onCodeChanged("cat(2)")         // give the new entry non-blank content
        vm.setEntry("helpers.R")
        vm.runCode()
        advanceUntilIdle()
        assertEquals("helpers.R", api.lastRequest!!.entryFile)
    }

    @Test
    fun `deleting the active file falls back to a remaining file`() {
        val vm = viewModel()
        vm.addFile("helpers.R")
        vm.deleteFile("helpers.R")
        assertEquals("main.R", vm.uiState.value.project.activeFileName)
        assertEquals(listOf("main.R"), vm.uiState.value.project.files.map { it.name })
    }

    @Test
    fun `new and open project switch the editor`() {
        val vm = viewModel()
        val firstId = vm.uiState.value.project.id
        vm.onCodeChanged("first project code")
        vm.newProject("Second")
        assertEquals("Second", vm.uiState.value.project.name)
        assertEquals(2, vm.uiState.value.projects.size)
        vm.openProject(firstId)
        assertEquals("first project code", vm.uiState.value.code)
    }

    @Test
    fun `run maps tables into state and clears them on failure`() = runTest {
        val table = com.rmobile.console.data.model.RTable(
            columns = listOf("x"), columnTypes = listOf("numeric"),
            rows = listOf(listOf("1")), totalRows = 1,
        )
        val api = FakeApi(ExecuteResponse(stdout = "ok", tables = listOf(table)))
        val vm = viewModel(api = api)
        vm.onCodeChanged("data.frame(x=1)")
        vm.runCode()
        advanceUntilIdle()
        assertEquals(listOf(table), vm.uiState.value.tables)

        api.error = RuntimeException("boom")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tables.isEmpty())
    }

    @Test
    fun `failed run surfaces an error`() = runTest {
        val vm = viewModel(api = FakeApi(error = RuntimeException("boom")))
        vm.onCodeChanged("x")
        vm.runCode()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRunning)
        assertEquals("boom", vm.uiState.value.errorMessage)
    }

    @Test
    fun `run records history of the entry content`() = runTest {
        val history = InMemoryHistoryStore()
        val vm = viewModel(history = history)
        vm.onCodeChanged("cat(42)")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(history.stored.any { it.code == "cat(42)" })
    }

    @Test
    fun `save and load script operate on the active file`() {
        val scripts = InMemoryScriptStore()
        val vm = viewModel(scripts = scripts)
        vm.onCodeChanged("saved code")
        vm.saveCurrentScript("snippet")
        vm.onCodeChanged("other")
        vm.loadScript(scripts.stored.first())
        assertEquals("saved code", vm.uiState.value.code)
    }

    @Test
    fun `import adds and opens a project`() {
        val vm = viewModel()
        val bytes = ProjectArchive.export(
            Project(5, "Imported", listOf(ProjectFile("main.R", "cat(7)")), "main.R", "main.R", 5),
        )
        vm.importProject(bytes, "fallback")
        assertEquals("Imported", vm.uiState.value.project.name)
        assertEquals("cat(7)", vm.uiState.value.code)
        assertTrue(vm.uiState.value.projects.any { it.name == "Imported" })
    }

    @Test
    fun `import of garbage sets an error and leaves the library unchanged`() {
        val vm = viewModel()
        val before = vm.uiState.value.projects.size
        vm.importProject(byteArrayOf(9, 9, 9), "x")
        assertEquals(before, vm.uiState.value.projects.size)
        assertTrue(vm.uiState.value.errorMessage!!.contains("import", ignoreCase = true))
    }

    @Test
    fun `run sends the active project's session id`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "done"))
        val vm = viewModel(api = api)
        vm.onCodeChanged("cat(1)")
        vm.runCode()
        advanceUntilIdle()

        val expectedSession = ProjectSession.of(vm.uiState.value.project)
        assertEquals(expectedSession, api.lastRequest!!.sessionId)
    }

    @Test
    fun `deleting a project purges its backend session`() = runTest {
        val api = FakeApi()
        val vm = viewModel(api = api)
        vm.newProject("Second")
        val victim = vm.uiState.value.project
        val victimSession = ProjectSession.of(victim)

        vm.deleteProject(victim.id)
        advanceUntilIdle()

        val reset = api.lastReset!!
        assertEquals(victimSession, reset.sessionId)
        assertTrue(reset.purgePackages)
    }
}
