package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
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

    /** Text for a body, JSON pretty-printed when [pretty] is set. `null` when there is no text. */
    fun bodyText(body: Body?, pretty: Boolean): String? {
        val text = body?.text ?: return null
        if (!pretty || !JsonFormatter.looksLikeJson(text)) return text
        return JsonFormatter.format(text)?.text ?: text
    }

    /** Human explanation for bodies that have no text to show. */
    fun bodyNote(body: Body?): String? {
        body ?: return "Waiting for response…"
        val type = body.contentType ?: "unknown type"
        val size = body.size?.let { ", ${bytes(it)}" } ?: ""
        return when (body.state) {
            BodyState.EMPTY -> "(no body)"
            BodyState.PENDING -> "Receiving body…"
            BodyState.CAPTURED -> null
            BodyState.TRUNCATED -> "Truncated: showing the first ${bytes(body.bytes?.size?.toLong())} of ${bytes(body.size)}."
            BodyState.STREAMING -> "Not captured: streaming or multipart body ($type$size)."
            BodyState.BINARY -> "Binary content not shown ($type$size)."
            BodyState.SKIPPED -> "Not captured: matches the skip list ($type$size)."
            BodyState.REDIRECT -> "Redirect: body not captured."
            BodyState.FAILED -> "Body could not be read."
        }
    }
}
