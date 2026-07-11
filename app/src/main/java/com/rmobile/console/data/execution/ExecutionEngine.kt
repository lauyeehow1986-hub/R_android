package com.rmobile.console.data.execution

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse

/**
 * Where R code runs. Both implementations return the SAME [ExecuteResponse]
 * contract, so all downstream UI (output panel, plot decoding, RTableView,
 * workspace chips) is engine-agnostic.
 */
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse>
    /** Clears the session's workspace. */
    suspend fun reset(sessionId: String): Result<Unit>
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

    override suspend fun reset(sessionId: String): Result<Unit> =
        repository.reset(sessionId).map { }
}
