package com.phiraphongbenzz.ktorch.internal.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.format.JsonFormatter

/** Characters [start] until [end] of display line [line]. */
internal class LineSpan(val line: Int, val start: Int, val end: Int)

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
    /** JSON key names, quotes excluded. Only for pretty-printed JSON, so at most one per line. */
    val keys: List<LineSpan> = emptyList(),
) {

    /** Where [query] first occurs in each key name containing it, ignoring case, in order. */
    fun findKeys(query: String): List<LineSpan> {
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<LineSpan>()
        for (key in keys) {
            val line = lines[key.line].text
            for (i in key.start..key.end - query.length) {
                if (line.regionMatches(i, query, 0, query.length, ignoreCase = true)) {
                    hits += LineSpan(key.line, i, i + query.length)
                    break
                }
            }
        }
        return hits
    }

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
                    val colored = formatted.text.length <= MAX_HIGHLIGHT_CHARS
                    val keys = ArrayList<LineSpan>()
                    return BodyContent(
                        split(formatted.text, formatted.spans, if (colored) colors else null, keys),
                        isJson = true,
                        text = formatted.text,
                        highlighted = colored && formatted.spans.isNotEmpty(),
                        keys = keys,
                    )
                }
            }
            return BodyContent(split(raw, emptyList(), null), isJson = isJson, text = raw)
        }

        /** Tokens are colored only when [colors] is set; key positions go to [keys] either way. */
        private fun split(
            text: String,
            spans: List<JsonFormatter.Span>,
            colors: JsonColors?,
            keys: MutableList<LineSpan> = ArrayList(),
        ): List<AnnotatedString> {
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
                    lines += piece(text, pieceStart, pieceEnd, spans, spanIndex, colors, lines.size, keys)
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
            colors: JsonColors?,
            line: Int,
            keys: MutableList<LineSpan>,
        ): AnnotatedString {
            val content = text.substring(start, end)
            if (spans.isEmpty()) return AnnotatedString(content)
            val builder = colors?.let { AnnotatedString.Builder(content) }
            var i = firstSpan
            while (i < spans.size && spans[i].start < end) {
                val span = spans[i]
                val from = maxOf(span.start, start) - start
                val to = minOf(span.end, end) - start
                if (span.kind == JsonFormatter.Kind.KEY && span.start >= start) {
                    // The name sits between the quotes; a cut through the key keeps what is here.
                    val nameEnd = minOf(span.end - 1, end) - start
                    if (from + 1 < nameEnd) keys += LineSpan(line, from + 1, nameEnd)
                }
                if (builder != null && colors != null && to > from) {
                    builder.addStyle(SpanStyle(color = colorFor(span.kind, colors)), from, to)
                }
                i++
            }
            return builder?.toAnnotatedString() ?: AnnotatedString(content)
        }

        private fun colorFor(kind: JsonFormatter.Kind, colors: JsonColors) = when (kind) {
            JsonFormatter.Kind.KEY -> colors.key
            JsonFormatter.Kind.STRING -> colors.string
            JsonFormatter.Kind.NUMBER -> colors.number
            JsonFormatter.Kind.LITERAL -> colors.literal
        }
    }
}
