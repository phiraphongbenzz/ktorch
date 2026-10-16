package com.phiraphongbenzz.ktorch

import android.content.Context
import android.content.ContextWrapper
import com.phiraphongbenzz.ktorch.internal.capture.BoundedBuffer
import com.phiraphongbenzz.ktorch.internal.capture.Redactor
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.Header
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.data.PersistenceSync
import com.phiraphongbenzz.ktorch.internal.data.TransactionCodec
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.format.CurlFormatter
import com.phiraphongbenzz.ktorch.internal.format.JsonFormatter
import com.phiraphongbenzz.ktorch.internal.format.StatusFilter
import com.phiraphongbenzz.ktorch.internal.format.TextExporter
import com.phiraphongbenzz.ktorch.internal.format.filterBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun `pretty JSON keeps big numbers, decimals and non-ASCII text exactly`() {
        val raw = """{"id":12345678901234567890,"price":1.50,"exp":1e400,"name":"Café \"v2\"","ok":true,"n":null,"a":[],"o":{}}"""
        val result = JsonFormatter.format(raw)!!.text
        assertEquals(
            """
            {
              "id": 12345678901234567890,
              "price": 1.50,
              "exp": 1e400,
              "name": "Café \"v2\"",
              "ok": true,
              "n": null,
              "a": [],
              "o": {}
            }
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `pretty JSON keeps duplicate keys instead of merging them`() {
        val result = JsonFormatter.format("""{"action":"keep","id":1,"action":"delete"}""")!!.text
        assertEquals(
            """
            {
              "action": "keep",
              "id": 1,
              "action": "delete"
            }
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `pretty JSON shows unicode escapes decoded and keeps required escapes`() {
        val result = JsonFormatter.format("""["caf\u00e9","tab\there","q\"uote","\u0001"]""")!!.text
        assertEquals("[\n  \"café\",\n  \"tab\\there\",\n  \"q\\\"uote\",\n  \"\\u0001\"\n]", result)
    }

    @Test
    fun `malformed or absurdly deep JSON is shown raw`() {
        assertNull(JsonFormatter.format("""{"a":01}"""))
        assertNull(JsonFormatter.format("""{"a":1,}"""))
        assertNull(JsonFormatter.format("""{"a":"x"} trailing"""))
        assertNull(JsonFormatter.format("[".repeat(10_000) + "]".repeat(10_000)))
        assertNotNull(JsonFormatter.format("\uFEFF{\"a\":1}"))
    }

    @Test
    fun `JSON field redaction keeps everything else byte for byte`() {
        val redactor = Redactor(emptySet(), emptySet(), setOf("password", "token"))
        assertEquals(
            "{ \"user\": \"ann\",  \"password\" : \"***\", \"n\": 1.50, \"n\": 2 }",
            redactor.jsonFields("{ \"user\": \"ann\",  \"password\" : \"p\\\"w\", \"n\": 1.50, \"n\": 2 }"),
        )
        // Escaped key, nested value replaced whole, truncated secret cut to the end, BOM prefix.
        assertEquals("""{"pass\u0077ord":"***"}""", redactor.jsonFields("""{"pass\u0077ord":1}"""))
        assertEquals("""{"token":"***","ok":true}""", redactor.jsonFields("""{"token":{"a":[1,{"b":"}"}]},"ok":true}"""))
        assertEquals("""{"token":"***"""", redactor.jsonFields("""{"token":"abc"""))
        assertEquals("\uFEFF{\"token\":\"***\"}", redactor.jsonFields("\uFEFF{\"token\":\"abc\"}"))
        // Not JSON, or nothing to redact: the very same string comes back.
        val text = "token=abc"
        assertTrue(redactor.jsonFields(text) === text)
        assertEquals("a=1&password=***", redactor.formFields("a=1&password=secret"))
    }

    @Test
    fun `curl keeps bang characters literal inside double quotes`() {
        val t = transaction(
            method = "POST",
            headers = listOf(Header("Content-Type", "application/x-www-form-urlencoded")),
            body = Body(BodyState.CAPTURED, "application/x-www-form-urlencoded", 30, "note=hi!&signature=***".toByteArray()),
        )
        val curl = CurlFormatter.format(t)
        assertTrue(curl, curl.contains("""--data-raw "note=hi"'!'"&signature=${'$'}SIGNATURE""""))
    }

    @Test
    fun `codec caps very large bodies so rows fit in a CursorWindow`() {
        val huge = "x".repeat(300_000)
        val t = transaction(code = 200).copy(responseBody = Body(BodyState.CAPTURED, "text/plain", 300_000, huge.toByteArray()))
        val back = TransactionCodec.decode(TransactionCodec.encode(t))!!
        assertEquals(BodyState.TRUNCATED, back.responseBody!!.state)
        assertEquals(200_000, back.responseBody!!.text!!.length)
        assertEquals(300_000L, back.responseBody!!.size)
    }

    @Test
    fun `invalid JSON returns null so the raw text is shown`() {
        assertNull(JsonFormatter.format("""{"a":1"""))
        assertNull(JsonFormatter.format("not json"))
    }

    @Test
    fun `highlight spans cover keys and values`() {
        val result = JsonFormatter.format("""{"k":"v","n":1}""")!!
        val kinds = result.spans.map { result.text.substring(it.start, it.end) to it.kind }
        assertEquals(
            listOf(
                "\"k\"" to JsonFormatter.Kind.KEY, "\"v\"" to JsonFormatter.Kind.STRING,
                "\"n\"" to JsonFormatter.Kind.KEY, "1" to JsonFormatter.Kind.NUMBER,
            ),
            kinds,
        )
    }

    @Test
    fun `curl escapes quotes and keeps real header values when not redacted`() {
        val t = transaction(
            method = "POST",
            headers = listOf(Header("Authorization", "Bearer abc"), Header("Content-Type", "application/json")),
            body = Body(BodyState.CAPTURED, "application/json", 11, """{"a":"it's"}""".toByteArray()),
        )
        val curl = CurlFormatter.format(t)
        assertEquals(
            """
            curl -X POST \
              'https://api.example.com/items?id=1' \
              -H 'Authorization: Bearer abc' \
              -H 'Content-Type: application/json' \
              --data-raw '{"a":"it'\''s"}'
            """.trimIndent(),
            curl,
        )
    }

    @Test
    fun `curl notes bodies that were not captured`() {
        val t = transaction(method = "POST", body = Body(BodyState.STREAMING, "multipart/form-data", 1024))
        assertTrue(CurlFormatter.format(t).startsWith("# NOTE: request body not captured"))
    }

    @Test
    fun `compact copy has status, method and url, request headers, body kind and the pretty response`() {
        val t = transaction(code = 200, headers = listOf(Header("Accept", "application/json"), Header("Authorization", "***")))
            .copy(responseBody = Body(BodyState.CAPTURED, "application/json", 18, """{"id":1,"ok":true}""".toByteArray()))
        assertEquals(
            """
            [200] [GET] https://api.example.com/items?id=1

            [Header]
            Accept: application/json
            Authorization: ***

            [Body] none

            [Response]
            {
              "id": 1,
              "ok": true
            }
            """.trimIndent(),
            TextExporter.compact(t),
        )
    }

    @Test
    fun `compact copy shows the error when there is no response`() {
        val t = transaction(method = "POST").copy(error = "ConnectException: Connection refused", totalMillis = 5)
        assertEquals(
            "[Failed] [POST] https://api.example.com/items?id=1\n\n[Header]\n(none)\n\n[Body] none\n\n" +
                "[Response]\nError: ConnectException: Connection refused",
            TextExporter.compact(t),
        )
    }

    @Test
    fun `compact copy names the request body kind and shows its content`() {
        fun copy(type: String, text: String, state: BodyState = BodyState.CAPTURED) =
            TextExporter.compact(transaction(method = "POST", body = Body(state, type, text.length.toLong(), text.toByteArray())))
                .substringAfter("[Body] ").substringBefore("\n\n[Response]")

        assertEquals(
            "x-www-form-urlencoded\nusername: dekd\nnote: สวัสดี & hello",
            copy("application/x-www-form-urlencoded", "username=dekd&note=%E0%B8%AA%E0%B8%A7%E0%B8%B1%E0%B8%AA%E0%B8%94%E0%B8%B5+%26+hello"),
        )
        assertEquals(
            "form-data\ntitle: My novel\nfile: <file a.bin, image/png>",
            copy("multipart/form-data; boundary=x", "title: My novel\nfile: <file a.bin, image/png>", BodyState.FIELDS),
        )
        assertEquals("raw (JSON)\n{\n  \"a\": 1\n}", copy("application/json; charset=UTF-8", """{"a":1}"""))
        assertEquals("raw (Text)\nhello", copy("text/plain", "hello"))
        assertEquals("raw (XML)\n<a/>", copy("application/xml", "<a/>"))
    }

    @Test
    fun `compact copy can keep JSON raw so a huge body still fits the clipboard`() {
        val t = transaction(code = 200).copy(responseBody = Body(BodyState.CAPTURED, "application/json", 7, """{"a":1}""".toByteArray()))
        assertTrue(TextExporter.compact(t, pretty = false).endsWith("[Response]\n{\"a\":1}"))
    }

    @Test
    fun `truncated JSON is pretty-printed up to the last complete value`() {
        val cut = """{"page":1,"data":[{"id":1,"name":"a"},{"id":2,"na"""
        assertNull(JsonFormatter.format(cut))
        val result = JsonFormatter.format(cut, partial = true)!!
        assertEquals(false, result.complete)
        assertEquals(
            """
            {
              "page": 1,
              "data": [
                {
                  "id": 1,
                  "name": "a"
                },
                {
                  "id": 2
            """.trimIndent(),
            result.text,
        )
        // The root, "data" and the second item were cut and run to the end; the first item closed.
        assertEquals(3, result.containers.count { it.close < 0 })
        assertEquals(4, result.containers.size)
    }

    @Test
    fun `a number at the cut is dropped, it may have lost digits`() {
        assertEquals("{\n  \"a\": 1", JsonFormatter.format("""{"a":1,"b":12""", partial = true)!!.text)
    }

    @Test
    fun `partial mode still rejects JSON that is broken before the cut`() {
        assertNull(JsonFormatter.format("""{"a":1,,"b":2""", partial = true))
        assertNull(JsonFormatter.format("hello", partial = true))
    }

    @Test
    fun `config keeps only the application context`() {
        val app = object : ContextWrapper(null) {}
        val activity = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = app
        }
        val config = NetworkInspectorConfig().apply { context = activity }
        assertSame("an Activity passed in must not be retained by the client", app, config.context)
    }

    @Test
    fun `bounded buffer never grows past its limit`() {
        val buffer = BoundedBuffer(10)
        buffer.write(ByteArray(8) { 1 }, 8)
        buffer.write(ByteArray(8) { 2 }, 8)
        assertEquals(10, buffer.size)
        assertTrue(buffer.overflowed)
        assertEquals(10, buffer.toByteArray().size)
    }

    @Test
    fun `filter matches URL substring and status class`() {
        val list = listOf(
            transaction(id = 1, code = 200, url = "https://a.com/api/items"),
            transaction(id = 2, code = 404, url = "https://a.com/api/missing"),
            transaction(id = 3, code = 500, url = "https://a.com/other"),
        )
        assertEquals(listOf(1L, 2L), list.filterBy("API", StatusFilter.ALL).map { it.id })
        assertEquals(listOf(2L), list.filterBy("", StatusFilter.CLIENT_ERROR).map { it.id })
        assertEquals(listOf(3L), list.filterBy("", StatusFilter.SERVER_ERROR).map { it.id })
    }

    @Test
    fun `codec round-trips a transaction and survives garbage`() {
        val t = transaction(code = 200, body = Body(BodyState.CAPTURED, "application/json", 7, "{\"a\":1}".toByteArray()))
            .copy(responseBody = Body(BodyState.TRUNCATED, "text/plain", 999, "Grüße aus Köln".toByteArray()), totalMillis = 12)
        val back = TransactionCodec.decode(TransactionCodec.encode(t))!!
        assertEquals(t.copy(requestBody = back.requestBody, responseBody = back.responseBody), back)
        assertEquals("{\"a\":1}", back.requestBody.text)
        assertEquals("Grüße aus Köln", back.responseBody!!.text)
        assertEquals(BodyState.TRUNCATED, back.responseBody!!.state)
        assertNull(TransactionCodec.decode("{broken"))
        assertNull(TransactionCodec.decode("""{"no_id":true}"""))
    }

    @Test
    fun `a record saved mid-request is restored as interrupted, not pending`() {
        val pending = transaction(code = 200).copy(responseBody = Body(BodyState.PENDING, "application/json", null))
        val fixed = PersistenceSync.markInterrupted(pending)
        assertTrue(fixed.isFinished)
        assertEquals(BodyState.FAILED, fixed.responseBody!!.state)
        assertNotNull(fixed.error)
    }

    @Test
    fun `restore merges saved records without duplicates and respects capacity`() {
        val store = TransactionStore(3)
        store.add(transaction(id = 10))
        store.restore(listOf(transaction(id = 10), transaction(id = 9), transaction(id = 8), transaction(id = 7)))
        assertEquals(listOf(10L, 9L, 8L), store.transactions.value.map { it.id })
    }

    private fun transaction(
        id: Long = 1,
        method: String = "GET",
        code: Int? = null,
        url: String = "https://api.example.com/items?id=1",
        headers: List<Header> = emptyList(),
        body: Body = Body.EMPTY,
    ) = HttpTransaction(
        id = id,
        requestTimeMillis = 0,
        method = method,
        url = url,
        scheme = "https",
        host = "api.example.com",
        path = "/items?id=1",
        requestHeaders = headers,
        requestBody = body,
        responseCode = code,
    )
}
