package com.rmobile.console.data.network

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * Streams a content [Uri]'s bytes into an OkHttp request body without buffering
 * the whole file in memory. Reopens the stream on each [writeTo], so it is safe
 * to reuse (not one-shot).
 */
class UriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentLength: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        stream.source().use { sink.writeAll(it) }
    }
}
