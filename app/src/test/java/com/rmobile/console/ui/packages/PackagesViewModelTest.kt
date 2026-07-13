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
        engineIsLocal: Boolean = false,
    ): PackagesViewModel {
        val repo = RExecutionRepository(api)
        return PackagesViewModel(
            repository = repo,
            projectStore = projectStore,
            engineProvider = { com.rmobile.console.data.execution.RemoteExecutionEngine(repo) },
            engineIsLocalProvider = { engineIsLocal },
        )
    }

    private class FakeEngine : com.rmobile.console.data.execution.ExecutionEngine {
        var installedArg: String? = null
        override suspend fun execute(request: ExecuteRequest) = Result.success(ExecuteResponse())
        override suspend fun reset(sessionId: String) = Result.success(Unit)
        override suspend fun listPackages(sessionId: String) = Result.success(PackagesResponse(listOf("local-pkg")))
        override suspend fun install(request: InstallRequest): Result<InstallResponse> {
            installedArg = request.packageName
            return Result.success(InstallResponse(installed = true))
        }
        override suspend fun uninstall(request: UninstallRequest) = Result.success(UninstallResponse(removed = true))
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
            engineProvider = { fakeEngine },
            engineIsLocalProvider = { true },
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
        val vm = viewModel(FakeApi(), engineIsLocal = true)
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
            engineProvider = { com.rmobile.console.data.execution.RemoteExecutionEngine(RExecutionRepository(api)) },
            engineIsLocalProvider = { local },
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
}
