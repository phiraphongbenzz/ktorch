package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.capture.REDACTED
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction

/**
 * Builds a runnable cURL command. Redacted values become shell variables, e.g.
 * `-H "Authorization: Bearer $TOKEN"`, so the command works after `export TOKEN=...` and
 * never contains the secret itself.
 */
internal object CurlFormatter {

    private sealed class Part {
        class Text(val value: String) : Part()
        class Variable(val name: String) : Part()
    }

    private val SKIPPED_HEADERS = setOf("content-length", "host", "accept-encoding", "connection")

    fun format(t: HttpTransaction): String {
        val variables = LinkedHashSet<String>()
        val args = ArrayList<String>()
        val notes = ArrayList<String>()

        val body = t.requestBody
        val hasBody = body.state != BodyState.EMPTY
        if (t.method != "GET" || hasBody) args += "-X ${t.method}"
        args += quote(redactedQuery(t.url), variables)

        t.requestHeaders.forEach { header ->
            if (header.name.lowercase() in SKIPPED_HEADERS) return@forEach
            args += "-H " + quote(header(header.name, header.value), variables)
        }
        if (t.requestHeader("Accept-Encoding")?.contains("gzip", ignoreCase = true) == true) {
            args += "--compressed"
        }

        when (body.state) {
            BodyState.CAPTURED, BodyState.TRUNCATED -> {
                val text = body.text.orEmpty()
                val parts = if (body.contentType?.contains("x-www-form-urlencoded", true) == true) {
                    redactedQuery(text)
                } else {
                    listOf(Part.Text(text))
                }
                args += "--data-raw " + quote(parts, variables)
                if (body.state == BodyState.TRUNCATED) {
                    notes += "WARNING: request body was truncated to ${Formats.bytes(body.bytes?.size?.toLong())} " +
                        "of ${Formats.bytes(body.size)}; the payload below is incomplete."
                }
            }
            BodyState.EMPTY -> Unit
            else -> notes += "NOTE: request body not captured (${body.contentType ?: "unknown type"}, " +
                "${Formats.bytes(body.size)}). ${Formats.bodyNote(body) ?: ""}".trim()
        }

        return buildString {
            if (variables.isNotEmpty()) {
                append("# Redacted values are shell variables. Set them first:\n")
                variables.forEach { append("#   export ").append(it).append("='...'\n") }
            }
            notes.forEach { append("# ").append(it).append('\n') }
            append("curl ")
            append(args.joinToString(" \\\n  "))
        }
    }

    private fun header(name: String, value: String): List<Part> {
        val variable = variableFor(name)
        return when {
            value == REDACTED -> listOf(Part.Text("$name: "), Part.Variable(variable))
            value.endsWith(" $REDACTED") -> listOf(
                Part.Text("$name: ${value.removeSuffix(REDACTED)}"),
                Part.Variable(variable),
            )
            else -> listOf(Part.Text("$name: $value"))
        }
    }

    /** Splits `a=1&token=***` so each redacted value becomes a variable named after its key. */
    private fun redactedQuery(text: String): List<Part> {
        val regex = Regex("""(^|[?&])([^=&?#]+)=\Q$REDACTED\E(?=$|[&#])""")
        val parts = ArrayList<Part>()
        var last = 0
        regex.findAll(text).forEach { match ->
            val valueStart = match.range.last + 1 - REDACTED.length
            parts += Part.Text(text.substring(last, valueStart))
            parts += Part.Variable(envName(match.groupValues[2]))
            last = match.range.last + 1
        }
        parts += Part.Text(text.substring(last))
        return parts
    }

    private fun variableFor(header: String): String = when (header.lowercase()) {
        "authorization" -> "TOKEN"
        "proxy-authorization" -> "PROXY_TOKEN"
        "cookie" -> "COOKIE"
        "set-cookie" -> "SET_COOKIE"
        else -> envName(header)
    }

    private fun envName(raw: String): String {
        val name = raw.uppercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("")
        return if (name.firstOrNull()?.isDigit() != false) "_$name" else name
    }

    private fun quote(text: String, variables: MutableSet<String>): String = quote(listOf(Part.Text(text)), variables)

    /**
     * Single quotes when there is nothing to expand (safest), double quotes with escaping when
     * a variable has to be expanded by the shell.
     */
    private fun quote(parts: List<Part>, variables: MutableSet<String>): String {
        if (parts.none { it is Part.Variable }) {
            val text = parts.joinToString("") { (it as Part.Text).value }
            return "'" + text.replace("'", "'\\''") + "'"
        }
        val out = StringBuilder("\"")
        parts.forEachIndexed { index, part ->
            when (part) {
                is Part.Text -> part.value.forEach { c ->
                    when (c) {
                        // History expansion ignores escapes inside double quotes, so '!' is
                        // closed out and single-quoted: "a"'!'"b" is still one shell word.
                        '!' -> out.append("\"'!'\"")
                        '"', '\\', '$', '`' -> out.append('\\').append(c)
                        else -> out.append(c)
                    }
                }
                is Part.Variable -> {
                    variables += part.name
                    val next = (parts.getOrNull(index + 1) as? Part.Text)?.value?.firstOrNull()
                    if (next != null && (next.isLetterOrDigit() || next == '_')) {
                        out.append("\${").append(part.name).append('}')
                    } else {
                        out.append('$').append(part.name)
                    }
                }
            }
        }
        return out.append('"').toString()
    }
}
