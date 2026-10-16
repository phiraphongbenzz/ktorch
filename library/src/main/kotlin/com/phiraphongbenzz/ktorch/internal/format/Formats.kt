package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import io.ktor.http.decodeURLQueryComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object Formats {

    fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    fun bytes(count: Long?): String = when {
        count == null || count < 0 -> "–"
        count < 1024 -> "$count B"
        count < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", count / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", count / (1024.0 * 1024.0))
    }

    fun duration(millis: Long?): String = when {
        millis == null -> "…"
        millis < 1000 -> "$millis ms"
        else -> String.format(Locale.US, "%.2f s", millis / 1000.0)
    }

    /** Like [duration], but a finished record without timing (interrupted) shows a dash, not "…". */
    fun duration(t: HttpTransaction): String =
        if (t.durationMillis == null && t.isFinished) "–" else duration(t.durationMillis)

    // SimpleDateFormat is not thread-safe; a new instance per call is cheap at this volume.
    fun time(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ZZZZZ", Locale.US).format(Date(millis))

    fun status(t: HttpTransaction): String = when {
        t.responseCode != null -> listOfNotNull(t.responseCode.toString(), t.responseMessage).joinToString(" ")
        t.error != null -> "Failed"
        else -> "Pending"
    }

    /** One redacted line per transaction, for the optional logger. */
    fun summaryLine(t: HttpTransaction): String {
        val head = t.responseCode?.toString() ?: if (t.error != null) "FAILED" else "…"
        val parts = mutableListOf("$head ${t.method} ${t.url}", duration(t))
        t.responseBody?.size?.let { parts += bytes(it) }
        t.error?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /**
     * Text for a body, JSON pretty-printed when [pretty] is set; a truncated JSON body is
     * pretty-printed as far as it goes. `null` when there is no text.
     */
    fun bodyText(body: Body?, pretty: Boolean): String? {
        val text = body?.text ?: return null
        if (!pretty || !JsonFormatter.looksLikeJson(text)) return text
        return JsonFormatter.format(text, partial = body.state == BodyState.TRUNCATED)?.text ?: text
    }

    /**
     * What kind of body this is, in Postman's terms: `none`, `form-data`,
     * `x-www-form-urlencoded`, `raw (JSON)` and the like, or `binary`.
     */
    fun bodyKind(body: Body?): String {
        if (body == null || body.state == BodyState.EMPTY) return "none"
        val type = body.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val sub = type.substringAfter('/')
        return when {
            type == "multipart/form-data" || body.state == BodyState.FIELDS -> "form-data"
            type.startsWith("multipart/") -> type
            sub == "x-www-form-urlencoded" -> "x-www-form-urlencoded"
            sub == "json" || sub.endsWith("+json") || sub == "x-ndjson" || sub == "ndjson" -> "raw (JSON)"
            sub == "xml" || sub.endsWith("+xml") -> "raw (XML)"
            sub == "html" -> "raw (HTML)"
            sub == "javascript" || sub == "x-javascript" || sub == "ecmascript" -> "raw (JavaScript)"
            sub == "graphql" -> "raw (GraphQL)"
            body.state == BodyState.BINARY -> "binary"
            type.startsWith("text/") || type.isEmpty() -> "raw (Text)"
            else -> "raw ($type)"
        }
    }

    /** An `x-www-form-urlencoded` body as decoded `name: value` lines (Postman's bulk edit format). */
    fun formFields(encoded: String): String =
        encoded.split('&').filter { it.isNotEmpty() }.joinToString("\n") { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            "${decode(name)}: ${decode(value)}"
        }

    private fun decode(part: String): String =
        runCatching { part.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(part)

    /** Human explanation for bodies that have no text to show. */
    fun bodyNote(body: Body?): String? {
        body ?: return "Waiting for response…"
        val type = body.contentType ?: "unknown type"
        val size = body.size?.let { ", ${bytes(it)}" } ?: ""
        return when (body.state) {
            BodyState.EMPTY -> "(no body)"
            BodyState.PENDING -> "Receiving body…"
            BodyState.CAPTURED -> null
            BodyState.FIELDS -> null
            BodyState.TRUNCATED -> "Truncated: showing the first ${bytes(body.bytes?.size?.toLong())} of ${bytes(body.size)}."
            BodyState.STREAMING -> "Not captured: streaming or multipart body ($type$size)."
            BodyState.BINARY -> "Binary content not shown ($type$size)."
            BodyState.SKIPPED -> "Not captured: matches the skip list ($type$size)."
            BodyState.REDIRECT -> "Redirect: body not captured."
            BodyState.FAILED -> "Body could not be read."
        }
    }
}
