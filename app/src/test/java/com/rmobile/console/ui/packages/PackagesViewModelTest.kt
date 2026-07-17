package com.rmobile.console.ui.packages

import com.rmobile.console.data.RExecutionRepository
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
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackagesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var packagesResponse: PackagesResponse = PackagesResponse(),
        var installResponse: InstallResponse = InstallResponse(installed = true),
        var uninstallResponse: UninstallResponse = UninstallResponse(removed = true),
        var importLegacyResponse: ImportLegacyResponse = ImportLegacyResponse(),
    ) : RExecutionApi {
        var lastPackagesSessionId: String? = null
        var lastInstallSessionId: String? = null
        var lastImportLegacySessionId: String? = null

        override suspend fun execute(request: ExecuteRequest): ExecuteResponse = ExecuteResponse()
        override suspend fun reset(request: ResetRequest): ResetResponse = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest): InstallResponse {
            lastInstallSessionId = request.sessionId
            return installResponse
        }
        override suspend fun uninstall(request: UninstallRequest): UninstallResponse = uninstallResponse
        override suspend fun packages(sessionId: String): PackagesResponse {
            lastPackagesSessionId = sessionId
            return packagesResponse
        }
        override suspend fun importLegacy(request: ImportLegacyRequest): ImportLegacyResponse {
            lastImportLegacySessionId = request.sessionId
            return importLegacyResponse
        }
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) =
            com.rmobile.console.data.model.HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) =
            com.rmobile.console.data.model.PreviewResponse()
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored: List<Project> = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun viewModel(
        api: FakeApi,
        projectStore: ProjectStore = InMemoryProjectStore(),
        defaultEngine: ExecutionEngineChoice = ExecutionEngineChoice.REMOTE,
    ): PackagesViewModel {
        val repo = RExecutionRepository(api)
        return PackagesViewModel(
            repository = repo,
            projectStore = projectStore,
            defaultEngine = { defaultEngine },
            engineProvider = { _ -> com.rmobile.console.data.execution.RemoteExecutionEngine(repo) },
        )
    }

    private class FakeEngine(val label: String = "local") : com.rmobile.console.data.execution.ExecutionEngine {
        var installedArg: String? = null
        var lastListLibraryKey: String? = null
        var lastInstallLibraryKey: String? = null
        var lastUninstallLibraryKey: String? = null
        override suspend fun execute(request: ExecuteRequest, libraryKey: String?) = Result.success(ExecuteResponse())
        override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?) = Result.success(Unit)
        override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> {
            lastListLibraryKey = libraryKey
            return Result.success(PackagesResponse(listOf("$label-pkg")))
        }
        override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> {
            installedArg = request.packageName; lastInstallLibraryKey = libraryKey
            return Result.success(InstallResponse(installed = true))
        }
        override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> {
            lastUninstallLibraryKey = libraryKey
            return Result.success(UninstallResponse(removed = true))
        }
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.PreviewResponse())
    }

    @Test
    fun `loads installed packages on init`() = runTest {
        val vm = viewModel(FakeApi(packagesResponse = PackagesResponse(listOf("glue", "praise"))))
        advanceUntilIdle()
        assertEquals(listOf("glue", "praise"), vm.uiState.value.installed)
    }

    @Test
    fun `successful install reports success and refreshes the list`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(emptyList()),
            installResponse = InstallResponse(installed = true, stdout = "done"),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(listOf("praise"))
        vm.onPackageNameChanged("praise")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.installing)
        assertFalse(s.isError)
        assertTrue(s.message!!.contains("praise"))
        assertEquals(listOf("praise"), s.installed)
    }

    @Test
    fun `failed install surfaces the error`() = runTest {
        val vm = viewModel(FakeApi(installResponse = InstallResponse(installed = false, error = "not available")))
        advanceUntilIdle()
        vm.onPackageNameChanged("nope")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.isError)
        assertTrue(s.message!!.contains("not available"))
    }

    @Test
    fun `uninstall removes the package and refreshes the list`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(listOf("praise")),
            uninstallResponse = UninstallResponse(removed = true),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(emptyList())
        vm.uninstall("praise")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.isError)
        assertTrue(s.message!!.contains("praise"))
        assertTrue(s.installed.isEmpty())
    }

    @Test
    fun `uninstall that removed nothing surfaces an error`() = runTest {
        val vm = viewModel(FakeApi(uninstallResponse = UninstallResponse(removed = false, error = "not there")))
        advanceUntilIdle()
        vm.uninstall("ghost")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.isError)
        assertTrue(s.message!!.contains("not there"))
    }

    @Test
    fun `uses the active project's session for listing and installing`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "MyProj", now = 0)
        val projectStore = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val api = FakeApi()
        val vm = viewModel(api, projectStore)
        advanceUntilIdle()

        assertEquals("proj-100", api.lastPackagesSessionId)
        assertEquals("MyProj", vm.uiState.value.projectName)

        vm.onPackageNameChanged("praise")
        vm.install()
        advanceUntilIdle()

        assertEquals("proj-100", api.lastInstallSessionId)
    }

    @Test
    fun `importLegacy reports the imported count and refreshes`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(emptyList()),
            importLegacyResponse = ImportLegacyResponse(imported = 3, packages = listOf("a", "b", "c")),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(listOf("a", "b", "c"))

        vm.importLegacy()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.isError)
        assertEquals("Imported 3 package(s).", s.message)
        assertEquals(listOf("a", "b", "c"), s.installed)
    }

    @Test
    fun `routes install through the provided engine, not the repository`() = runTest {
        val api = FakeApi()
        val fakeEngine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(api),
            projectStore = InMemoryProjectStore(),
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { _ -> fakeEngine },
        )
        advanceUntilIdle()
        assertEquals(listOf("local-pkg"), vm.uiState.value.installed)
        vm.onPackageNameChanged("dplyr")
        vm.install()
        advanceUntilIdle()
        assertEquals("dplyr", fakeEngine.installedArg)
        assertNull(api.lastInstallSessionId)
    }

    @Test
    fun `engineIsLocal is reflected in state`() = runTest {
        val vm = viewModel(FakeApi(), defaultEngine = ExecutionEngineChoice.LOCAL)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.engineIsLocal)
    }

    @Test
    fun `onShown re-reads the engine and reloads the list`() = runTest {
        var local = false
        val api = FakeApi(packagesResponse = PackagesResponse(listOf("glue")))
        val vm = PackagesViewModel(
            repository = RExecutionRepository(api),
            projectStore = InMemoryProjectStore(),
            defaultEngine = { if (local) ExecutionEngineChoice.LOCAL else ExecutionEngineChoice.REMOTE },
            engineProvider = { _ -> com.rmobile.console.data.execution.RemoteExecutionEngine(RExecutionRepository(api)) },
        )
        advanceUntilIdle()
        assertFalse(vm.uiState.value.engineIsLocal)

        // Engine switched (e.g. in Settings) and a package installed while away.
        local = true
        api.packagesResponse = PackagesResponse(listOf("glue", "praise"))
        vm.onShown()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.engineIsLocal)
        assertEquals(listOf("glue", "praise"), vm.uiState.value.installed)
    }

    @Test
    fun `resolved engine follows the active project's own engine choice`() = runTest {
        val localEngine = FakeEngine("local")
        val remoteEngine = FakeEngine("remote")
        val project = ProjectOps.newProject(id = 200, name = "Remote proj", now = 0, engine = ExecutionEngineChoice.REMOTE)
        val projectStore = InMemoryProjectStore(initial = listOf(project), lastId = 200)
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = projectStore,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { choice ->
                when (choice) {
                    ExecutionEngineChoice.LOCAL -> localEngine
                    ExecutionEngineChoice.REMOTE -> remoteEngine
                }
            },
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.engineIsLocal)
        assertEquals(listOf("remote-pkg"), vm.uiState.value.installed)
    }

    @Test
    fun `list uses the isolated library key by default`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()
        assertEquals("proj-100", engine.lastListLibraryKey)
        assertFalse(vm.uiState.value.sharedLibrary)
    }

    @Test
    fun `enabling shared library persists the flag and reloads with the shared key`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()

        vm.setSharedLibrary(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sharedLibrary)
        assertTrue(store.stored.first { it.id == 100L }.sharedLibrary)   // persisted
        assertEquals("shared", engine.lastListLibraryKey)                 // reloaded with shared key
    }

    @Test
    fun `install and uninstall use the resolved library key`() = runTest {
        val project = ProjectOps.newProject(id = 7, name = "P", now = 0).copy(sharedLibrary = true)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 7)
        val engine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(FakeApi()),
            projectStore = store,
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { engine },
        )
        advanceUntilIdle()
        vm.onPackageNameChanged("praise"); vm.install(); advanceUntilIdle()
        vm.uninstall("praise"); advanceUntilIdle()
        assertEquals("shared", engine.lastInstallLibraryKey)
        assertEquals("shared", engine.lastUninstallLibraryKey)
    }
}
