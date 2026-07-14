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
        var lastInstall: com.rmobile.console.data.model.InstallRequest? = null
        var lastUninstall: com.rmobile.console.data.model.UninstallRequest? = null
        var lastPackagesSessionId: String? = null
        var lastPreview: com.rmobile.console.data.model.PreviewRequest? = null
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            return ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))
        }
        override suspend fun reset(request: com.rmobile.console.data.model.ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest): com.rmobile.console.data.model.InstallResponse {
            lastInstall = request
            return com.rmobile.console.data.model.InstallResponse(installed = true, stdout = "installed")
        }
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest): com.rmobile.console.data.model.UninstallResponse {
            lastUninstall = request
            return com.rmobile.console.data.model.UninstallResponse(removed = true)
        }
        override suspend fun packages(sessionId: String): com.rmobile.console.data.model.PackagesResponse {
            lastPackagesSessionId = sessionId
            return com.rmobile.console.data.model.PackagesResponse(listOf("glue"))
        }
        override suspend fun importLegacy(request: com.rmobile.console.data.model.ImportLegacyRequest) = com.rmobile.console.data.model.ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) = com.rmobile.console.data.model.HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest): com.rmobile.console.data.model.PreviewResponse {
            lastPreview = request
            return com.rmobile.console.data.model.PreviewResponse(truncated = true)
        }
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

    @Test
    fun `install forwards package and session to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.install(com.rmobile.console.data.model.InstallRequest("praise", "proj-2"))
        assertEquals("praise", api.lastInstall!!.packageName)
        assertEquals("proj-2", api.lastInstall!!.sessionId)
        assertTrue(result.getOrNull()!!.installed)
    }

    @Test
    fun `uninstall forwards package and session to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.uninstall(com.rmobile.console.data.model.UninstallRequest("praise", "proj-2"))
        assertEquals("praise", api.lastUninstall!!.packageName)
        assertTrue(result.getOrNull()!!.removed)
    }

    @Test
    fun `listPackages forwards the session and returns the list`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.listPackages("proj-2")
        assertEquals("proj-2", api.lastPackagesSessionId)
        assertEquals(listOf("glue"), result.getOrNull()!!.packages)
    }

    @Test
    fun `preview forwards the request to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.preview(com.rmobile.console.data.model.PreviewRequest("file", "a.csv", "proj-2"))
        assertEquals("a.csv", api.lastPreview!!.name)
        assertEquals("proj-2", api.lastPreview!!.sessionId)
        assertTrue(result.getOrNull()!!.truncated)
    }
}
