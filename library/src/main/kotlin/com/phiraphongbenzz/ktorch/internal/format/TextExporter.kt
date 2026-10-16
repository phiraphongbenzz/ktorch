package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.Header
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction

/** Plain-text export. Input is already redacted, so the output can be shared as-is. */
internal object TextExporter {

    fun format(t: HttpTransaction): String = buildString {
        append("=== ").append(t.method).append(' ').append(t.url).append(" ===\n")
        line("Status", Formats.status(t))
        t.error?.let { line("Error", it) }
        line("Date", Formats.dateTime(t.requestTimeMillis))
        line("Duration", Formats.duration(t.totalMillis ?: t.headersMillis))
        t.headersMillis?.let { line("Time to headers", Formats.duration(it)) }
        t.protocol?.let { line("Protocol", it) }
        line("Request size", Formats.bytes(t.requestBody.size))
        line("Response size", Formats.bytes(t.responseBody?.size))

        append("\n--- Request headers ---\n")
        headers(t.requestHeaders)
        append("\n--- Request body ---\n")
        body(t.requestBody)

        append("\n--- Response headers ---\n")
        headers(t.responseHeaders)
        append("\n--- Response body ---\n")
        body(t.responseBody)
    }

    fun formatAll(transactions: List<HttpTransaction>): String =
        transactions.joinToString("\n\n") { format(it) }

    /**
     * Short form for the clipboard: `[CODE] [METHOD] url`, request headers, the request body with
     * its kind, and the response body. JSON is pretty-printed when [pretty] is set; a very large
     * body may need `pretty = false` to fit the clipboard.
     */
    fun compact(t: HttpTransaction, pretty: Boolean = true): String = buildString {
        val code = t.responseCode?.toString() ?: if (t.error != null) "Failed" else "Pending"
        append('[').append(code).append("] [").append(t.method).append("] ").append(t.url).append('\n')
        append("\n[Header]\n")
        headers(t.requestHeaders)
        append("\n[Body] ").append(Formats.bodyKind(t.requestBody)).append('\n')
        requestBody(t.requestBody, pretty)
        append("\n[Response]\n")
        // Failed before any response: say why instead of "Waiting for response…".
        if (t.responseBody == null && t.error != null) line("Error", t.error) else body(t.responseBody, pretty)
    }.trimEnd()

    private fun StringBuilder.line(name: String, value: String) {
        append(name).append(": ").append(value).append('\n')
    }

    private fun StringBuilder.headers(headers: List<Header>) {
        if (headers.isEmpty()) append("(none)\n")
        headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
    }

    private fun StringBuilder.body(body: Body?, pretty: Boolean = true) {
        Formats.bodyNote(body)?.let { append(it).append('\n') }
        Formats.bodyText(body, pretty)?.let { append(it).append('\n') }
    }

    /** No "(no body)" line: the kind already says `none`. A form shows one decoded field per line. */
    private fun StringBuilder.requestBody(body: Body, pretty: Boolean) {
        if (body.state == BodyState.EMPTY) return
        val text = body.text
        if (text != null && Formats.bodyKind(body) == "x-www-form-urlencoded") {
            Formats.bodyNote(body)?.let { append(it).append('\n') }
            if (text.isNotEmpty()) append(Formats.formFields(text)).append('\n')
            return
        }
        body(body, pretty)
    }
}
