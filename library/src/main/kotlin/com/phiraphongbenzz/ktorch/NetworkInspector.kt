package com.phiraphongbenzz.ktorch

import android.content.Context
import com.phiraphongbenzz.ktorch.internal.Ktorch
import com.phiraphongbenzz.ktorch.internal.capture.installCapture
import com.phiraphongbenzz.ktorch.internal.capture.CaptureSettings
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

private val KtorchPlugin: ClientPlugin<NetworkInspectorConfig> =
    createClientPlugin("NetworkInspector", ::NetworkInspectorConfig) {
        if (!pluginConfig.enabled) return@createClientPlugin
        val context = checkNotNull(pluginConfig.context ?: Ktorch.appContext) {
            "Ktorch: no Android Context. androidx.startup normally provides it; if you disabled " +
                "its InitializationProvider, set it yourself: install(NetworkInspector) { context = applicationContext }"
        }
        Ktorch.attach(context, pluginConfig)
        installCapture(CaptureSettings.from(pluginConfig), Ktorch.store)
    }

/**
 * Ktor client plugin that records HTTP traffic and shows it in an in-app inspector.
 *
 * ```
 * val client = HttpClient(OkHttp) {
 *     install(NetworkInspector) {
 *         skipHosts += "storage.example.com"
 *     }
 * }
 * ```
 *
 * Works with any Ktor engine. Pair it with `library-no-op` in release builds.
 */
public object NetworkInspector : ClientPlugin<NetworkInspectorConfig> by KtorchPlugin {

    /** Opens the inspector screen. Safe to call before the plugin is installed. */
    public fun launchUI(context: Context) {
        Ktorch.launchUI(context)
    }

    /** Removes every recorded transaction and dismisses the notification. */
    public fun clear() {
        Ktorch.clear()
    }
}
