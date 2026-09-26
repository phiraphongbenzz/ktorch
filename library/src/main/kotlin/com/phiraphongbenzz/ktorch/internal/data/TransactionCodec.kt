package com.phiraphongbenzz.ktorch.internal.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Hand-written JSON mapping for disk storage (no serialization compiler plugin needed).
 * Bodies are stored as decoded text in UTF-8; the original charset is not needed after capture.
 */
internal object TransactionCodec {

    private const val VERSION = 1

    /**
     * Per-body limit for what is written to disk. SQLite rows are read through a 2 MB CursorWindow;
     * with two bodies per row this keeps rows well under it even for multi-byte text.
     */
    private const val MAX_PERSISTED_BODY_CHARS = 200_000
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(t: HttpTransaction): String = buildJsonObject {
        put("v", VERSION)
        put("id", t.id)
        put("requestTime", t.requestTimeMillis)
        put("method", t.method)
        put("url", t.url)
        put("scheme", t.scheme)
        put("host", t.host)
        put("path", t.path)
        put("requestHeaders", headers(t.requestHeaders))
        put("requestBody", body(t.requestBody))
        t.responseCode?.let { put("code", it) }
        t.responseMessage?.let { put("message", it) }
        t.protocol?.let { put("protocol", it) }
        put("responseHeaders", headers(t.responseHeaders))
        t.responseBody?.let { put("responseBody", body(it)) }
        t.headersMillis?.let { put("headersMillis", it) }
        t.totalMillis?.let { put("totalMillis", it) }
        t.error?.let { put("error", it) }
    }.toString()

    /** Returns `null` for anything unreadable: a bad row must not break the list. */
    fun decode(text: String): HttpTransaction? = try {
        val o = json.parseToJsonElement(text).jsonObject
        HttpTransaction(
            id = o.long("id")!!,
            requestTimeMillis = o.long("requestTime") ?: 0L,
            method = o.string("method") ?: "?",
            url = o.string("url").orEmpty(),
            scheme = o.string("scheme").orEmpty(),
            host = o.string("host").orEmpty(),
            path = o.string("path").orEmpty(),
            requestHeaders = headers(o["requestHeaders"]),
            requestBody = (o["requestBody"] as? JsonObject)?.let(::body) ?: Body.EMPTY,
            responseCode = o["code"]?.jsonPrimitive?.intOrNull,
            responseMessage = o.string("message"),
            protocol = o.string("protocol"),
            responseHeaders = headers(o["responseHeaders"]),
            responseBody = (o["responseBody"] as? JsonObject)?.let(::body),
            headersMillis = o.long("headersMillis"),
            totalMillis = o.long("totalMillis"),
            error = o.string("error"),
        )
    } catch (t: Throwable) {
        null
    }

    private fun headers(list: List<Header>): JsonArray = buildJsonArray {
        list.forEach { add(buildJsonArray { add(JsonPrimitive(it.name)); add(JsonPrimitive(it.value)) }) }
    }

    private fun headers(element: kotlinx.serialization.json.JsonElement?): List<Header> =
        (element as? JsonArray)?.mapNotNull { pair ->
            val items = pair.jsonArray
            val name = items.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Header(name, items.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty())
        }.orEmpty()

    private fun body(b: Body): JsonObject = buildJsonObject {
        val text = b.text
        val cut = text != null && text.length > MAX_PERSISTED_BODY_CHARS
        put("state", if (cut) BodyState.TRUNCATED.name else b.state.name)
        b.contentType?.let { put("type", it) }
        b.size?.let { put("size", it) }
        text?.let { put("text", if (cut) it.substring(0, MAX_PERSISTED_BODY_CHARS) else it) }
    }

    private fun body(o: JsonObject): Body {
        val state = runCatching { BodyState.valueOf(o.string("state")!!) }.getOrDefault(BodyState.FAILED)
        return Body(
            state = state,
            contentType = o.string("type"),
            size = o.long("size"),
            bytes = o.string("text")?.toByteArray(Charsets.UTF_8),
            charset = Charsets.UTF_8,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
