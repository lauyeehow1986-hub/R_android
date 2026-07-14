package com.rmobile.console.data.execution

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
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

    override suspend fun listPackages(sessionId: String): Result<PackagesResponse> = runCatching {
        json.decodeFromString<PackagesResponse>(controller.listPackages())
    }

    override suspend fun install(request: InstallRequest): Result<InstallResponse> = runCatching {
        json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName))
    }

    override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> = runCatching {
        json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName))
    }

    override suspend fun preview(request: PreviewRequest): Result<PreviewResponse> = runCatching {
        val reqJson = json.encodeToString(PreviewRequest.serializer(), request)
        json.decodeFromString<PreviewResponse>(controller.preview(reqJson))
    }
}
