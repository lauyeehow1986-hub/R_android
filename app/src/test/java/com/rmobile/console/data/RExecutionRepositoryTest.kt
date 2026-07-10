package com.rmobile.console.data

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
import com.rmobile.console.data.network.RExecutionApi
import kotlinx.coroutines.test.runTest
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
        RExecutionRepository(api).help("lm", "proj-2")
        assertEquals("lm", api.lastHelp!!.topic)
        assertEquals("proj-2", api.lastHelp!!.sessionId)
        assertTrue(true)
    }
}
