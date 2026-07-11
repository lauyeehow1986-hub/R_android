package com.rmobile.console.data

import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.ExecFile
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.HelpRequest
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.ImportLegacyRequest
import com.rmobile.console.data.model.ImportLegacyResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.SymbolsResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import okhttp3.MultipartBody

class RExecutionRepository(
    private val api: RExecutionApi,
    private val dataApi: RDataApi = NetworkModule.rDataApi,
) {
    suspend fun run(code: String, sessionId: String = DEFAULT_SESSION_ID): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(code = code, sessionId = sessionId)) }

    suspend fun run(
        files: List<ExecFile>,
        entryFile: String,
        sessionId: String = DEFAULT_SESSION_ID,
    ): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(sessionId = sessionId, files = files, entryFile = entryFile)) }

    suspend fun reset(
        sessionId: String = DEFAULT_SESSION_ID,
        purgePackages: Boolean = false,
    ): Result<ResetResponse> =
        runCatching { api.reset(ResetRequest(sessionId, purgePackages)) }

    suspend fun install(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<InstallResponse> =
        runCatching { api.install(InstallRequest(packageName, sessionId)) }

    suspend fun uninstall(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<UninstallResponse> =
        runCatching { api.uninstall(UninstallRequest(packageName, sessionId)) }

    suspend fun listPackages(sessionId: String = DEFAULT_SESSION_ID): Result<PackagesResponse> =
        runCatching { api.packages(sessionId) }

    suspend fun importLegacy(sessionId: String = DEFAULT_SESSION_ID): Result<ImportLegacyResponse> =
        runCatching { api.importLegacy(ImportLegacyRequest(sessionId)) }

    suspend fun listSymbols(sessionId: String = DEFAULT_SESSION_ID): Result<List<String>> =
        runCatching { api.symbols(sessionId).symbols }

    suspend fun help(topic: String, sessionId: String = DEFAULT_SESSION_ID): Result<HelpResponse> =
        runCatching { api.help(HelpRequest(topic, sessionId)) }

    suspend fun preview(
        source: String,
        name: String,
        sessionId: String = DEFAULT_SESSION_ID,
    ): Result<PreviewResponse> =
        runCatching { api.preview(PreviewRequest(source, name, sessionId)) }

    suspend fun uploadFile(sessionId: String, file: MultipartBody.Part): Result<UploadResponse> =
        runCatching { dataApi.upload(sessionId, file) }

    suspend fun listDataFiles(sessionId: String = DEFAULT_SESSION_ID): Result<List<DataFile>> =
        runCatching { dataApi.dataFiles(sessionId).files }

    suspend fun deleteDataFile(name: String, sessionId: String = DEFAULT_SESSION_ID): Result<Boolean> =
        runCatching { dataApi.deleteData(DeleteDataRequest(name, sessionId)).removed }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
