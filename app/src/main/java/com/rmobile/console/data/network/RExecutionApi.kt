package com.rmobile.console.data.network

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import retrofit2.http.Body
import retrofit2.http.POST

/** Talks to the R execution backend in /backend (see backend/README.md). */
interface RExecutionApi {
    @POST("execute")
    suspend fun execute(@Body request: ExecuteRequest): ExecuteResponse
}
