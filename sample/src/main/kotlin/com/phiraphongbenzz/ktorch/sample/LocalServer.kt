package com.phiraphongbenzz.ktorch.sample

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay

/** Deterministic, offline test server for every scenario the sample demonstrates. */
object LocalServer {
    const val PORT = 8765
    const val BASE = "http://127.0.0.1:$PORT"

    fun start() {
        embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            routing {
                // A sync-style response: big ids, decimals, escaped quotes, nested arrays.
                get("/api/items") {
                    val item = call.request.queryParameters["id"] ?: "0"
                    call.respondText(
                        """{"item_id":$item,"revision":12345678901234567890,"price":1.50,""" +
                            """"name":"Sample item \"v2\"","deleted":false,"owner":null,""" +
                            """"files":[{"id":9007199254740993,"type":"pdf","action":"keep"},""" +
                            """{"id":9007199254740994,"type":"zip","action":"delete"}],"meta":{}}""",
                        ContentType.Application.Json,
                    )
                }
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("""{"received_bytes":${body.toByteArray().size},"echo":$body}""", ContentType.Application.Json)
                }
                get("/status/{code}") {
                    val code = call.parameters["code"]?.toIntOrNull() ?: 500
                    call.respondText(
                        """{"error":"status $code","code":$code}""",
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(code),
                    )
                }
                // Nested JSON of about [kb] KB: exercises long bodies and folding.
                get("/big-json/{kb}") {
                    val kb = call.parameters["kb"]?.toIntOrNull() ?: 100
                    call.respondText(bigJson(kb * 1024), ContentType.Application.Json)
                }
                post("/form") {
                    val body = call.receiveText()
                    call.respondText("""{"received_bytes":${body.toByteArray().size}}""", ContentType.Application.Json)
                }
                get("/redirect") { call.respondRedirect("/api/items?id=7") }
                get("/slow") {
                    delay(3_000)
                    call.respondText("""{"slow":true}""", ContentType.Application.Json)
                }
                // Large binary download: known length, streamed, never held in memory.
                get("/files/download/{mb}") { streamBytes(call.parameters["mb"], ContentType.Application.OctetStream) }
                // Text with no length: exercises truncation at maxContentLength.
                get("/big-text/{mb}") { streamBytes(call.parameters["mb"], ContentType.Text.Plain, text = true) }
                post("/upload") {
                    var total = 0L
                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem) total += part.provider().toByteArray().size
                        part.dispose()
                    }
                    call.respondText("""{"uploaded_bytes":$total}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    private fun bigJson(bytes: Int): String = buildString {
        append("""{"page":1,"total":0,"data":[""")
        var i = 0
        while (length < bytes) {
            if (i > 0) append(',')
            append("""{"id":$i,"title":"ตอนที่ $i: ทดสอบ","author":{"id":${i * 7},"name":"writer_$i","tags":["novel","fantasy"]},""")
            append(""""stats":{"views":${i * 131},"likes":${i * 17},"chapters":[${(1..5).joinToString(",") { """{"no":$it,"words":${it * 900}}""" }}]}}""")
            i++
        }
        append("]}")
    }

    private suspend fun RoutingContext.streamBytes(
        mb: String?,
        type: ContentType,
        text: Boolean = false,
    ) {
        val total = (mb?.toLongOrNull() ?: 1L) * 1024 * 1024
        val chunk = ByteArray(64 * 1024) { i -> if (text) ('a' + i % 26).code.toByte() else (i * 31).toByte() }
        if (!text) call.response.header(HttpHeaders.ContentLength, total.toString())
        call.respondBytesWriter(contentType = type, contentLength = if (text) null else total) {
            var sent = 0L
            while (sent < total) {
                val n = minOf(chunk.size.toLong(), total - sent).toInt()
                writeFully(chunk, 0, n)
                sent += n
            }
        }
    }
}
