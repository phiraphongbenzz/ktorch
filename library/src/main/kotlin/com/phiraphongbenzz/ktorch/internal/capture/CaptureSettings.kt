package com.phiraphongbenzz.ktorch.internal.capture

import com.phiraphongbenzz.ktorch.NetworkInspectorConfig
import io.ktor.http.Url

/** Immutable snapshot of [NetworkInspectorConfig], taken once at install time. */
internal class CaptureSettings(
    val maxContentLength: Int,
    skipHosts: Set<String>,
    private val skipPathPrefixes: Set<String>,
    private val skipWhen: ((Url) -> Boolean)?,
    val redactor: Redactor,
    private val logger: ((String) -> Unit)?,
) {
    private val skipHosts: Set<String> = skipHosts.mapTo(HashSet()) { it.lowercase().trim('.') }

    /** Evaluated before any body is read. A throwing predicate counts as "skip". */
    fun shouldSkip(url: Url): Boolean {
        val host = url.host.lowercase()
        if (skipHosts.any { host == it || host.endsWith(".$it") }) return true
        val path = url.encodedPath
        if (skipPathPrefixes.any { path.startsWith(it) }) return true
        val predicate = skipWhen ?: return false
        return try {
            predicate(url)
        } catch (t: Throwable) {
            true
        }
    }

    fun log(message: () -> String) {
        val sink = logger ?: return
        try {
            sink(message())
        } catch (t: Throwable) {
            // A broken logger must never affect the request.
        }
    }

    companion object {
        fun from(config: NetworkInspectorConfig) = CaptureSettings(
            maxContentLength = config.maxContentLength.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            skipHosts = config.skipHosts.toSet(),
            skipPathPrefixes = config.skipPathPrefixes.toSet(),
            skipWhen = config.skipWhen,
            redactor = Redactor(
                headers = config.redactHeaders.toSet(),
                queryParams = config.redactQueryParams.toSet(),
                bodyFields = config.redactBodyFields.toSet(),
            ),
            logger = config.logger,
        )
    }
}
