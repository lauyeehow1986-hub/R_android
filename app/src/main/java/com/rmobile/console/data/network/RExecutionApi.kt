package com.rmobile.console.data.network

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import retrofit2.http.Body
import retrofit2.http.POST

/** Talks to the R execution backend in /backend (see backend/README.md). */
interface RExecutionApi {
    @POST("execute")
    suspend fun execute(@Body request: ExecuteRequest): ExecuteResponse

    @POST("reset")
    suspend fun reset(@Body request: ResetRequest): ResetResponse
}
