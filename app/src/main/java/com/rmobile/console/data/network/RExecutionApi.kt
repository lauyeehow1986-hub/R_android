package com.rmobile.console.data.network

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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
    suspend fun packages(): PackagesResponse
}
