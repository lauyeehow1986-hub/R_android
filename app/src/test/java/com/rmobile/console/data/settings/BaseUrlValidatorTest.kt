package com.rmobile.console.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlValidatorTest {

    @Test
    fun `keeps a well-formed url and ensures trailing slash`() {
        assertEquals("http://10.0.2.2:8000/", BaseUrlValidator.normalize("http://10.0.2.2:8000/"))
        assertEquals("http://10.0.2.2:8000/", BaseUrlValidator.normalize("http://10.0.2.2:8000"))
    }

    @Test
    fun `assumes http when scheme is missing`() {
        assertEquals("http://192.168.1.5:8000/", BaseUrlValidator.normalize("192.168.1.5:8000"))
    }

    @Test
    fun `preserves https and path prefixes`() {
        assertEquals("https://example.com/api/", BaseUrlValidator.normalize("https://example.com/api"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("http://host:8000/", BaseUrlValidator.normalize("  http://host:8000/  "))
    }

    @Test
    fun `rejects blank and non-http input`() {
        assertNull(BaseUrlValidator.normalize(""))
        assertNull(BaseUrlValidator.normalize("   "))
        assertNull(BaseUrlValidator.normalize("ftp://example.com"))
        assertNull(BaseUrlValidator.normalize("http://"))
    }
}
