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
import com.rmobile.console.data.settings.ExecutionEngineChoice
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

        var symbolsResponse: com.rmobile.console.data.model.SymbolsResponse =
            com.rmobile.console.data.model.SymbolsResponse(listOf("mean", "median"))
        var helpResponse: com.rmobile.console.data.model.HelpResponse =
            com.rmobile.console.data.model.HelpResponse(topic = "mean", packageName = "base", text = "Usage", found = true)
        var helpError: Throwable? = null
        var lastHelp: com.rmobile.console.data.model.HelpRequest? = null

        override suspend fun symbols(sessionId: String) = symbolsResponse
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest): com.rmobile.console.data.model.HelpResponse {
            lastHelp = request
            helpError?.let { throw it }
            return helpResponse
        }

        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) =
            com.rmobile.console.data.model.PreviewResponse()
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
        defaultEngine: () -> ExecutionEngineChoice = { ExecutionEngineChoice.LOCAL },
        engineProvider: ((ExecutionEngineChoice) -> com.rmobile.console.data.execution.ExecutionEngine)? = null,
    ): EditorViewModel {
        val repo = RExecutionRepository(api)
        return EditorViewModel(
            repo, history, scripts, projects, now = { counter++ },
            defaultEngine = defaultEngine,
            engineProvider = engineProvider
                ?: { _ -> com.rmobile.console.data.execution.RemoteExecutionEngine(repo) },
        )
    }

    private class FakeEngine(val response: com.rmobile.console.data.model.ExecuteResponse) :
        com.rmobile.console.data.execution.ExecutionEngine {
        var lastRequest: com.rmobile.console.data.model.ExecuteRequest? = null
        var lastRequestLibraryKey: String? = null
        var lastReset: Pair<String, Boolean>? = null
        var lastResetLibraryKey: String? = null
        override suspend fun execute(request: com.rmobile.console.data.model.ExecuteRequest, libraryKey: String?):
            Result<com.rmobile.console.data.model.ExecuteResponse> {
            lastRequest = request; lastRequestLibraryKey = libraryKey; return Result.success(response)
        }
        override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?): Result<Unit> {
            lastReset = sessionId to purgePackages
            lastResetLibraryKey = libraryKey
            return Result.success(Unit)
        }
        override suspend fun listPackages(sessionId: String, libraryKey: String?) = Result.success(com.rmobile.console.data.model.PackagesResponse())
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest, libraryKey: String?) = Result.success(com.rmobile.console.data.model.InstallResponse())
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest, libraryKey: String?) = Result.success(com.rmobile.console.data.model.UninstallResponse())
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest, libraryKey: String?) = Result.success(com.rmobile.console.data.model.PreviewResponse())
        var helpResult: Result<com.rmobile.console.data.model.HelpResponse> =
            Result.success(com.rmobile.console.data.model.HelpResponse(found = false))
        var symbolsResult: Result<com.rmobile.console.data.model.SymbolsResponse> =
            Result.success(com.rmobile.console.data.model.SymbolsResponse())
        var lastHelpTopic: String? = null
        override suspend fun help(topic: String, sessionId: String, libraryKey: String?): Result<com.rmobile.console.data.model.HelpResponse> {
            lastHelpTopic = topic
            return helpResult
        }
        override suspend fun symbols(sessionId: String, libraryKey: String?): Result<com.rmobile.console.data.model.SymbolsResponse> =
            symbolsResult
    }

    @Test
    fun `runCode routes through the selected engine and maps its response`() = runTest {
        val engine = FakeEngine(ExecuteResponse(stdout = "hi", workspaceObjects = listOf("y")))
        val vm = viewModel(engineProvider = { _ -> engine })
        vm.onCodeChanged("y <- 1")
        vm.runCode()
        advanceUntilIdle()

        assertEquals("hi", vm.uiState.value.stdout)
        assertEquals(listOf("y"), vm.uiState.value.workspaceObjects)
        assertEquals("y <- 1", engine.lastRequest!!.files!!.first().content)
    }

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

    @Test
    fun `runCode resolves the active project engine`() = runTest {
        val localEngine = FakeEngine(ExecuteResponse(stdout = "local"))
        val remoteEngine = FakeEngine(ExecuteResponse(stdout = "remote"))
        val vm = viewModel(
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { choice ->
                when (choice) {
                    ExecutionEngineChoice.LOCAL -> localEngine
                    ExecutionEngineChoice.REMOTE -> remoteEngine
                }
            },
        )
        vm.setProjectEngine(ExecutionEngineChoice.REMOTE)
        vm.onCodeChanged("cat(1)")
        vm.runCode()
        advanceUntilIdle()

        assertTrue(remoteEngine.lastRequest != null)
        assertEquals(null, localEngine.lastRequest)
    }

    @Test
    fun `new project is stamped with the settings default engine`() {
        val vm = viewModel(defaultEngine = { ExecutionEngineChoice.REMOTE })
        vm.newProject("Fresh")
        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.project.engine)
    }

    @Test
    fun `setProjectEngine persists the choice on the active project`() {
        val projects = InMemoryProjectStore()
        val vm = viewModel(projects = projects)
        vm.setProjectEngine(ExecutionEngineChoice.REMOTE)

        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.project.engine)
        val persisted = projects.stored.first { it.id == vm.uiState.value.project.id }
        assertEquals(ExecutionEngineChoice.REMOTE, persisted.engine)
    }

    @Test
    fun `deleteProject resets via the victim project's engine`() = runTest {
        val localEngine = FakeEngine(ExecuteResponse(stdout = "local"))
        val remoteEngine = FakeEngine(ExecuteResponse(stdout = "remote"))
        val vm = viewModel(
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { choice ->
                when (choice) {
                    ExecutionEngineChoice.LOCAL -> localEngine
                    ExecutionEngineChoice.REMOTE -> remoteEngine
                }
            },
        )
        vm.newProject("Second")
        vm.setProjectEngine(ExecutionEngineChoice.REMOTE)
        val victim = vm.uiState.value.project
        val victimSession = ProjectSession.of(victim)

        vm.deleteProject(victim.id)
        advanceUntilIdle()

        assertEquals(victimSession to true, remoteEngine.lastReset)
        assertEquals(null, localEngine.lastReset)
    }

    @Test
    fun `deleting a shared-library project resets with the shared library key`() = runTest {
        val engine = FakeEngine(ExecuteResponse(stdout = "x"))
        val shared = ProjectOps.newProject(id = 55, name = "S", now = 0).copy(sharedLibrary = true)
        val store = InMemoryProjectStore(initial = listOf(shared)).apply { lastId = 55 }
        val vm = viewModel(
            projects = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { _ -> engine },
        )
        advanceUntilIdle()

        vm.deleteProject(55L)
        advanceUntilIdle()

        // App still requests a purge; the bridge is what skips purging a shared library.
        assertEquals("proj-55" to true, engine.lastReset)
        assertEquals("shared", engine.lastResetLibraryKey)
    }

    @Test
    fun `refreshActiveProjectSettings picks up a shared-library toggle made off-screen`() = runTest {
        val engine = FakeEngine(ExecuteResponse(stdout = "ok"))
        val project = ProjectOps.newProject(id = 42, name = "P", now = 0) // sharedLibrary = false
        val store = InMemoryProjectStore(initial = listOf(project)).apply { lastId = 42 }
        val vm = viewModel(
            projects = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { _ -> engine },
        )
        advanceUntilIdle()

        // The Packages screen (its own ViewModel) toggles shared-library ON and persists it.
        store.stored = listOf(project.copy(sharedLibrary = true))
        vm.refreshActiveProjectSettings()

        vm.onCodeChanged("1")
        vm.runCode()
        advanceUntilIdle()
        assertEquals("shared", engine.lastRequestLibraryKey)
    }

    @Test
    fun `runCode passes the project's library key`() = runTest {
        val engine = FakeEngine(ExecuteResponse(stdout = "ok"))
        val shared = ProjectOps.newProject(id = 88, name = "S", now = 0).copy(sharedLibrary = true)
        val store = InMemoryProjectStore(initial = listOf(shared)).apply { lastId = 88 }
        val vm = viewModel(
            projects = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { _ -> engine },
        )
        advanceUntilIdle()
        vm.onCodeChanged("1")
        vm.runCode()
        advanceUntilIdle()
        assertEquals("shared", engine.lastRequestLibraryKey)
    }

    @Test
    fun `symbol set includes base names and refreshed index`() = runTest {
        val api = FakeApi()
        api.symbolsResponse = com.rmobile.console.data.model.SymbolsResponse(listOf("dplyr_fn"))
        val vm = viewModel(api = api)
        advanceUntilIdle() // init calls refreshSymbols()
        val syms = vm.uiState.value.completionSymbols
        assertTrue(syms.contains("mean"))       // baked base
        assertTrue(syms.contains("dplyr_fn"))   // refreshed index
    }

    @Test
    fun `run adds workspace objects to the symbol set`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("my_df")))
        val vm = viewModel(api = api)
        vm.onCodeChanged("my_df <- 1")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completionSymbols.contains("my_df"))
    }

    @Test
    fun `showHelp transitions Loading to Loaded`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("mean")
        advanceUntilIdle()
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded)
        assertEquals("mean", (help as HelpState.Loaded).response.topic)
    }

    @Test
    fun `showHelp maps not-found result`() = runTest {
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(topic = "zzz", found = false)
        val vm = viewModel(api = api)
        vm.showHelp("zzz")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.help is HelpState.NotFound)
    }

    @Test
    fun `dismissHelp clears the sheet`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("mean")
        advanceUntilIdle()
        vm.dismissHelp()
        assertEquals(null, vm.uiState.value.help)
    }

    @Test
    fun `showHelp maps failure to Error`() = runTest {
        val api = FakeApi()
        api.helpError = RuntimeException("network down")
        val vm = viewModel(api = api)
        vm.showHelp("mean")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.help is HelpState.Error)
    }

    @Test
    fun `showHelp with blank topic is a no-op`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("   ")
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.help)
    }

    @Test
    fun `dismiss during loading keeps the sheet closed when the response arrives`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("mean")           // sets Loading, schedules the lookup
        vm.dismissHelp()              // user closes the sheet before it resolves
        advanceUntilIdle()            // the in-flight response now completes
        assertEquals(null, vm.uiState.value.help) // must not resurface the sheet
    }

    @Test
    fun `switching to a new project drops the old symbol set and refreshes`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("old_var")))
        api.symbolsResponse = com.rmobile.console.data.model.SymbolsResponse(listOf("fresh_sym"))
        val vm = viewModel(api = api)
        vm.runCode()                  // populate project A's workspace objects
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completionSymbols.contains("old_var"))

        vm.newProject("B")
        // Old project's workspace-derived completions are dropped synchronously.
        assertFalse(vm.uiState.value.completionSymbols.contains("old_var"))
        advanceUntilIdle()
        // The new project's session index is refetched.
        assertTrue(vm.uiState.value.completionSymbols.contains("fresh_sym"))
    }

    @Test
    fun `insertText appends a snippet on its own line`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCodeChanged("head(x)")
        vm.insertText("\"sales.csv\"")
        advanceUntilIdle()
        assertEquals("head(x)\n\"sales.csv\"", vm.uiState.value.code)
    }

    @Test
    fun `insertText into empty code has no leading newline`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCodeChanged("")
        vm.insertText("\"sales.csv\"")
        advanceUntilIdle()
        assertEquals("\"sales.csv\"", vm.uiState.value.code)
    }

    @Test
    fun `showHelp on a Local project routes to the engine and does not hit the backend when found`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(
            com.rmobile.console.data.model.HelpResponse(topic = "aes", text = "Aesthetics", found = true),
        )
        val api = FakeApi()
        val vm = viewModel(api = api, engineProvider = { _ -> engine }) // defaultEngine = LOCAL
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals("aes", engine.lastHelpTopic)
        assertEquals(null, api.lastHelp) // no Remote fallback when the engine found it
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded && help.response.text == "Aesthetics")
    }

    @Test
    fun `showHelp on a Local project falls back to the backend when on-device help is not found`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(com.rmobile.console.data.model.HelpResponse(topic = "aes", found = false))
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(topic = "aes", packageName = "ggplot2", text = "From backend", found = true)
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals("aes", api.lastHelp!!.topic) // fallback consulted
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded && help.response.text == "From backend")
    }

    @Test
    fun `showHelp shows NotFound when neither the engine nor the backend has it`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.success(com.rmobile.console.data.model.HelpResponse(found = false))
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(found = false)
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        vm.showHelp("nope")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.help is HelpState.NotFound)
    }

    @Test
    fun `showHelp on a Remote project does not fall back and surfaces the error`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.helpResult = Result.failure(RuntimeException("boom"))
        val api = FakeApi()
        val vm = viewModel(
            api = api,
            defaultEngine = { ExecutionEngineChoice.REMOTE },
            engineProvider = { _ -> engine },
        )
        vm.showHelp("aes")
        advanceUntilIdle()

        assertEquals(null, api.lastHelp) // REMOTE: no second (fallback) call
        assertTrue(vm.uiState.value.help is HelpState.Error)
    }

    @Test
    fun `refreshSymbols on a Local project uses the engine symbols`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.symbolsResult = Result.success(com.rmobile.console.data.model.SymbolsResponse(listOf("engine_sym")))
        val vm = viewModel(engineProvider = { _ -> engine })
        advanceUntilIdle() // init calls refreshSymbols()
        assertTrue(vm.uiState.value.completionSymbols.contains("engine_sym"))
    }

    @Test
    fun `refreshSymbols falls back to backend symbols when the engine fails`() = runTest {
        val engine = FakeEngine(ExecuteResponse())
        engine.symbolsResult = Result.failure(RuntimeException("offline"))
        val api = FakeApi()
        api.symbolsResponse = com.rmobile.console.data.model.SymbolsResponse(listOf("backend_sym"))
        val vm = viewModel(api = api, engineProvider = { _ -> engine })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completionSymbols.contains("backend_sym"))
    }

    @Test
    fun `runCode assembles ordered output from stdout markers`() {
        val md = com.rmobile.console.data.execution.OutputAssembler.PLOT_MARKER + "done\n"
        val engine = FakeEngine(ExecuteResponse(stdout = md, plots = listOf("PNG"), tables = emptyList()))
        val vm = viewModel(engineProvider = { _ -> engine })
        vm.onCodeChanged("plot(cars); cat('done')")
        vm.runCode()
        val s = vm.uiState.value
        assertTrue(s.outputOrdered)
        assertEquals("done\n", s.stdout)
        assertEquals(2, s.output.size)
        assertTrue(s.output[0] is com.rmobile.console.data.execution.OutputChunk.Plot)
        assertTrue(s.output[1] is com.rmobile.console.data.execution.OutputChunk.Text)
    }
}
