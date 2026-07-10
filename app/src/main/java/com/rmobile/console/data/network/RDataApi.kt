package com.rmobile.console.data.network

import com.rmobile.console.data.model.DataFilesResponse
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.DeleteDataResponse
import com.rmobile.console.data.model.UploadResponse
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * Data-file endpoints. Served by a dedicated OkHttp client (no overall call
 * timeout) so large uploads aren't aborted; see [NetworkModule.rDataApi].
 */
interface RDataApi {
    @Multipart
    @POST("upload")
    suspend fun upload(
        @Query("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
    ): UploadResponse

    @GET("data")
    suspend fun dataFiles(@Query("sessionId") sessionId: String): DataFilesResponse

    @POST("delete-data")
    suspend fun deleteData(@Body request: DeleteDataRequest): DeleteDataResponse
}
