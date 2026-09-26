package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.data.Body
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

    private fun StringBuilder.line(name: String, value: String) {
        append(name).append(": ").append(value).append('\n')
    }

    private fun StringBuilder.headers(headers: List<Header>) {
        if (headers.isEmpty()) append("(none)\n")
        headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
    }

    private fun StringBuilder.body(body: Body?) {
        Formats.bodyNote(body)?.let { append(it).append('\n') }
        Formats.bodyText(body, pretty = true)?.let { append(it).append('\n') }
    }
}
