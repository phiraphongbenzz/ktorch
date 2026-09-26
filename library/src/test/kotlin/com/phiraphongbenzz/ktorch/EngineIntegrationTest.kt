package com.phiraphongbenzz.ktorch

import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.security.MessageDigest
import io.ktor.server.cio.CIO as ServerCIO

/** Real sockets and real engines, with the plugins a typical app installs. */
class EngineIntegrationTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val base = "http://127.0.0.1:$port"
    private val downloadSize = 15L * 1024 * 1024
    private val json = """{"item_id":1,"action":"delete"}"""
    private lateinit var server: EmbeddedServer<*, *>

    @Before
    fun start() {
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("/sync") { call.respondText(json, ContentType.Application.Json) }
                get("/files/download/stream") {
                    call.respondBytesWriter(ContentType.Application.OctetStream, contentLength = downloadSize) {
                        val chunk = ByteArray(64 * 1024) { (it * 7).toByte() }
                        var sent = 0L
                        while (sent < downloadSize) {
                            val n = minOf(chunk.size.toLong(), downloadSize - sent).toInt()
                            writeFully(chunk, 0, n)
                            sent += n
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    @After
    fun stop() {
        server.stop(0, 0)
    }

    @Test
    fun `OkHttp engine with ContentNegotiation, HttpTimeout and Logging`() = runBlocking { verify(OkHttp.create()) }

    @Test
    fun `CIO engine`() = runBlocking { verify(CIO.create()) }

    private suspend fun verify(engine: HttpClientEngine) {
        val store = TransactionStore(100)
        val logged = mutableListOf<String>()
        val client = testClient(
            engine,
            store,
            clientConfig = {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                    requestTimeoutMillis = 240_000
                }
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) {
                            synchronized(logged) { logged += message }
                        }
                    }
                    level = LogLevel.BODY
                }
            },
        ) { skipPathPrefixes += "/files/download" }

        val response = client.get("$base/sync")
        assertEquals(json, response.bodyAsText())
        assertEquals(json, response.bodyAsText())

        val digest = MessageDigest.getInstance("SHA-256")
        val received = client.prepareGet("$base/files/download/stream").execute {
            val channel = it.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                digest.update(buffer, 0, n)
                total += n
            }
            total
        }
        assertEquals(downloadSize, received)

        val (download, sync) = store.awaitFinished(2)
        assertEquals(json, sync.responseBody!!.text)
        assertEquals(BodyState.SKIPPED, download.responseBody!!.state)
        assertNull(download.responseBody!!.bytes)
        client.close()
    }
}
