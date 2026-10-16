package com.phiraphongbenzz.ktorch.sample

import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phiraphongbenzz.ktorch.NetworkInspector
import com.phiraphongbenzz.ktorch.sample.databinding.ActivityMainBinding
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val base = LocalServer.BASE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        button("GET JSON") {
            client.get("$base/api/items?id=42") { header(HttpHeaders.Authorization, "Bearer sample-token-123") }.bodyAsText()
        }
        button("POST JSON") {
            client.post("$base/echo") {
                contentType(ContentType.Application.Json)
                setBody("""{"items":[1,2,3],"device":"sample","note":"hello"}""")
            }.bodyAsText()
        }
        button("GET long JSON (120 KB)") { client.get("$base/big-json/120").bodyAsText().length }
        button("GET long JSON (600 KB, over the cap)") { client.get("$base/big-json/600").bodyAsText().length }
        button("POST url-encoded form") {
            client.submitForm(
                url = "$base/form",
                formParameters = parameters {
                    append("username", "dekd")
                    append("note", "สวัสดี & hello")
                },
            ).bodyAsText()
        }
        button("GET 404") { client.get("$base/status/404").bodyAsText() }
        button("GET 500") { client.get("$base/status/500").bodyAsText() }
        button("GET redirect (302 → 200)") { client.get("$base/redirect").bodyAsText() }
        button("GET slow (3 s)") { client.get("$base/slow").bodyAsText() }
        button("GET with redacted query param") { client.get("$base/api/items?id=1&signature=s3cr3t").bodyAsText() }
        button("Upload multipart (1 MB)") {
            client.submitFormWithBinaryData(
                url = "$base/upload",
                formData = formData {
                    append("title", "My novel")
                    append("chapter", 12)
                    append("file", ByteArray(1024 * 1024) { 7 }, Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"archive.zip\"")
                    })
                },
            ).bodyAsText()
        }
        button("Download 15 MB file (skipped path)") { download("$base/files/download/15") }
        button("Stream 5 MB text (truncated to cap)") { download("$base/big-text/5") }
        button("Fire 500 requests") {
            (1..500).map { i -> lifecycleScope.async { client.get("$base/api/items?id=$i").bodyAsText() } }
                .awaitAll()
                .let { "500 responses, ${it.sumOf { body -> body.length }} chars total" }
        }
        button("Remote HTTPS GET (httpbin.org)") { client.get("https://httpbin.org/get").bodyAsText() }
        button("Open inspector") {
            NetworkInspector.launchUI(this@MainActivity)
            "opened"
        }
    }

    /** Streams the body like a file download and reports what arrived. */
    private suspend fun download(url: String): String = client.prepareGet(url).execute { response ->
        val channel = response.bodyAsChannel()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        "received $total bytes, sha256 $sha…"
    }

    private fun button(label: String, action: suspend () -> Any) {
        val button = Button(this).apply { text = label; isAllCaps = false }
        button.setOnClickListener {
            lifecycleScope.launch {
                val started = System.currentTimeMillis()
                val result = runCatching { action() }
                val ms = System.currentTimeMillis() - started
                val heapMb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
                binding.result.text = buildString {
                    append(label).append(" · ").append(ms).append(" ms · heap ").append(heapMb).append(" MB\n")
                    append(result.fold({ it.toString().take(600) }, { "FAILED: $it" }))
                }
            }
        }
        binding.buttons.addView(button)
    }
}
