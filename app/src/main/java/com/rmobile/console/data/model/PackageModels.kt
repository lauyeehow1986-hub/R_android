package com.rmobile.console.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstallRequest(
    // JSON key must be "package" (a Kotlin soft keyword), so the property is renamed.
    @SerialName("package") val packageName: String,
)

@Serializable
data class InstallResponse(
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val timedOut: Boolean = false,
    val installed: Boolean = false,
    val systemRequirements: String? = null,
)

@Serializable
data class PackagesResponse(
    val packages: List<String> = emptyList(),
)
