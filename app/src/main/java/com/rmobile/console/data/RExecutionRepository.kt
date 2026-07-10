package com.rmobile.console.data

import com.rmobile.console.data.model.ExecFile
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

class RExecutionRepository(
    private val api: RExecutionApi,
) {
    suspend fun run(code: String, sessionId: String = DEFAULT_SESSION_ID): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(code = code, sessionId = sessionId)) }

    suspend fun run(
        files: List<ExecFile>,
        entryFile: String,
        sessionId: String = DEFAULT_SESSION_ID,
    ): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(sessionId = sessionId, files = files, entryFile = entryFile)) }

    suspend fun reset(
        sessionId: String = DEFAULT_SESSION_ID,
        purgePackages: Boolean = false,
    ): Result<ResetResponse> =
        runCatching { api.reset(ResetRequest(sessionId, purgePackages)) }

    suspend fun install(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<InstallResponse> =
        runCatching { api.install(InstallRequest(packageName, sessionId)) }

    suspend fun uninstall(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<UninstallResponse> =
        runCatching { api.uninstall(UninstallRequest(packageName, sessionId)) }

    suspend fun listPackages(sessionId: String = DEFAULT_SESSION_ID): Result<PackagesResponse> =
        runCatching { api.packages(sessionId) }

    suspend fun importLegacy(sessionId: String = DEFAULT_SESSION_ID): Result<ImportLegacyResponse> =
        runCatching { api.importLegacy(ImportLegacyRequest(sessionId)) }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
