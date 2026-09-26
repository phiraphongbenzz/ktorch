package com.phiraphongbenzz.ktorch

import android.content.Context
import io.ktor.http.Url
import io.ktor.utils.io.KtorDsl

/**
 * Configuration for [NetworkInspector].
 *
 * Every value is read once, when the plugin is installed. Changing it afterwards has no effect.
 */
@KtorDsl
public class NetworkInspectorConfig {

    /**
     * Android context for the inspector UI and notification. Optional: Ktorch captures the
     * application context at startup through androidx.startup, so a top-level
     * `val client = HttpClient(...)` works without one. Only the application context is ever kept,
     * so passing an Activity does not leak it. If androidx.startup has been disabled for Ktorch and
     * no context is set, install fails with an [IllegalStateException].
     */
    public var context: Context? = null

    /** Set to `false` to install the plugin without recording anything. */
    public var enabled: Boolean = true

    /**
     * Maximum number of body bytes kept per request and per response. Anything beyond is not
     * buffered, and the record is marked as truncated. The app still receives the full body.
     */
    public var maxContentLength: Long = 250_000

    /** Number of transactions kept in memory. The oldest one is dropped when the limit is reached. */
    public var maxTransactions: Int = 100

    /**
     * Hosts whose bodies are never read. A host matches itself and its subdomains, so
     * `"example.com"` also covers `"cdn.example.com"`. Matching transactions are recorded as
     * metadata only (method, URL, status, size, duration).
     */
    public val skipHosts: MutableSet<String> = mutableSetOf()

    /** URL path prefixes whose bodies are never read, e.g. `"/downloads/"`. */
    public val skipPathPrefixes: MutableSet<String> = mutableSetOf()

    /** Extra predicate evaluated before any body is read. Return `true` to record metadata only. */
    public var skipWhen: ((Url) -> Boolean)? = null

    /**
     * Header names whose values are replaced with `***` before anything is stored, e.g.
     * `redactHeaders += "Authorization"`. Empty by default (same as Chucker), so tokens are
     * visible in the UI and in shared exports. Case-insensitive.
     */
    public val redactHeaders: MutableSet<String> = mutableSetOf()

    /** Query parameter (and form field) names whose values are redacted. Case-insensitive. */
    public val redactQueryParams: MutableSet<String> = mutableSetOf()

    /**
     * JSON field names whose values are replaced with `"***"` in request and response bodies, at
     * any depth, and form fields with the same names, e.g.
     * `redactBodyFields += setOf("password", "access_token", "refresh_token")`. Applied before
     * anything is stored, so the values never reach the UI, disk or exports. The app itself still
     * sends and receives the real body. Case-insensitive.
     */
    public val redactBodyFields: MutableSet<String> = mutableSetOf()

    /**
     * Show a notification with the latest requests. Skipped silently when the app does not hold
     * `POST_NOTIFICATIONS` on Android 13+; the library never requests it.
     */
    public var showNotification: Boolean = true

    /**
     * Keep transactions on disk so they survive an app restart. Requires the separate
     * `library-room` artifact on the classpath; without it this flag logs a warning (through
     * [logger]) and the in-memory store is used.
     */
    public var persistToDisk: Boolean = false

    /**
     * Optional sink that receives a one-line, already-redacted summary of every finished
     * transaction, e.g. `logger = { Timber.tag("Ktorch").d(it) }`.
     */
    public var logger: ((String) -> Unit)? = null
}
