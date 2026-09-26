package com.phiraphongbenzz.ktorch.internal.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.format.CurlFormatter
import com.phiraphongbenzz.ktorch.internal.format.TextExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Subclass so the manifest entry never collides with the host app's own FileProvider. */
internal class KtorchFileProvider : FileProvider()

internal object Sharer {

    // Binder transactions are capped around 1 MB; anything large travels as a file instead.
    private const val MAX_INLINE_CHARS = 50_000
    private const val SHARE_DIR = "ktorch_share"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    suspend fun shareCurl(context: Context, t: HttpTransaction) {
        val text = withContext(Dispatchers.Default) { CurlFormatter.format(t) }
        val subject = "cURL · ${t.method} ${t.path}"
        if (text.length <= MAX_INLINE_CHARS) {
            shareInline(context, subject, text)
        } else {
            shareFile(context, "ktorch-${t.id}-curl.txt", subject, text)
        }
    }

    suspend fun shareTransaction(context: Context, t: HttpTransaction) {
        val text = withContext(Dispatchers.Default) { TextExporter.format(t) }
        shareFile(context, "ktorch-${t.id}.txt", "${t.method} ${t.path}", text)
    }

    suspend fun shareAll(context: Context, transactions: List<HttpTransaction>) {
        val text = withContext(Dispatchers.Default) { TextExporter.formatAll(transactions) }
        shareFile(context, "ktorch-${System.currentTimeMillis()}.txt", "Ktorch · ${transactions.size} requests", text)
    }

    /** Returns `false` when the clipboard refused the text (usually too large). */
    fun copy(context: Context, label: String, text: String): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 24) {
            // Copies can hold tokens: keep them out of the clipboard preview (Android 13+) and
            // keyboard suggestions. Same key as ClipDescription.EXTRA_IS_SENSITIVE (API 33).
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        true
    } catch (t: Throwable) {
        false
    }

    private fun shareInline(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, subject))
    }

    private suspend fun shareFile(context: Context, name: String, subject: String, text: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            // Only the latest export is kept; older ones are not needed once shared.
            dir.listFiles()?.forEach { it.delete() }
            File(dir, name).apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ktorch.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(
            Intent.createChooser(send, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
