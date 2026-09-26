package com.phiraphongbenzz.ktorch.internal.capture

import io.ktor.http.ContentType
import io.ktor.http.charset
import java.nio.charset.Charset

internal object ContentTypes {

    private val TEXT_SUBTYPES = setOf(
        "json", "xml", "javascript", "x-javascript", "ecmascript", "x-www-form-urlencoded",
        "graphql", "x-ndjson", "ndjson", "yaml", "x-yaml", "csv", "html", "problem+json",
    )

    fun parse(value: String?): ContentType? =
        if (value.isNullOrBlank()) null else runCatching { ContentType.parse(value) }.getOrNull()

    /** `true` = text, `false` = binary, `null` = unknown (no or unparseable content type). */
    fun isText(type: ContentType?): Boolean? {
        type ?: return null
        val main = type.contentType.lowercase()
        val sub = type.contentSubtype.lowercase()
        return when {
            main == "text" -> true
            sub in TEXT_SUBTYPES -> true
            sub.endsWith("+json") || sub.endsWith("+xml") -> true
            else -> false
        }
    }

    fun isJson(type: ContentType?): Boolean {
        val sub = type?.contentSubtype?.lowercase() ?: return false
        return sub == "json" || sub.endsWith("+json")
    }

    fun isFormUrlEncoded(type: ContentType?): Boolean =
        type?.contentSubtype.equals("x-www-form-urlencoded", ignoreCase = true)

    /** Declared charset, UTF-8 when missing or unsupported. Never throws. */
    fun charset(type: ContentType?): Charset =
        runCatching { type?.charset() }.getOrNull() ?: Charsets.UTF_8

    /** Heuristic for bodies without a content type: NUL bytes or many control characters. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 512)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C) control++
        }
        return control * 10 > n
    }
}
