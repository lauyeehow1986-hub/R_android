package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `install request uses the package json key`() {
        val encoded = json.encodeToString(InstallRequest(packageName = "praise"))
        assertTrue(encoded.contains("\"package\":\"praise\""))
    }

    @Test
    fun `install response decodes installed and systemRequirements`() {
        val r = json.decodeFromString<InstallResponse>("""{"installed":true,"stdout":"ok"}""")
        assertTrue(r.installed)
        assertEquals("ok", r.stdout)
        assertNull(r.systemRequirements)
    }

    @Test
    fun `packages response decodes a list`() {
        val r = json.decodeFromString<PackagesResponse>("""{"packages":["praise","glue"]}""")
        assertEquals(listOf("praise", "glue"), r.packages)
    }
}
