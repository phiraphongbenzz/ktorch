package com.phiraphongbenzz.ktorch

import com.phiraphongbenzz.ktorch.internal.capture.CaptureSettings
import com.phiraphongbenzz.ktorch.internal.capture.installCapture
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/**
 * Same capture wiring as [NetworkInspector], minus the Android parts (context, notification,
 * UI), so it runs on the plain JVM.
 */
internal fun testClient(
    engine: HttpClientEngine,
    store: TransactionStore = TransactionStore(100),
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
    configure: NetworkInspectorConfig.() -> Unit = {},
): HttpClient {
    val plugin = createClientPlugin("KtorchTest", ::NetworkInspectorConfig) {
        store.capacity = pluginConfig.maxTransactions
        installCapture(CaptureSettings.from(pluginConfig), store)
    }
    return HttpClient(engine) {
        clientConfig()
        install(plugin) { configure() }
    }
}

/** Response bodies finish recording asynchronously, right after the app sees EOF. */
internal suspend fun TransactionStore.awaitFinished(count: Int = 1): List<HttpTransaction> =
    withTimeout(10_000) {
        while (true) {
            val list = transactions.value
            if (list.size >= count && list.all { it.isFinished }) return@withTimeout list
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
