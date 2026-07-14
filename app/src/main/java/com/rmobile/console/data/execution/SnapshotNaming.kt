package com.rmobile.console.data.execution

/**
 * Pure naming for the per-session WebR snapshot files under filesDir. Kept separate
 * from [WebRController] (which is Android-bound) so it is JVM-unit-testable, and so
 * the migration and the runtime store agree byte-for-byte on filenames.
 */
object SnapshotNaming {
    /** Matches the sanitizer LocalSessionDataStore / WebRController.localDataDir use. */
    fun sanitize(sessionId: String): String =
        sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }

    fun fileName(kind: String, sessionId: String): String {
        val ext = when (kind) {
            "workspace" -> "RData"
            "library" -> "tar.gz"
            else -> error("Unknown snapshot kind: $kind")
        }
        return "webr-$kind-${sanitize(sessionId)}.$ext"
    }
}
