package com.phiraphongbenzz.ktorch

import android.content.Context
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

// Registers no hooks: requests pass through untouched.
private val NoOpPlugin: ClientPlugin<NetworkInspectorConfig> =
    createClientPlugin("NetworkInspector", ::NetworkInspectorConfig) {}

/** No-op mirror of the real `NetworkInspector`. Records nothing, shows nothing. */
public object NetworkInspector : ClientPlugin<NetworkInspectorConfig> by NoOpPlugin {
    @Suppress("UNUSED_PARAMETER")
    public fun launchUI(context: Context) {}

    public fun clear() {}
}
