package com.phiraphongbenzz.ktorch

import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.format.CurlFormatter
import com.phiraphongbenzz.ktorch.internal.format.TextExporter
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class CaptureTest {

    private val json = """{"item_id":42,"revision":12345678901234567890,"name":"Café","files":[1,2]}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8")

    @Test
    fun `response body reaches the app unchanged and is recorded`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders) }, store)

        val body = client.get("https://api.example.com/api/items?id=42").bodyAsText()

        assertEquals(json, body)
        val t = store.awaitFinished().single()
        assertEquals(200, t.responseCode)
        assertEquals(BodyState.CAPTURED, t.responseBody!!.state)
        assertEquals(json, t.responseBody!!.text)
        assertEquals("/api/items?id=42", t.path)
        assertEquals("api.example.com", t.host)
        assertNotNull(t.totalMillis)
    }

    @Test
    fun `app can still read a saved body more than once`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders) }, store)

        val response = client.get("https://api.example.com/api/items")

        // Ktor 3 keeps non-streaming bodies in memory; reading twice must keep working.
        assertEquals(json, response.bodyAsText())
        assertEquals(json, response.bodyAsText())
        assertEquals(json, store.awaitFinished().single().responseBody!!.text)
    }

    @Test
    fun `large streamed download arrives intact while capture stays within the cap`() = runBlocking {
        val size = 20 * 1024 * 1024
        val expected = ByteArray(size) { (it * 31).toByte() }
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine {
                respond(
                    content = GlobalScope.writer(Dispatchers.IO) {
                        var sent = 0
                        while (sent < size) {
                            val n = minOf(64 * 1024, size - sent)
                            channel.writeFully(expected, sent, sent + n) // (array, startIndex, endIndex)
                            sent += n
                        }
                    }.channel,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"), // text, unknown length: teed
                )
            },
            store,
        ) { maxContentLength = 250_000 }

        val received = client.prepareGet("https://files.example.com/big.txt").execute { it.bodyAsChannel().readRemainingBytes() }

        assertEquals(sha256(expected), sha256(received))
        val body = store.awaitFinished().single().responseBody!!
        assertEquals(BodyState.TRUNCATED, body.state)
        assertEquals(250_000, body.bytes!!.size)
        assertEquals(size.toLong(), body.size)
    }

    @Test
    fun `skipped host and binary content are never read by the plugin`() = runBlocking {
        val store = TransactionStore(100)
        val pdf = ByteArray(3 * 1024 * 1024) { it.toByte() }
        val client = testClient(
            MockEngine { request ->
                val type = if (request.url.encodedPath.endsWith(".pdf")) "application/pdf" else "application/json"
                respond(pdf, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type))
            },
            store,
        ) { skipHosts += "storage.example.com" }

        val skipped = client.get("https://cdn.storage.example.com/signed/file.json").bodyAsBytes()
        val binary = client.get("https://api.example.com/report.pdf").bodyAsBytes()

        assertArrayEquals(pdf, skipped)
        assertArrayEquals(pdf, binary)
        val (second, first) = store.awaitFinished(2)
        assertEquals(BodyState.SKIPPED, first.responseBody!!.state)
        assertNull(first.responseBody!!.bytes)
        assertEquals(BodyState.BINARY, second.responseBody!!.state)
        assertNull(second.responseBody!!.bytes)
    }

    @Test
    fun `leaving execute early does not wait on an idle stream`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine {
                respond(
                    // Like SSE or long polling: some bytes, then the server goes quiet.
                    GlobalScope.writer(Dispatchers.IO) {
                        channel.writeFully("data: hello\n\n".toByteArray())
                        channel.flush()
                        awaitCancellation()
                    }.channel,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
            store,
        )

        // The app looks at the status only; Ktor's cleanup must not hang on the unread body.
        val status = withTimeout(5_000) {
            client.prepareGet("https://api.example.com/events").execute { it.status }
        }

        assertEquals(HttpStatusCode.OK, status)
        val t = store.awaitFinished().single()
        assertNull("the app stopping early is not a network error", t.error)
    }

    @Test
    fun `JSON body fields are redacted at any depth, the app still gets the real body`() = runBlocking {
        val secret = "s3cr3t-value"
        val responseJson = """{"access_token":"$secret","user":{"name":"Ann","refresh_token":"$secret"}}"""
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond(responseJson, HttpStatusCode.OK, jsonHeaders) }, store) {
            redactBodyFields += setOf("password", "access_token", "Refresh_Token")
        }

        val received = client.post("https://api.example.com/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ann@example.com","password":"$secret","devices":[{"password":"$secret"}]}""")
        }.bodyAsText()

        assertEquals(responseJson, received)
        val t = store.awaitFinished().single()
        listOf(t.requestBody.text, t.responseBody!!.text, CurlFormatter.format(t), TextExporter.format(t)).forEach {
            assertFalse("secret leaked in: $it", it!!.contains(secret))
        }
        assertEquals(
            """{"email":"ann@example.com","password":"***","devices":[{"password":"***"}]}""",
            t.requestBody.text,
        )
        assertEquals("""{"access_token":"***","user":{"name":"Ann","refresh_token":"***"}}""", t.responseBody!!.text)
    }

    @Test
    fun `headers are not redacted by default, like Chucker`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders) }, store)

        client.get("https://api.example.com/me") { header(HttpHeaders.Authorization, "Bearer live-token") }.bodyAsText()

        assertEquals("Bearer live-token", store.awaitFinished().single().requestHeader(HttpHeaders.Authorization))
    }

    @Test
    fun `redacted values never reach the store, cURL or text export`() = runBlocking {
        val secret = "s3cr3t-token-value"
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "session=$secret")) },
            store,
        ) {
            redactHeaders += setOf("Authorization", "Set-Cookie")
            redactQueryParams += "signature"
        }

        client.post("https://api.example.com/sync?signature=$secret&id=1") {
            header(HttpHeaders.Authorization, "Bearer $secret")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("signature=$secret&device=a")
        }.bodyAsText()

        val t = store.awaitFinished().single()
        val everything = listOf(t.toString(), t.requestBody.text, CurlFormatter.format(t), TextExporter.format(t))
        everything.forEach { assertFalse("secret leaked in: $it", it!!.contains(secret)) }
        assertEquals("Bearer ***", t.requestHeader(HttpHeaders.Authorization))
        assertTrue(t.url.contains("signature=***"))
        val curl = CurlFormatter.format(t)
        assertTrue(curl, curl.contains("-H \"Authorization: Bearer \$TOKEN\""))
        assertTrue(curl, curl.contains("signature=\$SIGNATURE"))
    }

    @Test
    fun `ring buffer keeps exactly the configured number of records`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders) }, store) {
            maxTransactions = 50
        }

        (1..500).map { i -> async(Dispatchers.IO) { client.get("https://api.example.com/items/$i").bodyAsText() } }.awaitAll()

        val list = store.awaitFinished(50)
        assertEquals(50, list.size)
        assertEquals(50, list.map { it.id }.distinct().size)
    }

    @Test
    fun `identical concurrent requests are correlated by id, not URL`() = runBlocking {
        val store = TransactionStore(500)
        val client = testClient(
            MockEngine { request ->
                val n = request.headers["X-Request"]!!
                kotlinx.coroutines.delay((0..20L).random())
                respond("""{"n":$n}""", HttpStatusCode.OK, jsonHeaders)
            },
            store,
        ) { maxTransactions = 500 }

        (1..200).map { n ->
            async(Dispatchers.IO) { client.get("https://api.example.com/same") { header("X-Request", n) }.bodyAsText() }
        }.awaitAll()

        val list = store.awaitFinished(200)
        list.forEach { t -> assertEquals("""{"n":${t.requestHeader("X-Request")}}""", t.responseBody!!.text) }
    }

    @Test
    fun `engine failure reaches the app unchanged and is recorded`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { throw IOException("connection reset") }, store)

        try {
            client.get("https://api.example.com/fail")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        val t = store.awaitFinished().single()
        assertTrue(t.error!!.contains("connection reset"))
    }

    @Test
    fun `failure in the middle of the body reaches the app`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine {
                respond(
                    GlobalScope.writer(Dispatchers.IO) {
                        channel.writeFully("partial".toByteArray())
                        channel.flush()
                        throw IOException("socket closed")
                    }.channel,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            },
            store,
        )

        val result = runCatching {
            client.prepareGet("https://api.example.com/drop").execute { it.bodyAsChannel().readRemainingBytes() }
        }

        assertTrue("app must see the failure", result.isFailure)
        // Ktor drops bytes still buffered when the writer fails, so the capture may hold a
        // partial body or none at all. Either way the record must be closed, never pending.
        val t = store.awaitFinished().single()
        assertTrue(t.responseBody!!.state in setOf(BodyState.TRUNCATED, BodyState.FAILED))
        assertNotNull(t.error)
    }

    @Test
    fun `a throwing logger or skip predicate never breaks the request`() = runBlocking {
        val client = testClient(MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders) }) {
            logger = { throw IllegalStateException("broken logger") }
            skipWhen = { throw IllegalStateException("broken predicate") }
        }
        assertEquals(json, client.get("https://api.example.com/x").bodyAsText())
    }

    @Test
    fun `multipart upload is recorded as metadata only`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }, store)

        client.submitFormWithBinaryData(
            url = "https://api.example.com/upload",
            formData = formData {
                append("file", ByteArray(512 * 1024) { 1 }, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"a.bin\"")
                })
            },
        ).bodyAsText()

        val body = store.awaitFinished().single().requestBody
        assertEquals(BodyState.STREAMING, body.state)
        assertNull(body.bytes)
        assertTrue(body.contentType!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `declared charset is honoured and bad bytes never throw`() = runBlocking {
        val text = "Crème brûlée"
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/latin1") {
                    respond(text.toByteArray(Charsets.ISO_8859_1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain; charset=ISO-8859-1"))
                } else {
                    respond(byteArrayOf(0xC3.toByte(), 0x28, 0x41), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain; charset=nope"))
                }
            },
            store,
        )

        client.get("https://api.example.com/latin1").bodyAsBytes()
        client.get("https://api.example.com/bad").bodyAsBytes()

        val (bad, latin1) = store.awaitFinished(2)
        assertEquals(text, latin1.responseBody!!.text)
        assertNotNull(bad.responseBody!!.text)
    }

    @Test
    fun `gzip encoded bodies are decoded for display`() = runBlocking {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine {
                respond(gz, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip")))
            },
            store,
        )

        assertArrayEquals(gz, client.get("https://api.example.com/gz").bodyAsBytes())
        assertEquals(json, store.awaitFinished().single().responseBody!!.text)
    }

    @Test
    fun `404 and 500 are recorded with their bodies`() = runBlocking {
        val store = TransactionStore(100)
        val client = testClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/404") respond("""{"e":1}""", HttpStatusCode.NotFound, jsonHeaders)
                else respondError(HttpStatusCode.InternalServerError, """{"e":2}""", jsonHeaders)
            },
            store,
        )
        client.get("https://api.example.com/404").bodyAsText()
        client.get("https://api.example.com/500").bodyAsText()

        val (e500, e404) = store.awaitFinished(2)
        assertEquals(404, e404.responseCode)
        assertEquals("""{"e":1}""", e404.responseBody!!.text)
        assertEquals(500, e500.responseCode)
    }

    private suspend fun ByteReadChannel.readRemainingBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
