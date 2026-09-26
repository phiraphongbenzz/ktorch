package com.phiraphongbenzz.ktorch.internal.data

import java.nio.charset.Charset

internal data class Header(val name: String, val value: String)

internal enum class BodyState {
    /** No body on the wire (GET, 204, HEAD, ...). */
    EMPTY,

    /** Response body is still being read by the app. */
    PENDING,

    /** Full body captured. */
    CAPTURED,

    /** Only the first `maxContentLength` bytes were kept. */
    TRUNCATED,

    /** Streaming, multipart or unknown-length content: metadata only, never buffered. */
    STREAMING,

    /** Non-text content type (image, PDF, ZIP, ...): metadata only. */
    BINARY,

    /** Matched skipHosts / skipPathPrefixes / skipWhen: metadata only. */
    SKIPPED,

    /** Redirect response, followed by the client: metadata only. */
    REDIRECT,

    /** The body could not be read, e.g. the connection dropped. */
    FAILED,
}

/**
 * A captured body. [bytes] never exceeds the configured cap and is only set for text content.
 * Identity equality on purpose: bodies are immutable and compared by reference in the UI.
 */
internal class Body(
    val state: BodyState,
    val contentType: String? = null,
    /** Bytes seen on the wire, or the declared Content-Length when the body was not read. */
    val size: Long? = null,
    val bytes: ByteArray? = null,
    val charset: Charset = Charsets.UTF_8,
) {
    /** Decoding replaces malformed input instead of throwing. */
    val text: String? by lazy { bytes?.let { String(it, charset) } }

    companion object {
        val EMPTY = Body(BodyState.EMPTY, size = 0)
    }
}

internal data class HttpTransaction(
    val id: Long,
    val requestTimeMillis: Long,
    val method: String,
    /** Redacted: no user info, redacted query values. */
    val url: String,
    val scheme: String,
    val host: String,
    /** Encoded path plus redacted query. */
    val path: String,
    val requestHeaders: List<Header>,
    val requestBody: Body,
    val responseCode: Int? = null,
    val responseMessage: String? = null,
    val protocol: String? = null,
    val responseHeaders: List<Header> = emptyList(),
    val responseBody: Body? = null,
    /** Time from sending the request until response headers arrived. */
    val headersMillis: Long? = null,
    /** Time from sending the request until the response body was fully read. */
    val totalMillis: Long? = null,
    val error: String? = null,
) {
    val isFinished: Boolean get() = totalMillis != null || error != null

    val durationMillis: Long? get() = totalMillis ?: headersMillis

    fun requestHeader(name: String): String? =
        requestHeaders.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    fun responseHeader(name: String): String? =
        responseHeaders.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
}
