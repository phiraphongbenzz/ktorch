package com.phiraphongbenzz.ktorch.internal.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.format.JsonFormatter

/** Characters [start] until [end] of display line [line]. */
internal class LineSpan(val line: Int, val start: Int, val end: Int)

/**
 * A foldable object or array: display lines [start] (opening bracket) to [end] (closing bracket).
 * When the body was truncated before it closed, [closed] is false and [end] is the last line.
 */
internal class Fold(val start: Int, val end: Int, val size: Int, val isArray: Boolean, val closed: Boolean)

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
    /** Foldable containers of pretty-printed JSON, by start line. At most one starts per line. */
    val folds: List<Fold> = emptyList(),
) {
    private val foldAt: Map<Int, Fold> = folds.associateBy { it.start }

    fun foldAt(line: Int): Fold? = foldAt[line]

    /** Display lines left when the folds starting at [collapsed] lines are closed, in order. */
    fun visibleLines(collapsed: Set<Int>): IntArray {
        if (collapsed.isEmpty()) return IntArray(lines.size) { it }
        val visible = IntArray(lines.size)
        var count = 0
        var i = 0
        while (i < lines.size) {
            visible[count++] = i
            val fold = if (i in collapsed) foldAt[i] else null
            // A closed fold shows its closing bracket on the start line, so that line goes too.
            i = if (fold != null) fold.end + 1 else i + 1
        }
        return visible.copyOf(count)
    }

    /** [collapsed] minus every fold that hides [line]. */
    fun reveal(collapsed: Set<Int>, line: Int): Set<Int> {
        if (collapsed.isEmpty()) return collapsed
        val hiding = folds.filter { it.start in collapsed && line > it.start && line <= it.end }
        return if (hiding.isEmpty()) collapsed else collapsed - hiding.map { it.start }.toSet()
    }

    /** Every fold except the outermost one, so the top level stays readable. */
    fun collapseAll(): Set<Int> = folds.filter { it.start != 0 }.mapTo(HashSet()) { it.start }

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
                // A body cut at the capture limit is still pretty-printed as far as it goes.
                val formatted = JsonFormatter.format(raw, partial = body.state == BodyState.TRUNCATED)
                if (formatted != null) {
                    val colored = formatted.text.length <= MAX_HIGHLIGHT_CHARS
                    val keys = ArrayList<LineSpan>()
                    val starts = IntArrayList()
                    val lines = split(formatted.text, formatted.spans, if (colored) colors else null, keys, starts)
                    return BodyContent(
                        lines,
                        isJson = true,
                        text = formatted.text,
                        highlighted = colored && formatted.spans.isNotEmpty(),
                        keys = keys,
                        folds = folds(formatted.containers, starts, lines.lastIndex),
                    )
                }
            }
            return BodyContent(split(raw, emptyList(), null), isJson = isJson, text = raw)
        }

        /** Maps bracket offsets to display lines; a container on a single line has nothing to fold. */
        private fun folds(containers: List<JsonFormatter.Container>, starts: IntArrayList, last: Int): List<Fold> {
            val folds = ArrayList<Fold>(containers.size)
            for (c in containers) {
                val start = starts.lineOf(c.open)
                val end = if (c.close < 0) last else starts.lineOf(c.close)
                if (end > start) folds += Fold(start, end, c.size, c.isArray, closed = c.close >= 0)
            }
            folds.sortBy { it.start }
            return folds
        }

        /** Tokens are colored only when [colors] is set; key positions go to [keys] either way. */
        private fun split(
            text: String,
            spans: List<JsonFormatter.Span>,
            colors: JsonColors?,
            keys: MutableList<LineSpan> = ArrayList(),
            starts: IntArrayList? = null,
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
                    starts?.add(pieceStart)
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

/** Text offset where each display line starts, ascending: a growable int array. */
internal class IntArrayList {
    private var values = IntArray(1024)
    private var size = 0

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    /** The display line holding text [offset]: the last line starting at or before it. */
    fun lineOf(offset: Int): Int {
        var low = 0
        var high = size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (values[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }
}
