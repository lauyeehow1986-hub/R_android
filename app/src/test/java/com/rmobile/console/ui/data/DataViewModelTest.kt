package com.rmobile.console.ui.data

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.DataFile
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
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class NoopExecApi : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = SymbolsResponse()
        override suspend fun help(request: HelpRequest) = HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) =
            com.rmobile.console.data.model.PreviewResponse()
    }

    private class FakeDataApi(
        var files: DataFilesResponse = DataFilesResponse(listOf(DataFile("a.csv", 10))),
        var upload: UploadResponse = UploadResponse("b.csv", 20),
        var delete: DeleteDataResponse = DeleteDataResponse(removed = true),
    ) : RDataApi {
        var lastDataSession: String? = null
        var lastUploadSession: String? = null
        var lastDelete: DeleteDataRequest? = null
        override suspend fun upload(sessionId: String, file: MultipartBody.Part): UploadResponse {
            lastUploadSession = sessionId; return upload
        }
        override suspend fun dataFiles(sessionId: String): DataFilesResponse {
            lastDataSession = sessionId; return files
        }
        override suspend fun deleteData(request: DeleteDataRequest): DeleteDataResponse {
            lastDelete = request; return delete
        }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored: List<Project> = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun vm(
        dataApi: FakeDataApi,
        projectStore: ProjectStore = InMemoryProjectStore(),
    ) = DataViewModel(RExecutionRepository(NoopExecApi(), dataApi), projectStore)

    private fun part() =
        MultipartBody.Part.createFormData("file", "b.csv", "x".toRequestBody())

    @Test
    fun `loads files on init`() = runTest {
        val vm = vm(FakeDataApi(files = DataFilesResponse(listOf(DataFile("a.csv", 10)))))
        advanceUntilIdle()
        assertEquals(listOf(DataFile("a.csv", 10)), vm.uiState.value.files)
    }

    @Test
    fun `upload refreshes the list`() = runTest {
        val api = FakeDataApi(files = DataFilesResponse(emptyList()))
        val vm = vm(api)
        advanceUntilIdle()
        api.files = DataFilesResponse(listOf(DataFile("b.csv", 20)))
        vm.upload(part())
        advanceUntilIdle()
        assertEquals(listOf(DataFile("b.csv", 20)), vm.uiState.value.files)
        assertTrue(!vm.uiState.value.uploading)
    }

    @Test
    fun `delete refreshes the list`() = runTest {
        val api = FakeDataApi(files = DataFilesResponse(listOf(DataFile("a.csv", 10))))
        val vm = vm(api)
        advanceUntilIdle()
        api.files = DataFilesResponse(emptyList())
        vm.delete("a.csv")
        advanceUntilIdle()
        assertEquals("a.csv", api.lastDelete!!.name)
        assertTrue(vm.uiState.value.files.isEmpty())
    }

    @Test
    fun `uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "MyProj", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val api = FakeDataApi()
        vm(api, store)
        advanceUntilIdle()
        assertEquals("proj-100", api.lastDataSession)
    }
}
