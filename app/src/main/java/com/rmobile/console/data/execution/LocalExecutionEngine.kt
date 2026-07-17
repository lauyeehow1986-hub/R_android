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
    override suspend fun execute(request: ExecuteRequest, libraryKey: String?): Result<ExecuteResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        val requestJson = json.encodeToString(ExecuteRequest.serializer(), request)
        json.decodeFromString<ExecuteResponse>(controller.execute(requestJson, libraryKey ?: session))
    }

    override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?): Result<Unit> = runCatching {
        controller.reset(sessionId, purgePackages, libraryKey ?: sessionId); Unit
    }

    override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> = runCatching {
        json.decodeFromString<PackagesResponse>(controller.listPackages(sessionId, libraryKey ?: sessionId))
    }

    override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName, session, libraryKey ?: session))
    }

    override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName, session, libraryKey ?: session))
    }

    override suspend fun preview(request: PreviewRequest, libraryKey: String?): Result<PreviewResponse> = runCatching {
        val session = request.sessionId ?: com.rmobile.console.data.RExecutionRepository.DEFAULT_SESSION_ID
        val reqJson = json.encodeToString(PreviewRequest.serializer(), request)
        json.decodeFromString<PreviewResponse>(controller.preview(reqJson, libraryKey ?: session))
    }
}
