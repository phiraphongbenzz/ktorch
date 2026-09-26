@file:OptIn(KtorchInternalApi::class)

package com.phiraphongbenzz.ktorch.internal.data

import com.phiraphongbenzz.ktorch.internal.spi.KtorchInternalApi
import com.phiraphongbenzz.ktorch.internal.spi.PersistedRecord
import com.phiraphongbenzz.ktorch.internal.spi.TransactionPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ServiceLoader

/**
 * Mirrors the in-memory store to disk. The store stays the single source of truth; this only
 * writes what changed, from one coroutine, so the database never sees concurrent writes.
 */
internal class PersistenceSync(
    private val persistence: TransactionPersistence,
    private val store: TransactionStore,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            // Only what came from disk is known to be on disk. Requests recorded while the restore
            // was running are not, and must be written by the loop below.
            var written: Map<Long, HttpTransaction> = restore()
            store.transactions.collect { list ->
                try {
                    val current = list.associateBy { it.id }
                    if (current.isEmpty() && written.isNotEmpty()) {
                        persistence.clear()
                    } else {
                        val removed = written.keys.filter { it !in current }
                        if (removed.isNotEmpty()) persistence.delete(removed)
                        // Records are immutable: a new instance means it changed.
                        val changed = list.filter { written[it.id] !== it }
                        if (changed.isNotEmpty()) {
                            persistence.upsert(changed.map { PersistedRecord(it.id, TransactionCodec.encode(it)) })
                        }
                    }
                    written = current
                } catch (t: Throwable) {
                    log("Ktorch: disk write failed: ${t.javaClass.simpleName}")
                }
                delay(WRITE_INTERVAL_MILLIS)
            }
        }
    }

    /** Loads saved records into the store and returns them, keyed by id, as they now are on disk. */
    private suspend fun restore(): Map<Long, HttpTransaction> = try {
        val capacity = store.capacity
        // Rows beyond the ring buffer (e.g. after lowering maxTransactions) would never be read.
        persistence.trim(capacity)
        val restored = persistence.loadRecent(capacity)
            .mapNotNull { TransactionCodec.decode(it.json) }
            .map(::markInterrupted)
        store.restore(restored)
        // Persist the "interrupted" marking right away so it is never restored as pending again.
        val fixed = restored.filter { it.error == INTERRUPTED }
        if (fixed.isNotEmpty()) {
            persistence.upsert(fixed.map { PersistedRecord(it.id, TransactionCodec.encode(it)) })
        }
        restored.associateBy { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("Ktorch: could not restore saved requests: ${t.javaClass.simpleName}")
        emptyMap()
    }

    companion object {
        private const val WRITE_INTERVAL_MILLIS = 500L
        const val INTERRUPTED = "Interrupted: the app stopped before this request finished"

        /** A record saved mid-request (app killed) is closed off instead of shown as pending forever. */
        fun markInterrupted(t: HttpTransaction): HttpTransaction {
            if (t.isFinished && t.responseBody?.state != BodyState.PENDING) return t
            return t.copy(
                error = t.error ?: INTERRUPTED,
                responseBody = t.responseBody?.let {
                    if (it.state == BodyState.PENDING) Body(BodyState.FAILED, it.contentType, it.size) else it
                },
            )
        }

        fun discover(): TransactionPersistence? = try {
            val type = TransactionPersistence::class.java
            ServiceLoader.load(type, type.classLoader).iterator().let { if (it.hasNext()) it.next() else null }
        } catch (t: Throwable) {
            null
        }
    }
}
