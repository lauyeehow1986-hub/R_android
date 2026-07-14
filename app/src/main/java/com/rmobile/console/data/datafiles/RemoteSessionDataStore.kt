package com.rmobile.console.data.datafiles

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.network.UriRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody

/** Delegates to the backend data endpoints (unchanged behavior). */
class RemoteSessionDataStore(private val repository: RExecutionRepository) : SessionDataStore {
    override suspend fun list(sessionId: String): Result<List<DataFile>> = repository.listDataFiles(sessionId)

    override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> {
        val body = UriRequestBody(upload.openStream, upload.size, "application/octet-stream".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", upload.name, body)
        return repository.uploadFile(sessionId, part).map { }
    }

    override suspend fun delete(sessionId: String, name: String): Result<Boolean> =
        repository.deleteDataFile(name, sessionId)
}
