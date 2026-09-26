package com.phiraphongbenzz.ktorch.internal.capture

import com.phiraphongbenzz.ktorch.NetworkInspectorConfig
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import io.ktor.client.plugins.api.ClientPluginBuilder
import io.ktor.client.plugins.api.Send
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.util.AttributeKey

private val TransactionIdKey = AttributeKey<Long>("KtorchTransactionId")
private val StartNanosKey = AttributeKey<Long>("KtorchStartNanos")

/**
 * Wires the [Recorder] into the client using only engine-agnostic Ktor API.
 *
 * - `Send` runs once per network hop (redirects included), after the body is serialized to
 *   `OutgoingContent`, so it sees the real payload and can time the whole exchange.
 * - `HttpReceivePipeline.After` sees the raw response before any body is consumed and may swap
 *   in a teed copy. Request and response are correlated by an id in the call attributes, never
 *   by URL: identical concurrent requests are common.
 */
internal fun ClientPluginBuilder<NetworkInspectorConfig>.installCapture(
    settings: CaptureSettings,
    store: TransactionStore,
) {
    val recorder = Recorder(settings, store)

    on(Send) { request ->
        val startNanos = System.nanoTime()
        val id = swallow { recorder.onRequest(request) }
        if (id == null) {
            // Redirect hops copy attributes; never let a stale id point at another record.
            request.attributes.remove(TransactionIdKey)
            return@on proceed(request)
        }
        request.attributes.put(TransactionIdKey, id)
        request.attributes.put(StartNanosKey, startNanos)
        try {
            proceed(request)
        } catch (cause: Throwable) {
            swallow { recorder.onFailure(id, startNanos, request.url.build(), cause) }
            throw cause
        }
    }

    client.receivePipeline.intercept(HttpReceivePipeline.After) { response ->
        val attributes = response.call.attributes
        val id = attributes.getOrNull(TransactionIdKey) ?: return@intercept
        val startNanos = attributes.getOrNull(StartNanosKey) ?: return@intercept
        val teed = swallow { recorder.onResponse(id, startNanos, response) } ?: return@intercept
        proceedWith(teed)
    }
}
