package com.phiraphongbenzz.ktorch.internal.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.phiraphongbenzz.ktorch.R
import com.phiraphongbenzz.ktorch.internal.capture.swallow
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.ui.InspectorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the latest transactions in a notification. The library never declares or requests
 * `POST_NOTIFICATIONS`: without it, notifications are skipped silently.
 */
internal class InspectorNotifier(
    private val context: Context,
    private val store: TransactionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = NotificationManagerCompat.from(context)
    private val logo: Bitmap? by lazy {
        swallow { ContextCompat.getDrawable(context, R.drawable.ktorch_ic_notification_large)?.toBitmap(LOGO_PX, LOGO_PX) }
    }

    fun start() {
        scope.launch {
            // StateFlow conflates for slow collectors, so collect + delay = at most one update per
            // interval, always with the latest state.
            store.transactions.collect { list ->
                swallow { show(list) }
                delay(THROTTLE_MILLIS)
            }
        }
    }

    fun cancel() {
        swallow { manager.cancel(NOTIFICATION_ID) }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun show(transactions: List<HttpTransaction>) {
        if (transactions.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!canPost()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Ktorch network activity")
                .build(),
        )
        val style = NotificationCompat.InboxStyle()
        transactions.take(MAX_LINES).forEach { style.addLine(line(it)) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ktorch_ic_notification)
            // Android 16+ shows the host app's icon in the shade; the logo makes Ktorch's own
            // notification recognisable next to the app's real ones.
            .setLargeIcon(logo)
            .setContentTitle("Ktorch · ${transactions.size} requests")
            .setContentText(line(transactions.first()))
            .setStyle(style)
            .setContentIntent(openInspector())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun openInspector(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        InspectorActivity.intent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun line(t: HttpTransaction): String {
        val status = t.responseCode?.toString() ?: if (t.error != null) "!!!" else "…"
        return "$status ${t.method} ${t.path}"
    }

    private companion object {
        const val CHANNEL_ID = "ktorch"
        const val NOTIFICATION_ID = 0x6b74 // "kt"
        const val MAX_LINES = 5
        const val THROTTLE_MILLIS = 750L
        const val LOGO_PX = 192
    }
}
