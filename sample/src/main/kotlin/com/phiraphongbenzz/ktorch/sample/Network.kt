package com.phiraphongbenzz.ktorch.sample

import android.util.Log
import com.phiraphongbenzz.ktorch.NetworkInspector
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// A top-level client on the OkHttp engine, as many apps have: no
// Context in scope. Ktorch gets the application context from androidx.startup.
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 240_000
    }
    if (BuildConfig.DEBUG) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d("Ktor", message)
                }
            }
            level = LogLevel.HEADERS
        }
    }
    install(NetworkInspector) {
        maxContentLength = 250_000
        skipHosts += "storage.example.com"
        // Large files are streamed from here; never read those bodies.
        skipPathPrefixes += "/files/download"
        redactQueryParams += "signature"
        persistToDisk = true
        logger = { Log.d("Ktorch", it) }
    }
}
