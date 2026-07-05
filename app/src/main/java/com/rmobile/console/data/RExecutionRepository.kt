package com.rmobile.console.data

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi

class RExecutionRepository(
    private val api: RExecutionApi,
) {
    suspend fun run(code: String, sessionId: String = DEFAULT_SESSION_ID): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(code, sessionId)) }

    suspend fun reset(sessionId: String = DEFAULT_SESSION_ID): Result<ResetResponse> =
        runCatching { api.reset(ResetRequest(sessionId)) }

    suspend fun install(packageName: String): Result<InstallResponse> =
        runCatching { api.install(InstallRequest(packageName)) }

    suspend fun listPackages(): Result<PackagesResponse> =
        runCatching { api.packages() }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
