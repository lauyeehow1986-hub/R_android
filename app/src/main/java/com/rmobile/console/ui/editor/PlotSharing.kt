package com.rmobile.console.ui.editor

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Decodes a base64 PNG to the app cache and launches a share sheet for it via
 * the app's [FileProvider]. Best-effort: on any failure it shows a toast rather
 * than crashing (the plot is still visible on screen).
 */
fun sharePlotPng(context: Context, base64: String) {
    runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "plot_${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share plot"))
    }.onFailure {
        Toast.makeText(context, "Couldn't share the plot.", Toast.LENGTH_SHORT).show()
    }
}
