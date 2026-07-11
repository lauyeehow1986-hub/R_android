package com.rmobile.console.data.network

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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Talks to the R execution backend in /backend (see backend/README.md). */
interface RExecutionApi {
    @POST("execute")
    suspend fun execute(@Body request: ExecuteRequest): ExecuteResponse

    @POST("reset")
    suspend fun reset(@Body request: ResetRequest): ResetResponse

    @POST("install")
    suspend fun install(@Body request: InstallRequest): InstallResponse

    @POST("uninstall")
    suspend fun uninstall(@Body request: UninstallRequest): UninstallResponse

    @GET("packages")
    suspend fun packages(@Query("sessionId") sessionId: String): PackagesResponse

    @POST("import-legacy")
    suspend fun importLegacy(@Body request: ImportLegacyRequest): ImportLegacyResponse

    @GET("symbols")
    suspend fun symbols(@Query("sessionId") sessionId: String): SymbolsResponse

    @POST("help")
    suspend fun help(@Body request: HelpRequest): HelpResponse

    @POST("preview")
    suspend fun preview(@Body request: PreviewRequest): PreviewResponse
}
