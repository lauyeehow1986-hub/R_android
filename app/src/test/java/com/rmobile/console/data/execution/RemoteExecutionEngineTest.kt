package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecFile
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteExecutionEngineTest {

    private class RecordingApi : RExecutionApi {
        var lastRequest: ExecuteRequest? = null
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            return ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))
        }
        override suspend fun reset(request: com.rmobile.console.data.model.ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest) = com.rmobile.console.data.model.InstallResponse()
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest) = com.rmobile.console.data.model.UninstallResponse()
        override suspend fun packages(sessionId: String) = com.rmobile.console.data.model.PackagesResponse()
        override suspend fun importLegacy(request: com.rmobile.console.data.model.ImportLegacyRequest) = com.rmobile.console.data.model.ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) = com.rmobile.console.data.model.HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) = com.rmobile.console.data.model.PreviewResponse()
    }

    @Test
    fun `execute forwards the request to the repository and returns its response`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val request = ExecuteRequest(sessionId = "proj-1", files = listOf(ExecFile("main.R", "1")), entryFile = "main.R")

        val result = engine.execute(request)

        assertEquals(listOf(ExecFile("main.R", "1")), api.lastRequest!!.files)
        assertEquals("proj-1", api.lastRequest!!.sessionId)
        assertEquals("ok", result.getOrNull()!!.stdout)
    }

    @Test
    fun `reset delegates to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.reset("proj-1")
        assertTrue(result.isSuccess)
    }
}
