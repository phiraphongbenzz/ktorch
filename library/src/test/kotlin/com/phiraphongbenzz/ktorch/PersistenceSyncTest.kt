@file:OptIn(KtorchInternalApi::class)

package com.phiraphongbenzz.ktorch

import android.content.Context
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.data.PersistenceSync
import com.phiraphongbenzz.ktorch.internal.data.TransactionCodec
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.spi.KtorchInternalApi
import com.phiraphongbenzz.ktorch.internal.spi.PersistedRecord
import com.phiraphongbenzz.ktorch.internal.spi.TransactionPersistence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class PersistenceSyncTest {

    /** In-memory stand-in for Room, with a slow restore. */
    private class FakePersistence(saved: List<HttpTransaction>, private val restoreDelayMillis: Long) : TransactionPersistence {
        val rows = ConcurrentHashMap<Long, String>().apply { saved.forEach { put(it.id, TransactionCodec.encode(it)) } }

        override fun init(context: Context) = Unit

        override suspend fun loadRecent(limit: Int): List<PersistedRecord> {
            delay(restoreDelayMillis)
            return rows.entries.sortedByDescending { it.key }.take(limit).map { PersistedRecord(it.key, it.value) }
        }

        override suspend fun upsert(records: List<PersistedRecord>) = records.forEach { rows[it.id] = it.json }

        override suspend fun delete(ids: List<Long>) = ids.forEach { rows.remove(it) }

        override suspend fun trim(keep: Int) {
            rows.keys.sortedDescending().drop(keep).forEach { rows.remove(it) }
        }

        override suspend fun clear() = rows.clear()
    }

    @Test
    fun `requests recorded while the restore runs are still written to disk`() = runBlocking {
        val saved = transaction(id = 1)
        val disk = FakePersistence(listOf(saved), restoreDelayMillis = 300)
        val store = TransactionStore(100)
        PersistenceSync(disk, store) {}.start()

        // A splash-screen request finishes before the restore returns and never changes again.
        val live = transaction(id = 2)
        store.add(live)

        withTimeout(5_000) {
            while (!disk.rows.containsKey(live.id)) delay(20)
        }
        assertEquals(listOf(2L, 1L), store.transactions.value.map { it.id })
        assertTrue(disk.rows.containsKey(saved.id))
    }

    @Test
    fun `restore trims rows beyond the ring buffer`() = runBlocking {
        val disk = FakePersistence((1L..10L).map { transaction(it) }, restoreDelayMillis = 0)
        val store = TransactionStore(3)
        PersistenceSync(disk, store) {}.start()

        withTimeout(5_000) {
            while (disk.rows.size != 3 || store.transactions.value.size != 3) delay(20)
        }
        assertEquals(setOf(8L, 9L, 10L), disk.rows.keys)
    }

    private fun transaction(id: Long) = HttpTransaction(
        id = id,
        requestTimeMillis = 0,
        method = "GET",
        url = "https://api.example.com/$id",
        scheme = "https",
        host = "api.example.com",
        path = "/$id",
        requestHeaders = emptyList(),
        requestBody = Body.EMPTY,
        responseCode = 200,
        responseBody = Body.EMPTY,
        headersMillis = 1,
        totalMillis = 1,
    )
}
