package com.phiraphongbenzz.ktorch.internal.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory ring buffer of transactions, newest first.
 *
 * Thread safety: the list is immutable and every write goes through [MutableStateFlow.update],
 * an atomic compare-and-set. Writers on any dispatcher and the UI reading on the main thread
 * never see a half-applied change, and nothing needs to suspend or lock.
 */
internal class TransactionStore(capacity: Int) {

    private val state = MutableStateFlow<List<HttpTransaction>>(emptyList())

    // Seeded from the clock so ids stay unique across launches when records are restored from
    // disk, even for requests that start before the restore has finished.
    private val ids = AtomicLong(System.currentTimeMillis() * 1000)

    fun nextId(): Long = ids.incrementAndGet()

    val transactions: StateFlow<List<HttpTransaction>> = state

    @Volatile
    var capacity: Int = capacity.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            state.update { it.take(field) }
        }

    fun add(transaction: HttpTransaction) {
        state.update { current ->
            val limit = capacity
            val next = ArrayList<HttpTransaction>(minOf(current.size + 1, limit))
            next.add(transaction)
            for (i in 0 until minOf(current.size, limit - 1)) next.add(current[i])
            next
        }
    }

    /** No-op when [id] was already evicted or cleared. */
    fun update(id: Long, transform: (HttpTransaction) -> HttpTransaction) {
        state.update { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = transform(it[index]) }
            }
        }
    }

    /** Merges records loaded from disk. Records already in memory win. */
    fun restore(restored: List<HttpTransaction>) {
        if (restored.isEmpty()) return
        state.update { current ->
            val known = current.mapTo(HashSet()) { it.id }
            (current + restored.filter { it.id !in known })
                .sortedByDescending { it.id }
                .take(capacity)
        }
    }

    fun get(id: Long): HttpTransaction? = state.value.firstOrNull { it.id == id }

    fun clear() {
        state.value = emptyList()
    }
}
