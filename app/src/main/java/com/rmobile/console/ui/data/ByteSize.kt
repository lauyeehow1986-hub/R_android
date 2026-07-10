package com.rmobile.console.ui.data

import kotlin.math.roundToInt

/** Formats a byte count as a short human-readable string, e.g. "1.5 KB", "1 GB". */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "${if (bytes < 0) 0 else bytes} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    val rounded = (value * 10).roundToInt() / 10.0
    val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    return "$text ${units[i]}"
}
