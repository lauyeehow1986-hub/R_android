package com.rmobile.console.data

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
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.RTable
import com.rmobile.console.data.model.SymbolsResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RExecutionRepositoryTest {

    private class FakeApi(
        var symbolsResponse: SymbolsResponse = SymbolsResponse(listOf("mean", "median")),
        var helpResponse: HelpResponse = HelpResponse(topic = "mean", found = true),
        var lastSymbolsSession: String? = null,
        var lastHelp: HelpRequest? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String): SymbolsResponse {
            lastSymbolsSession = sessionId; return symbolsResponse
        }
        override suspend fun help(request: HelpRequest): HelpResponse {
            lastHelp = request; return helpResponse
        }
        var previewResponse: PreviewResponse = PreviewResponse(table = RTable(columns = listOf("x")))
        var lastPreview: PreviewRequest? = null
        override suspend fun preview(request: PreviewRequest): PreviewResponse {
            lastPreview = request; return previewResponse
        }
    }

    private class FakeDataApi(
        var dataFilesResponse: DataFilesResponse = DataFilesResponse(listOf(DataFile("a.csv", 10))),
        var uploadResponse: UploadResponse = UploadResponse("a.csv", 10),
        var deleteResponse: DeleteDataResponse = DeleteDataResponse(removed = true),
    ) : RDataApi {
        var lastUploadSession: String? = null
        var lastDataSession: String? = null
        var lastDelete: DeleteDataRequest? = null
        override suspend fun upload(sessionId: String, file: MultipartBody.Part): UploadResponse {
            lastUploadSession = sessionId; return uploadResponse
        }
        override suspend fun dataFiles(sessionId: String): DataFilesResponse {
            lastDataSession = sessionId; return dataFilesResponse
        }
        override suspend fun deleteData(request: DeleteDataRequest): DeleteDataResponse {
            lastDelete = request; return deleteResponse
        }
    }

    @Test
    fun `listDataFiles passes the session and returns files`() = runTest {
        val dataApi = FakeDataApi()
        val result = RExecutionRepository(FakeApi(), dataApi).listDataFiles("proj-9")
        assertEquals("proj-9", dataApi.lastDataSession)
        assertEquals(listOf(DataFile("a.csv", 10)), result.getOrNull())
    }

    @Test
    fun `uploadFile passes the session`() = runTest {
        val dataApi = FakeDataApi()
        val part = MultipartBody.Part.createFormData("file", "a.csv", "x,y".toRequestBody())
        val result = RExecutionRepository(FakeApi(), dataApi).uploadFile("proj-9", part)
        assertEquals("proj-9", dataApi.lastUploadSession)
        assertEquals(UploadResponse("a.csv", 10), result.getOrNull())
    }

    @Test
    fun `deleteDataFile passes name and session and returns removed`() = runTest {
        val dataApi = FakeDataApi()
        val result = RExecutionRepository(FakeApi(), dataApi).deleteDataFile("a.csv", "proj-9")
        assertEquals("a.csv", dataApi.lastDelete!!.name)
        assertEquals("proj-9", dataApi.lastDelete!!.sessionId)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun `listSymbols passes the session and returns names`() = runTest {
        val api = FakeApi()
        val result = RExecutionRepository(api).listSymbols("proj-1")
        assertEquals("proj-1", api.lastSymbolsSession)
        assertEquals(listOf("mean", "median"), result.getOrNull())
    }

    @Test
    fun `help passes topic and session`() = runTest {
        val api = FakeApi()
        val result = RExecutionRepository(api).help("lm", "proj-2")
        assertEquals("lm", api.lastHelp!!.topic)
        assertEquals("proj-2", api.lastHelp!!.sessionId)
        assertEquals(HelpResponse(topic = "mean", found = true), result.getOrNull())
    }

    @Test
    fun `preview forwards source, name, session and returns the table`() = runTest {
        val api = FakeApi()
        val result = RExecutionRepository(api).preview("file", "a.csv", "proj-7")
        assertEquals("file", api.lastPreview!!.source)
        assertEquals("a.csv", api.lastPreview!!.name)
        assertEquals("proj-7", api.lastPreview!!.sessionId)
        assertEquals(listOf("x"), result.getOrNull()!!.table!!.columns)
    }
}
