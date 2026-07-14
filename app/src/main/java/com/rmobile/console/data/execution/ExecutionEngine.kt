package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse

/**
 * Where R code runs. Both implementations return the SAME [ExecuteResponse]
 * contract, so all downstream UI (output panel, plot decoding, RTableView,
 * workspace chips) is engine-agnostic.
 */
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse>
    /** Clears the session's workspace (and its package library when [purgePackages]). */
    suspend fun reset(sessionId: String, purgePackages: Boolean = false): Result<Unit>
    suspend fun listPackages(sessionId: String): Result<PackagesResponse>
    suspend fun install(request: InstallRequest): Result<InstallResponse>
    suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse>
    /** Read-only preview of a data file or workspace object as a table. */
    suspend fun preview(request: PreviewRequest): Result<PreviewResponse>
}

/** The network backend engine — delegates to the existing repository. */
class RemoteExecutionEngine(
    private val repository: RExecutionRepository,
) : ExecutionEngine {
    override suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse> {
        val sessionId = request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID
        val files = request.files
        return if (files != null) {
            repository.run(files, request.entryFile.orEmpty(), sessionId)
        } else {
            repository.run(request.code.orEmpty(), sessionId)
        }
    }

    override suspend fun reset(sessionId: String, purgePackages: Boolean): Result<Unit> =
        repository.reset(sessionId, purgePackages).map { }

    override suspend fun listPackages(sessionId: String): Result<PackagesResponse> =
        repository.listPackages(sessionId)

    override suspend fun install(request: InstallRequest): Result<InstallResponse> =
        repository.install(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> =
        repository.uninstall(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun preview(request: PreviewRequest): Result<PreviewResponse> =
        repository.preview(request.source, request.name, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)
}
