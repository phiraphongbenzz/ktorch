package com.phiraphongbenzz.ktorch.internal.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.format.JsonFormatter

/**
 * A body split into display lines, so a large payload renders lazily line by line instead of
 * as one huge Text. Built off the main thread.
 */
internal class BodyContent(
    val lines: List<AnnotatedString>,
    val isJson: Boolean,
    val text: String,
    /** Syntax colors applied: plain text (punctuation) is then drawn in a muted color. */
    val highlighted: Boolean = false,
) {

    companion object {
        /** Very long lines (minified JSON) are cut into chunks so each row stays cheap to lay out. */
        private const val MAX_LINE_CHARS = 2_000

        /** Syntax colors cost a span per token; skip them for huge documents. */
        private const val MAX_HIGHLIGHT_CHARS = 600_000

        val EMPTY = BodyContent(emptyList(), isJson = false, text = "")

        fun build(body: Body?, pretty: Boolean, colors: JsonColors): BodyContent {
            val raw = body?.text ?: return EMPTY
            val isJson = JsonFormatter.looksLikeJson(raw)
            if (pretty && isJson) {
                val formatted = JsonFormatter.format(raw)
                if (formatted != null) {
                    val spans = if (formatted.text.length <= MAX_HIGHLIGHT_CHARS) formatted.spans else emptyList()
                    return BodyContent(
                        split(formatted.text, spans, colors),
                        isJson = true,
                        text = formatted.text,
                        highlighted = spans.isNotEmpty(),
                    )
                }
            }
            return BodyContent(split(raw, emptyList(), colors), isJson = isJson, text = raw)
        }

        private fun split(text: String, spans: List<JsonFormatter.Span>, colors: JsonColors): List<AnnotatedString> {
            val lines = ArrayList<AnnotatedString>()
            var spanIndex = 0
            var lineStart = 0
            while (lineStart <= text.length) {
                val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                var pieceStart = lineStart
                do {
                    val pieceEnd = minOf(newline, pieceStart + MAX_LINE_CHARS)
                    // Spans are sorted by start; skip those that ended before this piece.
                    while (spanIndex < spans.size && spans[spanIndex].end <= pieceStart) spanIndex++
                    lines += piece(text, pieceStart, pieceEnd, spans, spanIndex, colors)
                    pieceStart = pieceEnd
                } while (pieceStart < newline)
                lineStart = newline + 1
            }
            return lines
        }

        private fun piece(
            text: String,
            start: Int,
            end: Int,
            spans: List<JsonFormatter.Span>,
            firstSpan: Int,
            colors: JsonColors,
        ): AnnotatedString {
            if (spans.isEmpty()) return AnnotatedString(text.substring(start, end))
            val builder = AnnotatedString.Builder(text.substring(start, end))
            var i = firstSpan
            while (i < spans.size && spans[i].start < end) {
                val span = spans[i]
                val from = maxOf(span.start, start) - start
                val to = minOf(span.end, end) - start
                if (to > from) builder.addStyle(SpanStyle(color = colorFor(span.kind, colors)), from, to)
                i++
            }
            return builder.toAnnotatedString()
        }

        private fun colorFor(kind: JsonFormatter.Kind, colors: JsonColors) = when (kind) {
            JsonFormatter.Kind.KEY -> colors.key
            JsonFormatter.Kind.STRING -> colors.string
            JsonFormatter.Kind.NUMBER -> colors.number
            JsonFormatter.Kind.LITERAL -> colors.literal
        }
    }
}
