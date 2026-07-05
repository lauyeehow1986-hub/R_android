package com.rmobile.console.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation/normalization for the user-entered backend URL. Pure (no Android
 * dependencies) so it can be unit-tested on the JVM.
 */
object BaseUrlValidator {

    /**
     * Returns a normalized, Retrofit-usable base URL (always ending in `/`) for
     * [input], or `null` if it isn't a usable http(s) URL. A bare `host:port`
     * with no scheme is assumed to be `http://`.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val candidate = if (trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))) {
            trimmed
        } else {
            "http://$trimmed"
        }

        val url = candidate.toHttpUrlOrNull() ?: return null
        val asString = url.toString()
        return if (asString.endsWith("/")) asString else "$asString/"
    }
}
