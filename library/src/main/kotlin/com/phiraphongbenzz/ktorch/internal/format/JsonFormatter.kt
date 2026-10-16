package com.phiraphongbenzz.ktorch.internal.format

/**
 * Pretty-prints JSON by re-indenting its tokens, without building an object model.
 *
 * Going through a map (kotlinx JsonObject) would silently merge duplicate keys, and re-encoding
 * numbers would turn `12345678901234567890` into `1.2345678901234567E19` or `1.50` into `1.5`.
 * For a debugging tool both are lies, so every key is kept in order and numbers are written back
 * exactly as received. Strings are shown decoded (`é` as `é`), re-escaped only where JSON
 * requires it.
 */
internal object JsonFormatter {

    enum class Kind { KEY, STRING, NUMBER, LITERAL }

    class Span(val start: Int, val end: Int, val kind: Kind)

    /**
     * A non-empty object or array: output offsets of its brackets and how many values it holds.
     * [close] is -1 when the input ended before the container did (truncated body).
     */
    class Container(val open: Int, val close: Int, val size: Int, val isArray: Boolean)

    /** [complete] is false when only a truncated prefix of the document was formatted. */
    class Result(
        val text: String,
        val spans: List<Span>,
        val containers: List<Container> = emptyList(),
        val complete: Boolean = true,
    )

    /** Deeper documents are shown raw rather than risking the stack. */
    private const val MAX_DEPTH = 256

    fun looksLikeJson(text: String): Boolean {
        val first = text.firstOrNull { !it.isWhitespace() && it != '\uFEFF' } ?: return false
        return first == '{' || first == '['
    }

    /**
     * Returns `null` when [raw] is not strictly valid JSON; the caller then shows it as-is.
     * With [partial] (a body cut at the capture limit), input that is valid up to where it ends
     * is formatted as far as it goes instead of being rejected.
     */
    fun format(raw: String, indent: String = "  ", partial: Boolean = false): Result? = try {
        Printer(raw.removePrefix("\uFEFF"), indent, partial).run()
    } catch (e: InvalidJson) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private class InvalidJson : RuntimeException()

    private class Printer(private val s: String, private val indent: String, private val partial: Boolean) {
        private var i = 0
        private val out = StringBuilder(s.length + s.length / 4)
        private val spans = ArrayList<Span>()
        private val containers = ArrayList<Container>()

        /** Containers still open, innermost last: output offset of the bracket and values so far. */
        private val open = ArrayList<IntArray>()

        /** Set once anything looked past the last character: a failure there means "cut short". */
        private var reachedEnd = false

        /** Output length after the last complete value: where a cut-short document ends. */
        private var valueEnd = 0

        fun run(): Result {
            try {
                skipWhitespace()
                value(0)
                skipWhitespace()
                if (i != s.length) throw InvalidJson()
            } catch (e: InvalidJson) {
                if (!partial || !reachedEnd) throw e
                return cutShort()
            }
            return Result(out.toString(), spans, containers)
        }

        /** Keeps everything up to the last complete value; containers left open run to the end. */
        private fun cutShort(): Result {
            if (valueEnd == 0) throw InvalidJson()
            out.setLength(valueEnd)
            // Drops a key whose value was cut off.
            spans.removeAll { it.end > valueEnd }
            for ((at, size) in open) {
                if (at < valueEnd) containers += Container(at, -1, size, out[at] == '[')
            }
            open.clear()
            return Result(out.toString(), spans, containers, complete = false)
        }

