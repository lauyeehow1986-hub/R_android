package com.rmobile.console.data.project

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSharedLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `defaults to false`() {
        assertFalse(ProjectOps.newProject(1, "P", 0).sharedLibrary)
    }

    @Test fun `old JSON without the field decodes to false`() {
        val legacy = """{"id":1,"name":"P","files":[],"activeFileName":"a",
            "entryFileName":"a","updatedAt":0}""".trimIndent()
        assertFalse(json.decodeFromString(Project.serializer(), legacy).sharedLibrary)
    }

    @Test fun `round-trips true`() {
        val p = ProjectOps.newProject(1, "P", 0).copy(sharedLibrary = true)
        val back = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), p))
        assertTrue(back.sharedLibrary)
    }
}
