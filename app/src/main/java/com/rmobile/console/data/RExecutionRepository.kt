package com.rmobile.console.data

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.network.RExecutionApi

class RExecutionRepository(
    private val api: RExecutionApi,
) {
    suspend fun run(code: String): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(code)) }
}
