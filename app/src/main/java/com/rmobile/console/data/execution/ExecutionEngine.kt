package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.SymbolsResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse

/**
 * Where R code runs. Both implementations return the SAME [ExecuteResponse]
 * contract, so all downstream UI (output panel, plot decoding, RTableView,
 * workspace chips) is engine-agnostic.
 */
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest, libraryKey: String? = null): Result<ExecuteResponse>
    /** Clears the session's workspace (and its package library when [purgePackages]).
     *  [libraryKey] selects which on-device library to purge; a shared library is never purged. */
    suspend fun reset(sessionId: String, purgePackages: Boolean = false, libraryKey: String? = null): Result<Unit>
    suspend fun listPackages(sessionId: String, libraryKey: String? = null): Result<PackagesResponse>
    suspend fun install(request: InstallRequest, libraryKey: String? = null): Result<InstallResponse>
    suspend fun uninstall(request: UninstallRequest, libraryKey: String? = null): Result<UninstallResponse>
    /** Read-only preview of a data file or workspace object as a table. */
    suspend fun preview(request: PreviewRequest, libraryKey: String? = null): Result<PreviewResponse>
    /** Rendered R help text for [topic]. */
    suspend fun help(topic: String, sessionId: String, libraryKey: String? = null): Result<HelpResponse>
    /** Completion symbol names for the session (base + attached/library()'d exports). */
    suspend fun symbols(sessionId: String, libraryKey: String? = null): Result<SymbolsResponse>
}

/** The network backend engine — delegates to the existing repository. */
class RemoteExecutionEngine(
    private val repository: RExecutionRepository,
) : ExecutionEngine {
    override suspend fun execute(request: ExecuteRequest, libraryKey: String?): Result<ExecuteResponse> {
        val sessionId = request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID
        val files = request.files
        return if (files != null) {
            repository.run(files, request.entryFile.orEmpty(), sessionId)
        } else {
            repository.run(request.code.orEmpty(), sessionId)
        }
    }

    override suspend fun reset(sessionId: String, purgePackages: Boolean, libraryKey: String?): Result<Unit> =
        repository.reset(sessionId, purgePackages).map { }

    override suspend fun listPackages(sessionId: String, libraryKey: String?): Result<PackagesResponse> =
        repository.listPackages(sessionId)

    override suspend fun install(request: InstallRequest, libraryKey: String?): Result<InstallResponse> =
        repository.install(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun uninstall(request: UninstallRequest, libraryKey: String?): Result<UninstallResponse> =
        repository.uninstall(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun preview(request: PreviewRequest, libraryKey: String?): Result<PreviewResponse> =
        repository.preview(request.source, request.name, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun help(topic: String, sessionId: String, libraryKey: String?): Result<HelpResponse> =
        repository.help(topic, sessionId)

    override suspend fun symbols(sessionId: String, libraryKey: String?): Result<SymbolsResponse> =
        repository.listSymbols(sessionId).map { SymbolsResponse(it) }
}
