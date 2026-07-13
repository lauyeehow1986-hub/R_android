package com.rmobile.console.data.execution

import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.PreviewResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeResponseParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `bridge output json deserializes into ExecuteResponse`() {
        val sample = """
          {"stdout":"[1] 2\n","stderr":"","plots":["iVBORw0KGgo="],
           "tables":[{"columns":["a"],"columnTypes":["numeric"],"rows":[["1"]],"totalRows":1}],
           "workspaceObjects":["x"],"error":null,"timedOut":false}
        """.trimIndent()

        val response = json.decodeFromString<ExecuteResponse>(sample)

        assertEquals("[1] 2\n", response.stdout)
        assertEquals(listOf("iVBORw0KGgo="), response.plots)
        assertEquals(1, response.tables.single().totalRows)
        assertEquals(listOf("a"), response.tables.single().columns)
        assertEquals(listOf("x"), response.workspaceObjects)
        assertFalse(response.timedOut)
    }

    @Test fun `bridge preview json deserializes into PreviewResponse with rows`() {
        // The exact string the D3 diagnostic showed reaching the app on-device.
        val sample = """
          {"table":{"columns":["name","score"],"columnTypes":["character","integer"],
           "rows":[["alice","90"],["bob","75"],["carol","88"]],"totalRows":3},
           "error":null,"truncated":false}
        """.trimIndent()

        val response = json.decodeFromString<PreviewResponse>(sample)

        assertEquals(listOf("name", "score"), response.table!!.columns)
        assertEquals(3, response.table!!.rows.size)
        assertEquals(listOf("alice", "90"), response.table!!.rows.first())
        assertEquals(3, response.table!!.totalRows)
    }
}
