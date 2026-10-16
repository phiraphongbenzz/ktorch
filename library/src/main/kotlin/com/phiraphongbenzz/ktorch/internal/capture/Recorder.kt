package com.phiraphongbenzz.ktorch.internal.capture

import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.Header
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.format.Formats
import io.ktor.client.call.replaceResponse
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Set by Ktor's built-in SaveBody plugin once the whole body is in memory. The key is internal to
 * Ktor; if a future version renames it, this simply never matches and capture falls back to the
 * tee, which is still byte-exact (a test fails in that case, so the upgrade gets noticed).
 */
private val ResponseBodySaved = AttributeKey<Unit>("ResponseBodySaved")

/**
 * Runs [block] and swallows any failure: the plugin must never fail or alter the real request.
 * Cancellation is the exception: it is the caller's request to stop, so it always propagates.
 */
internal inline fun <T> swallow(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

/**
 * Turns Ktor requests and responses into [HttpTransaction]s. Every public method is called
 * through [swallow] by the plugin, so a bug here costs a record, never a request.
 */
internal class Recorder(
    private val settings: CaptureSettings,
    private val store: TransactionStore,
) {
    private val redactor get() = settings.redactor

    fun onRequest(request: HttpRequestBuilder): Long {
        val url = request.url.build()
        val content = request.body as? OutgoingContent
        val id = store.nextId()
        store.add(
            HttpTransaction(
                id = id,
                requestTimeMillis = System.currentTimeMillis(),
                method = request.method.value,
                url = redactor.url(url),
                scheme = url.protocol.name,
                host = url.host,
                path = redactor.pathAndQuery(url),
                requestHeaders = requestHeaders(request, content),
                requestBody = requestBody(content, settings.shouldSkip(url)),
            ),
        )
        return id
    }

    /**
     * Records status and headers, then the body:
     * - saved by Ktor (the default for non-streaming calls): read our own bounded copy and leave
     *   the response untouched, so the app can still read it as many times as it likes;
     * - streaming (`prepareGet().execute { }`, downloads): return a replacement whose body is teed
     *   into a bounded buffer.
     * Returns `null` when the original response must be used.
     */
    suspend fun onResponse(id: Long, startNanos: Long, response: HttpResponse): HttpResponse? {
        val headersMillis = Formats.elapsedMillis(startNanos)
        val headers = response.headers
        val type = ContentTypes.parse(headers[HttpHeaders.ContentType])
        val typeName = headers[HttpHeaders.ContentType]
        val length = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val status = response.status.value
        val request = response.call.request

        val noBody = request.method == HttpMethod.Head || status in 100..199 ||
            status == 204 || status == 304 || length == 0L
        val body = when {
            noBody -> Body.EMPTY
            settings.shouldSkip(request.url) -> Body(BodyState.SKIPPED, typeName, length)
            status in 300..399 && headers[HttpHeaders.Location] != null -> Body(BodyState.REDIRECT, typeName, length)
            ContentTypes.isText(type) == false -> Body(BodyState.BINARY, typeName, length)
            else -> Body(BodyState.PENDING, typeName, length)
        }
        val tee = body.state == BodyState.PENDING

        store.update(id) {
            it.copy(
                responseCode = status,
                responseMessage = response.status.description,
                protocol = response.version.toString(),
                responseHeaders = redactedHeaders(headers),
                responseBody = body,
                headersMillis = headersMillis,
                totalMillis = if (tee) null else headersMillis,
            )
        }
        if (!tee) {
            logFinished(id)
            return null
        }
        val encoding = headers[HttpHeaders.ContentEncoding]
        if (response.call.attributes.contains(ResponseBodySaved)) {
            captureSaved(id, startNanos, response, type, encoding)
            return null
        }
        return teeBody(id, startNanos, response, type, encoding)
    }

    /** The body is already in memory: every `rawContent` access returns a fresh copy. */
    @OptIn(InternalAPI::class)
    private suspend fun captureSaved(
        id: Long,
        startNanos: Long,
        response: HttpResponse,
        type: ContentType?,
        contentEncoding: String?,
    ) {
        val copy = response.rawContent
        val capture = BoundedBuffer(settings.maxContentLength)
        val chunk = ByteArray(CHUNK_SIZE)
        var total = 0L
        try {
            while (!capture.overflowed) {
                val n = copy.readAvailable(chunk, 0, chunk.size)
                if (n < 0) break
                total += n
                capture.write(chunk, n)
            }
            // Past the cap only the size matters: skip the rest instead of copying it.
            if (capture.overflowed) total += copy.discard()
        } finally {
            copy.cancel(null)
        }
        onBodyFinished(id, startNanos, type, contentEncoding, capture, true, total, null)
    }

    fun onFailure(id: Long, startNanos: Long, url: Url, cause: Throwable) {
        val message = describe(cause, url)
        store.update(id) {
            it.copy(
                error = message,
                totalMillis = it.totalMillis ?: Formats.elapsedMillis(startNanos),
                responseBody = it.responseBody?.let { body ->
                    if (body.state == BodyState.PENDING) Body(BodyState.FAILED, body.contentType, body.size) else body
                },
            )
        }
        logFinished(id)
    }

    /**
     * Copies the response channel into a new one the app reads, keeping at most
     * `maxContentLength` bytes on the side. Nothing beyond the cap is ever held in memory, and
     * the app side receives every byte unchanged, including an upstream failure.
     */
    @OptIn(InternalAPI::class)
    private fun teeBody(
        id: Long,
        startNanos: Long,
        response: HttpResponse,
        type: ContentType?,
        contentEncoding: String?,
    ): HttpResponse {
        val source: ByteReadChannel = response.rawContent
        val sink = ByteChannel(autoFlush = true)
        val appCancelled = AtomicBoolean(false)
        // What the app reads. Cancelling it must also stop the upstream read: Ktor's
        // HttpStatement cleanup cancels this channel after an early return from execute { } and
        // then joins the call, which would otherwise wait on the tee until the server sends more.
        val appSide = object : ByteReadChannel by sink {
            override fun cancel(cause: Throwable?) {
                appCancelled.set(true)
                sink.cancel(cause)
                source.cancel(cause)
            }
        }
        // Build the replacement first: if this throws, nothing has touched `source` yet.
        val replacement = response.call.replaceResponse { appSide }.response
        val capture = BoundedBuffer(settings.maxContentLength)

        val job = response.launch {
            val chunk = ByteArray(CHUNK_SIZE)
            var total = 0L
            var capturing = true
            var failure: Throwable? = null
            try {
                while (true) {
                    val n = source.readAvailable(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (capturing) {
                        capturing = swallow { capture.write(chunk, n) } != null
                    }
                    sink.writeFully(chunk, 0, n) // (array, startIndex, endIndex)
                }
                // Ktor 3 reports an upstream failure as end-of-stream plus closedCause; pass it on
                // so the app sees the same error it would without the plugin.
                source.closedCause?.let { throw it }
                sink.flushAndClose()
            } catch (t: Throwable) {
                // The app stopping early is not a network error; record it as a cancellation.
                failure = if (appCancelled.get()) CancellationException("The app stopped reading the body") else t
                sink.cancel(t)
                source.cancel(t)
            }
            swallow { onBodyFinished(id, startNanos, type, contentEncoding, capture, capturing, total, failure) }
        }
        // Cancelled before it ever ran: make sure the app side does not wait forever.
        job.invokeOnCompletion { cause -> if (cause != null) sink.cancel(cause) }
        return replacement
    }

    private fun onBodyFinished(
        id: Long,
        startNanos: Long,
        type: ContentType?,
        contentEncoding: String?,
        capture: BoundedBuffer,
        captured: Boolean,
        total: Long,
        failure: Throwable?,
    ) {
        val typeName = type?.toString()
        val cap = settings.maxContentLength
        var bytes = if (captured) capture.toByteArray() else ByteArray(0)
        var truncated = capture.overflowed || !captured

        val encoding = contentEncoding?.trim()?.lowercase()
        var binary = false
        if (!encoding.isNullOrEmpty() && encoding != "identity" && bytes.isNotEmpty()) {
            if (encoding == "gzip" || encoding == "x-gzip") {
                val (decoded, overflow) = gunzip(bytes, cap)
                bytes = decoded
                truncated = truncated || overflow
            } else {
                binary = true // br, deflate, zstd: not decoded
            }
        }
        if (ContentTypes.isText(type) == null && ContentTypes.looksBinary(bytes)) binary = true
        val charset = ContentTypes.charset(type)
        if (!binary) bytes = redactBody(bytes, charset, isForm = false)

        val body = when {
            binary -> Body(BodyState.BINARY, typeName, total)
            failure != null && bytes.isEmpty() -> Body(BodyState.FAILED, typeName, total)
            failure != null || truncated -> Body(BodyState.TRUNCATED, typeName, total, bytes, charset)
            total == 0L -> Body(BodyState.EMPTY, typeName, 0)
            else -> Body(BodyState.CAPTURED, typeName, total, bytes, charset)
        }
        val error = failure?.takeUnless { it is CancellationException }?.let { "Body read failed: ${it.javaClass.simpleName}" }
        store.update(id) {
            it.copy(
                responseBody = body,
                totalMillis = Formats.elapsedMillis(startNanos),
                error = it.error ?: error,
            )
        }
        logFinished(id)
    }

    private fun requestHeaders(request: HttpRequestBuilder, content: OutgoingContent?): List<Header> {
        val result = ArrayList<Header>()
        request.headers.entries().forEach { (name, values) ->
            values.forEach { result += Header(name, redactor.header(name, it)) }
        }
        content?.headers?.entries()?.forEach { (name, values) ->
            values.forEach { result += Header(name, redactor.header(name, it)) }
        }
        fun missing(name: String) = result.none { it.name.equals(name, ignoreCase = true) }
        content?.contentType?.let { if (missing(HttpHeaders.ContentType)) result += Header(HttpHeaders.ContentType, it.toString()) }
        content?.contentLength?.let { if (missing(HttpHeaders.ContentLength)) result += Header(HttpHeaders.ContentLength, it.toString()) }
        return result
    }

    private fun redactedHeaders(headers: Headers): List<Header> {
        val result = ArrayList<Header>()
        headers.entries().forEach { (name, values) ->
            values.forEach { result += Header(name, redactor.header(name, it)) }
        }
        return result
    }

    private fun requestBody(content: OutgoingContent?, skip: Boolean): Body {
        if (content == null || content is OutgoingContent.NoContent) return Body.EMPTY
        val type = content.contentType
        val typeName = type?.toString()
        val length = content.contentLength
        if (skip) return Body(BodyState.SKIPPED, typeName, length)
        if (content is MultiPartFormDataContent) return multipartFields(content, typeName, length)
        // TextContent and FormDataContent are ByteArrayContent: already in memory, safe to read.
        // Channel/multipart content is streamed; reading it would consume the upload.
        if (content !is OutgoingContent.ByteArrayContent) return Body(BodyState.STREAMING, typeName, length)

        val all = content.bytes()
        if (all.isEmpty()) return Body.EMPTY
        val isText = ContentTypes.isText(type)
        if (isText == false || (isText == null && ContentTypes.looksBinary(all))) {
            return Body(BodyState.BINARY, typeName, all.size.toLong())
        }
        val cap = settings.maxContentLength
        val charset = ContentTypes.charset(type)
        val kept = redactBody(
            bytes = if (all.size > cap) all.copyOf(cap) else all,
            charset = charset,
            isForm = ContentTypes.isFormUrlEncoded(type),
        )
        val state = if (all.size > cap) BodyState.TRUNCATED else BodyState.CAPTURED
        return Body(state, typeName, all.size.toLong(), kept, charset)
    }

    /**
     * Lists the parts of a multipart form as `name: value` lines. Only the part list is looked
     * at: file contents are providers that would consume the upload, so they are described
     * (file name, type, size), never read.
     */
    @OptIn(InternalAPI::class)
    private fun multipartFields(content: MultiPartFormDataContent, typeName: String?, length: Long?): Body {
        val text = content.parts.joinToString("\n") { part ->
            val name = part.name ?: "(unnamed)"
            val value = when (part) {
                is PartData.FormItem -> redactor.formField(name, part.value)
                else -> {
                    // formData { } adds its own "form-data; name=..." before the caller's
                    // `filename=...`; on the wire they are one header, so parse them as one.
                    val disposition = part.headers.getAll(HttpHeaders.ContentDisposition).orEmpty().joinToString("; ")
                    val fileName = swallow { ContentDisposition.parse(disposition).parameter(ContentDisposition.Parameters.FileName) }
                    val size = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    val details = listOfNotNull(part.contentType?.toString(), size?.let { Formats.bytes(it) })
                    buildString {
                        append("<file")
                        fileName?.let { append(' ').append(it) }
                        if (details.isNotEmpty()) append(", ").append(details.joinToString(", "))
                        append('>')
                    }
                }
            }
            "$name: $value"
        }
        if (text.isEmpty()) return Body(BodyState.STREAMING, typeName, length)
        return Body(BodyState.FIELDS, typeName, length, text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
    }

    /** Applies field redaction to a text body; returns [bytes] itself when nothing matched. */
    private fun redactBody(bytes: ByteArray, charset: Charset, isForm: Boolean): ByteArray {
        if (bytes.isEmpty() || !redactor.redactsBodies) return bytes
        val text = String(bytes, charset)
        val redacted = if (isForm) redactor.formFields(text) else redactor.jsonFields(text)
        return if (redacted === text) bytes else redacted.toByteArray(charset)
    }

    private fun describe(cause: Throwable, url: Url): String {
        if (cause is CancellationException) return "Cancelled"
        val message = cause.message?.let { redactor.scrub(it, url) }
        return if (message.isNullOrBlank()) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $message"
    }

    private fun logFinished(id: Long) {
        settings.log {
            val t = store.get(id) ?: return@log "#$id evicted"
            Formats.summaryLine(t)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 8 * 1024

        /** Decompresses at most [limit] bytes. A truncated stream keeps what could be decoded. */
        fun gunzip(input: ByteArray, limit: Int): Pair<ByteArray, Boolean> {
            val out = BoundedBuffer(limit)
            try {
                GZIPInputStream(ByteArrayInputStream(input)).use { stream ->
                    val chunk = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        out.write(chunk, n)
                        if (out.overflowed) break
                    }
                }
            } catch (t: Throwable) {
                return out.toByteArray() to true
            }
            return out.toByteArray() to out.overflowed
        }
    }
}
