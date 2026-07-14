package com.rmobile.console.data.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream

/** Streams from [openStream] (reopened per writeTo, so it's reusable) without buffering in memory. */
class UriRequestBody(
    private val openStream: () -> InputStream,
    private val contentLength: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = contentLength
    override fun writeTo(sink: BufferedSink) {
        openStream().source().use { sink.writeAll(it) }
    }
}
