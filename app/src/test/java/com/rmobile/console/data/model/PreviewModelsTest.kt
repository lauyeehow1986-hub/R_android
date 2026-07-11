package com.rmobile.console.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request serializes source, name, sessionId`() {
        val out = json.encodeToString(PreviewRequest.serializer(), PreviewRequest("file", "a.csv", "proj-1"))
        assertEquals("""{"source":"file","name":"a.csv","sessionId":"proj-1"}""", out)
    }

    @Test
    fun `response parses a table with truncated flag`() {
        val body = """{"table":{"columns":["x"],"columnTypes":["integer"],"rows":[["1"]],"totalRows":900},"error":null,"truncated":true}"""
        val resp = json.decodeFromString(PreviewResponse.serializer(), body)
        assertEquals(listOf("x"), resp.table!!.columns)
        assertEquals(900, resp.table!!.totalRows)
        assertEquals(true, resp.truncated)
        assertNull(resp.error)
    }

    @Test
    fun `response parses an error with null table`() {
        val resp = json.decodeFromString(PreviewResponse.serializer(), """{"table":null,"error":"Not a table.","truncated":false}""")
        assertNull(resp.table)
        assertEquals("Not a table.", resp.error)
    }
}
