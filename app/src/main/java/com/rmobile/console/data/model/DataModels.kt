package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DataFile(val name: String, val size: Long)

@Serializable
data class DataFilesResponse(val files: List<DataFile> = emptyList())

@Serializable
data class UploadResponse(val name: String = "", val size: Long = 0)

@Serializable
data class DeleteDataRequest(val name: String, val sessionId: String? = null)

@Serializable
data class DeleteDataResponse(val removed: Boolean = false)
