package com.rmobile.console.data.execution

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import kotlinx.serialization.json.Json

/** On-device WebR engine. Serializes the request to the bridge and parses its ExecuteResponse. */
class LocalExecutionEngine(
    private val controller: WebRController,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ExecutionEngine {
    override suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse> = runCatching {
        val requestJson = json.encodeToString(ExecuteRequest.serializer(), request)
        json.decodeFromString<ExecuteResponse>(controller.execute(requestJson))
    }

    override suspend fun reset(sessionId: String): Result<Unit> = runCatching {
        controller.reset(); Unit
    }
}
