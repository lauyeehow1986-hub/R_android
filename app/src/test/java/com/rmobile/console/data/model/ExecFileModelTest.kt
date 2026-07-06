package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecFileModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `execute request serializes files and entryFile`() {
        val req = ExecuteRequest(
            code = null,
            sessionId = "default",
            files = listOf(ExecFile("main.R", "cat(1)")),
            entryFile = "main.R",
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"entryFile\":\"main.R\""))
        assertTrue(encoded.contains("\"name\":\"main.R\""))
        assertTrue(encoded.contains("\"content\":\"cat(1)\""))
    }
}
