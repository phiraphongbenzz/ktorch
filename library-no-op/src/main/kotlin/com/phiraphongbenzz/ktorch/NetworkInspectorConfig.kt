package com.phiraphongbenzz.ktorch

import android.content.Context
import io.ktor.http.Url
import io.ktor.utils.io.KtorDsl

/**
 * No-op mirror of the real `NetworkInspectorConfig`. Values are accepted and ignored.
 * Keep this file in sync with `library`; `./gradlew check` fails if the APIs differ.
 */
@KtorDsl
public class NetworkInspectorConfig {
    public var context: Context? = null
        set(value) {
            // Kept for the client's lifetime, in release builds too: never hold an Activity.
            field = value?.applicationContext ?: value
        }
    public var enabled: Boolean = true
    public var maxContentLength: Long = 250_000
    public var maxTransactions: Int = 100
    public val skipHosts: MutableSet<String> = mutableSetOf()
    public val skipPathPrefixes: MutableSet<String> = mutableSetOf()
    public var skipWhen: ((Url) -> Boolean)? = null
    public val redactHeaders: MutableSet<String> = mutableSetOf()
    public val redactQueryParams: MutableSet<String> = mutableSetOf()
    public val redactBodyFields: MutableSet<String> = mutableSetOf()
    public var showNotification: Boolean = true
    public var persistToDisk: Boolean = false
    public var logger: ((String) -> Unit)? = null
}
