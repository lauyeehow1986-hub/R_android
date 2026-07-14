package com.rmobile.console.data.execution

import java.io.File

/**
 * One-time adoption of the pre-per-project shared Local snapshots into the last-open
 * project's per-session snapshot files. Pure file ops so it is JVM-unit-testable.
 * Best-effort: never overwrites an existing per-session file; the rename is its own
 * idempotency marker (once moved, the legacy name is gone).
 */
object LegacyLocalStateMigration {
    private val LEGACY = listOf(
        "workspace" to "webr-workspace.RData",
        "library" to "webr-library.tar.gz",
    )

    fun migrate(filesDir: File, lastOpenSessionId: String?) {
        if (lastOpenSessionId.isNullOrBlank()) return
        for ((kind, legacyName) in LEGACY) {
            val src = File(filesDir, legacyName)
            val dst = File(filesDir, SnapshotNaming.fileName(kind, lastOpenSessionId))
            if (src.exists() && !dst.exists()) {
                runCatching { src.renameTo(dst) }
            }
        }
    }
}
