package com.phiraphongbenzz.ktorch.internal

import android.content.Context
import com.phiraphongbenzz.ktorch.NetworkInspectorConfig
import com.phiraphongbenzz.ktorch.internal.capture.swallow
import com.phiraphongbenzz.ktorch.internal.data.PersistenceSync
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.spi.KtorchInternalApi
import com.phiraphongbenzz.ktorch.internal.notification.InspectorNotifier
import com.phiraphongbenzz.ktorch.internal.ui.InspectorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide state. The consuming app has one client, so one store is enough. */
internal object Ktorch {

    val store = TransactionStore(capacity = 100)

    /** Set by [KtorchInitializer] at app startup, before Application.onCreate. */
    @Volatile
    var appContext: Context? = null

    @Volatile
    private var notifier: InspectorNotifier? = null

    @Volatile
    private var persistence: PersistenceSync? = null

    /** The consumer's logger, once the plugin is installed. */
    @Volatile
    private var logger: ((String) -> Unit)? = null

    private val log: (String) -> Unit = { message -> swallow { logger?.invoke(message) } }

    /** Disk and ServiceLoader work: never on the thread that starts the app or builds the client. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called by [KtorchInitializer] when the app process starts. */
    fun onStartup(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        // Persistence was on last run: restore right away, so the inspector (e.g. opened from the
        // notification after a crash) shows the history before this run's first request.
        io.launch {
            if (swallow { markerFile(app).exists() } == true) startPersistence(app)
        }
    }

    fun attach(context: Context, config: NetworkInspectorConfig) {
        val app = context.applicationContext ?: context
        appContext = app
        logger = config.logger
        store.capacity = config.maxTransactions
        if (config.showNotification) {
            synchronized(this) {
                if (notifier == null) notifier = InspectorNotifier(app, store).also { it.start() }
            }
        }
        val persist = config.persistToDisk
        io.launch {
            if (persist) {
                startPersistence(app)
                swallow { markerFile(app).createNewFile() }
            } else {
                swallow { markerFile(app).delete() }
            }
        }
    }

    private fun markerFile(context: Context) = File(context.noBackupFilesDir, "ktorch_persist")

    @OptIn(KtorchInternalApi::class)
    @Synchronized
    private fun startPersistence(context: Context) {
        if (persistence != null) return
        val impl = PersistenceSync.discover()
        if (impl == null) {
            log("Ktorch: persistToDisk is on but library-room is not on the classpath; keeping requests in memory only.")
            return
        }
        if (swallow { impl.init(context) } == null) {
            log("Ktorch: could not open the Ktorch database; keeping requests in memory only.")
            return
        }
        persistence = PersistenceSync(impl, store, log).also { it.start() }
    }

    fun launchUI(context: Context) {
        swallow { context.startActivity(InspectorActivity.intent(context)) }
    }

    fun clear() {
        store.clear()
        notifier?.cancel()
    }
}
