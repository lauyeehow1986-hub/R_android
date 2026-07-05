package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuteModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `response without workspaceObjects decodes to null`() {
        val response = json.decodeFromString<ExecuteResponse>("""{"stdout":"hi"}""")
        assertEquals("hi", response.stdout)
        assertNull(response.workspaceObjects)
    }

    @Test
    fun `response decodes a workspaceObjects array`() {
        val response = json.decodeFromString<ExecuteResponse>("""{"workspaceObjects":["x","df"]}""")
        assertEquals(listOf("x", "df"), response.workspaceObjects)
    }

    @Test
    fun `request serializes sessionId`() {
        val encoded = json.encodeToString(ExecuteRequest(code = "1", sessionId = "default"))
        assertTrue(encoded.contains("\"sessionId\":\"default\""))
    }
}
