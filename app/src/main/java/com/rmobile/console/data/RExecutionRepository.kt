package com.rmobile.console.data

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
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

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
