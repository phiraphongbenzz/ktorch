package com.phiraphongbenzz.ktorch.internal.capture

import com.phiraphongbenzz.ktorch.internal.format.JsonStrings
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter

internal const val REDACTED = "***"

/**
 * Redacts at capture time, so a redacted value never reaches the store, the UI, disk, a log or an
 * export. Like Chucker, nothing is redacted unless the consumer asks for it.
 */
internal class Redactor(
    headers: Set<String>,
    queryParams: Set<String>,
    bodyFields: Set<String> = emptySet(),
) {
    private val headers: Set<String> = headers.mapTo(HashSet()) { it.lowercase() }
    private val params: Set<String> = queryParams.mapTo(HashSet()) { it.lowercase() }
    private val fields: Set<String> = bodyFields.mapTo(HashSet()) { it.lowercase() }

    /** Form bodies are covered by both query-param and body-field names. */
    private val formNames: Set<String> = params + fields

    val redactsBodies: Boolean get() = formNames.isNotEmpty()

    fun header(name: String, value: String): String {
        val key = name.lowercase()
        if (key !in headers) return value
        if (key in SCHEME_HEADERS) {
            // Keep the auth scheme ("Bearer ***"): it is not a secret and it helps debugging.
            val scheme = value.substringBefore(' ', missingDelimiterValue = "")
            if (scheme.isNotEmpty() && scheme.length <= 20 && scheme.all { it.isLetter() || it == '-' }) {
                return "$scheme $REDACTED"
            }
        }
        return REDACTED
    }

    /** Full URL without user info, with redacted query values. */
    fun url(url: Url): String = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.specifiedPort != 0 && url.specifiedPort != url.protocol.defaultPort) {
            append(':').append(url.specifiedPort)
        }
        append(pathAndQuery(url))
        if (url.encodedFragment.isNotEmpty()) append('#').append(url.encodedFragment)
    }

    fun pathAndQuery(url: Url): String {
        val path = url.encodedPath.ifEmpty { "/" }
        val query = url.encodedQuery
        return if (query.isEmpty()) path else "$path?${query(query)}"
    }

    /** Redacts `a=1&token=x` in a URL query. */
    fun query(encoded: String): String = redactPairs(encoded, params)

    /** Redacts an `application/x-www-form-urlencoded` body. */
    fun formFields(encoded: String): String = redactPairs(encoded, formNames)

    private fun redactPairs(encoded: String, names: Set<String>): String {
        if (names.isEmpty() || encoded.isEmpty()) return encoded
        var changed = false
        val result = encoded.split('&').joinToString("&") { part ->
            val rawName = part.substringBefore('=')
            val name = runCatching { rawName.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(rawName)
            if (name.lowercase() in names && part.contains('=')) {
                changed = true
                "$rawName=$REDACTED"
            } else {
                part
            }
        }
        return if (changed) result else encoded
    }

    /**
     * Replaces the value of every JSON object member whose key is a configured body field, at any
     * depth, with `"***"`. Everything else is kept byte for byte (formatting, key order, duplicate
     * keys, number spelling). Truncated input is fine: an unterminated secret is cut to the end.
     * Returns [text] itself when nothing matched or it is not JSON.
     */
    fun jsonFields(text: String): String {
        if (fields.isEmpty()) return text
        // A leading BOM must not hide a JSON body from redaction.
        val first = text.firstOrNull { !it.isWhitespace() && it != '\uFEFF' }
        if (first != '{' && first != '[') return text

        val out = StringBuilder(text.length)
        var i = 0
        var changed = false
        while (i < text.length) {
            val c = text[i]
            if (c != '"') {
                out.append(c)
                i++
                continue
            }
            val end = stringEnd(text, i)
            out.append(text, i, end)
            val token = text.substring(i, end)
            i = end
            // A string followed by ':' is an object key.
            var j = i
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length || text[j] != ':') continue
            val key = JsonStrings.decode(token)?.lowercase() ?: continue
            if (key !in fields) continue
            j++
            while (j < text.length && text[j].isWhitespace()) j++
            out.append(text, i, j).append('"').append(REDACTED).append('"')
            i = valueEnd(text, j)
            changed = true
        }
        return if (changed) out.toString() else text
    }

    /**
     * Removes secrets from free text such as exception messages, which often embed the full
     * request URL (e.g. Ktor's timeout exception).
     */
    fun scrub(text: String, url: Url): String {
        var result = text.replace(url.toString(), url(url))
        if (params.isEmpty()) return result
        url.parameters.entries().forEach { (name, values) ->
            if (name.lowercase() in params) {
                values.filter { it.isNotEmpty() }.forEach { value ->
                    result = result.replace(value.encodeURLParameter(), REDACTED).replace(value, REDACTED)
                }
            }
        }
        return result
    }

    private companion object {
        val SCHEME_HEADERS = setOf("authorization", "proxy-authorization")

        /** Index just past the string starting at [start], or the end of an unterminated one. */
        fun stringEnd(s: String, start: Int): Int {
            var k = start + 1
            while (k < s.length) {
                when (s[k]) {
                    '\\' -> k += 2
                    '"' -> return k + 1
                    else -> k++
                }
            }
            return s.length
        }

        /** Index just past the JSON value starting at [start]; the end of input if it is cut off. */
        fun valueEnd(s: String, start: Int): Int {
            if (start >= s.length) return s.length
            return when (s[start]) {
                '"' -> stringEnd(s, start)
                '{', '[' -> {
                    var depth = 0
                    var k = start
                    while (k < s.length) {
                        when (s[k]) {
                            '"' -> {
                                k = stringEnd(s, k)
                                continue
                            }
                            '{', '[' -> depth++
                            '}', ']' -> if (--depth == 0) return k + 1
                        }
                        k++
                    }
                    s.length
                }
                else -> {
                    var k = start
                    while (k < s.length && s[k] !in ",}] \t\r\n") k++
                    k
                }
            }
        }
    }
}
