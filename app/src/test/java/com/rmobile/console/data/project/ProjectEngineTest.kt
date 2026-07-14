package com.rmobile.console.data.project

import com.rmobile.console.data.settings.ExecutionEngineChoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `legacy project json without engine deserializes to null engine`() {
        val legacy = """
          {"id":5,"name":"Old","files":[{"name":"main.R","content":"1"}],
           "activeFileName":"main.R","entryFileName":"main.R","updatedAt":1}
        """.trimIndent()
        val p = json.decodeFromString(Project.serializer(), legacy)
        assertNull(p.engine)
    }

    @Test fun `engine round-trips through json`() {
        val p = ProjectOps.newProject(1, "P", 1, engine = ExecutionEngineChoice.REMOTE)
        val back = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), p))
        assertEquals(ExecutionEngineChoice.REMOTE, back.engine)
    }

    @Test fun `newProject stamps the passed engine`() {
        assertEquals(ExecutionEngineChoice.LOCAL, ProjectOps.newProject(1, "P", 1, ExecutionEngineChoice.LOCAL).engine)
    }

    @Test fun `newProject defaults engine to null when unspecified`() {
        assertNull(ProjectOps.newProject(1, "P", 1).engine)
    }
}
