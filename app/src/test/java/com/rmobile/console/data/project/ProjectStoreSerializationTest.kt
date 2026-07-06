package com.rmobile.console.data.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectStoreSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `projects round-trip through JSON`() {
        val projects = listOf(
            ProjectOps.newProject(1, "A", now = 1),
            ProjectOps.addFile(ProjectOps.newProject(2, "B", now = 2), "helpers.R", now = 3)!!,
        )
        val restored = json.decodeFromString<List<Project>>(json.encodeToString(projects))
        assertEquals(projects, restored)
    }
}
