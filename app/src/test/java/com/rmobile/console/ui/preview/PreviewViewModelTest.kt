package com.rmobile.console.ui.preview

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.RTable
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.model.DataFilesResponse
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.DeleteDataResponse
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.HelpRequest
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.ImportLegacyRequest
import com.rmobile.console.data.model.ImportLegacyResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.SymbolsResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePreviewApi(
        var response: PreviewResponse = PreviewResponse(table = RTable(columns = listOf("x"), totalRows = 1)),
        var fail: Boolean = false,
    ) : RExecutionApi {
        var lastRequest: PreviewRequest? = null
        override suspend fun preview(request: PreviewRequest): PreviewResponse {
            lastRequest = request
            if (fail) throw RuntimeException("boom")
            return response
        }
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = SymbolsResponse()
        override suspend fun help(request: HelpRequest) = HelpResponse()
    }

    private class NoopDataApi : RDataApi {
        override suspend fun upload(sessionId: String, file: MultipartBody.Part) = UploadResponse()
        override suspend fun dataFiles(sessionId: String) = DataFilesResponse()
        override suspend fun deleteData(request: DeleteDataRequest) = DeleteDataResponse()
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored: List<Project> = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun vm(
        api: FakePreviewApi,
        store: ProjectStore = InMemoryProjectStore(),
        defaultEngine: ExecutionEngineChoice = ExecutionEngineChoice.REMOTE,
    ) = PreviewViewModel(
        defaultEngine = { defaultEngine },
        engineProvider = { _ -> com.rmobile.console.data.execution.RemoteExecutionEngine(RExecutionRepository(api, NoopDataApi())) },
        projectStore = store,
    )

    @Test
    fun `load populates the table and title`() = runTest {
        val api = FakePreviewApi(PreviewResponse(table = RTable(columns = listOf("a"), totalRows = 3)))
        val model = vm(api)
        model.load("file", "a.csv")
        advanceUntilIdle()
        val s = model.uiState.value
        assertEquals("a.csv", s.title)
        assertEquals(listOf("a"), s.table!!.columns)
        assertTrue(!s.isLoading)
        assertNull(s.error)
    }

    @Test
    fun `load surfaces a backend error message`() = runTest {
        val api = FakePreviewApi(PreviewResponse(table = null, error = "Not a table."))
        val model = vm(api)
        model.load("object", "v")
        advanceUntilIdle()
        assertEquals("Not a table.", model.uiState.value.error)
        assertNull(model.uiState.value.table)
    }

    @Test
    fun `load surfaces a network failure`() = runTest {
        val api = FakePreviewApi(fail = true)
        val model = vm(api)
        model.load("object", "v")
        advanceUntilIdle()
        assertTrue(!model.uiState.value.error.isNullOrEmpty())
    }

    @Test
    fun `load uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 42, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 42)
        val api = FakePreviewApi()
        vm(api, store).load("file", "a.csv")
        advanceUntilIdle()
        assertEquals("proj-42", api.lastRequest!!.sessionId)
    }

    private class FakeEngine : com.rmobile.console.data.execution.ExecutionEngine {
        var lastRequest: PreviewRequest? = null
        override suspend fun execute(request: ExecuteRequest, libraryKey: String?) = Result.success(ExecuteResponse())
        override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?) = Result.success(Unit)
        override suspend fun listPackages(sessionId: String, libraryKey: String?) = Result.success(PackagesResponse())
        override suspend fun install(request: InstallRequest, libraryKey: String?) = Result.success(InstallResponse())
        override suspend fun uninstall(request: UninstallRequest, libraryKey: String?) = Result.success(UninstallResponse())
        override suspend fun preview(request: PreviewRequest, libraryKey: String?): Result<PreviewResponse> {
            lastRequest = request
            return Result.success(PreviewResponse(table = RTable(columns = listOf("x"), totalRows = 1)))
        }
        override suspend fun help(topic: String, sessionId: String, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.HelpResponse())
        override suspend fun symbols(sessionId: String, libraryKey: String?) =
            Result.success(com.rmobile.console.data.model.SymbolsResponse())
    }

    @Test
    fun `resolved engine follows the active project's own engine choice`() = runTest {
        val localEngine = FakeEngine()
        val remoteEngine = FakeEngine()
        val project = ProjectOps.newProject(id = 55, name = "P", now = 0, engine = ExecutionEngineChoice.REMOTE)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 55)
        val model = PreviewViewModel(
            defaultEngine = { ExecutionEngineChoice.LOCAL },
            engineProvider = { choice ->
                when (choice) {
                    ExecutionEngineChoice.LOCAL -> localEngine
                    ExecutionEngineChoice.REMOTE -> remoteEngine
                }
            },
            projectStore = store,
        )
        model.load("file", "a.csv")
        advanceUntilIdle()

        assertTrue(remoteEngine.lastRequest != null)
        assertNull(localEngine.lastRequest)
    }
}
