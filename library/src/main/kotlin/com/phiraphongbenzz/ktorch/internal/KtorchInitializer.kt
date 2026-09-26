package com.phiraphongbenzz.ktorch.internal

import android.content.Context
import androidx.startup.Initializer

/**
 * Captures the application context at startup, so the plugin can be installed where no Context
 * exists, e.g. a top-level `val client = HttpClient(OkHttp) { install(NetworkInspector) }`.
 * Runs inside the app's existing androidx.startup InitializationProvider; no extra provider.
 */
internal class KtorchInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Ktorch.onStartup(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
