package com.rmobile.console.ui.projects

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectArchive
import java.io.File

/**
 * Writes [project] as a `.zip` into the app cache and launches a share sheet for
 * it via the app's [FileProvider]. Best-effort (toast on failure).
 */
fun shareProjectZip(context: Context, project: Project) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val file = File(dir, "$safe.zip")
        file.writeBytes(ProjectArchive.export(project))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share project"))
    }.onFailure {
        Toast.makeText(context, "Couldn't share the project.", Toast.LENGTH_SHORT).show()
    }
}