        private fun value(depth: Int) {
            if (i >= s.length) reachedEnd = true
            if (depth > MAX_DEPTH || i >= s.length) throw InvalidJson()
            when (s[i]) {
                '{' -> container(depth, close = '}', keyed = true)
                '[' -> container(depth, close = ']', keyed = false)
                '"' -> string(Kind.STRING)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
            valueEnd = out.length
        }

        private fun container(depth: Int, close: Char, keyed: Boolean) {
            val open = s[i++]
            skipWhitespace()
            if (peek() == close) {
                i++
                out.append(open).append(close)
                return
            }
            val state = intArrayOf(out.length, 0)
            this.open += state
            out.append(open).append('\n')
            while (true) {
                pad(depth + 1)
                if (keyed) {
                    if (peek() != '"') throw InvalidJson()
                    string(Kind.KEY)
                    skipWhitespace()
                    if (peek() != ':') throw InvalidJson()
                    i++
                    out.append(": ")
                    skipWhitespace()
                }
                value(depth + 1)
                state[1]++
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        i++
                        skipWhitespace()
                        out.append(",\n")
                    }
                    close -> {
                        i++
                        break
                    }
                    else -> throw InvalidJson()
                }
            }
            out.append('\n')
            pad(depth)
            this.open.removeAt(this.open.lastIndex)
            containers += Container(state[0], out.length, state[1], open == '[')
            out.append(close)
        }

        private fun string(kind: Kind) {
            val start = i++
            while (true) {
                if (i >= s.length) {
                    reachedEnd = true
                    throw InvalidJson()
                }
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        break
                    }
                    c == '\\' -> {
                        val escape = s.getOrNull(i + 1) ?: run {
                            reachedEnd = true
                            throw InvalidJson()
                        }
                        i += when (escape) {
                            'u' -> if (isHex4(i + 2)) 6 else throw InvalidJson()
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> 2
                            else -> throw InvalidJson()
                        }
                    }
                    c < ' ' -> throw InvalidJson()
                    else -> i++
                }
            }
            val decoded = JsonStrings.decode(s.substring(start, i)) ?: throw InvalidJson()
            token(JsonStrings.quote(decoded), kind)
        }

        private fun number() {
            val start = i
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> throw InvalidJson()
            }
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') throw InvalidJson()
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') throw InvalidJson()
                digits()
            }
            // A number running into the cut may have lost digits: never show it as if complete.
            if (partial && i == s.length) {
                reachedEnd = true
                throw InvalidJson()
            }
            token(s.substring(start, i), Kind.NUMBER)
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) {
                if (word.startsWith(s.substring(i))) reachedEnd = true
                throw InvalidJson()
            }
            i += word.length
            token(word, Kind.LITERAL)
        }

        private fun digits() {
            while (peek() in '0'..'9') i++
        }

        private fun isHex4(from: Int): Boolean {
            if (from + 4 > s.length) {
                reachedEnd = true
                return false
            }
            return (from until from + 4).all { s[it].isDigit() || s[it].lowercaseChar() in 'a'..'f' }
        }

        private fun peek(): Char {
            if (i < s.length) return s[i]
            reachedEnd = true
            return Char.MIN_VALUE
        }

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun token(text: String, kind: Kind) {
            val start = out.length
            out.append(text)
            spans += Span(start, out.length, kind)
        }

        private fun pad(depth: Int) {
            repeat(depth) { out.append(indent) }
        }
    }
}

/** JSON string decoding/quoting shared by the formatter and the redactor. */
internal object JsonStrings {

    /** Decodes a JSON string token, quotes included. `null` if it is malformed. */
    fun decode(token: String): String? {
        if (token.length < 2 || token.first() != '"' || token.last() != '"') return null
        val end = token.length - 1
        val out = StringBuilder(end)
        var k = 1
        while (k < end) {
            val c = token[k]
            if (c != '\\') {
                out.append(c)
                k++
                continue
            }
            if (k + 1 >= end) return null
            when (token[k + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (k + 6 > end) return null
                    out.append(token.substring(k + 2, k + 6).toIntOrNull(16)?.toChar() ?: return null)
                    k += 6
                    continue
                }
                else -> return null
            }
            k += 2
        }
        return out.toString()
    }

    /** Quotes [content] as a JSON string, escaping only what JSON requires. */
    fun quote(content: String): String {
        val out = StringBuilder(content.length + 2).append('"')
        for (c in content) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
