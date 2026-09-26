@file:OptIn(KtorchInternalApi::class)

package com.phiraphongbenzz.ktorch.internal.room

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.phiraphongbenzz.ktorch.internal.spi.KtorchInternalApi
import com.phiraphongbenzz.ktorch.internal.spi.PersistedRecord
import com.phiraphongbenzz.ktorch.internal.spi.TransactionPersistence
import java.util.concurrent.CancellationException
import java.io.File

@Entity(tableName = "transactions")
internal class TransactionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "json") val json: String,
)

@Dao
internal interface TransactionDao {
    @Query("SELECT id FROM transactions ORDER BY id DESC LIMIT :limit")
    suspend fun recentIds(limit: Int): List<Long>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM transactions WHERE id NOT IN (SELECT id FROM transactions ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = false)
internal abstract class KtorchDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
}

/**
 * Room-backed storage for Ktorch, found by `library` through ServiceLoader. Stores each
 * transaction as the JSON document `library` hands over; the schema never needs to follow the
 * model. A version bump simply drops old records: they are debug data.
 */
@KtorchInternalApi
public class RoomTransactionPersistence : TransactionPersistence {

    private lateinit var dao: TransactionDao

    override fun init(context: Context) {
        val app = context.applicationContext
        // Earlier builds kept the file in databases/, which Auto Backup and device transfer copy.
        app.deleteDatabase(DATABASE_NAME)
        // noBackupFilesDir is excluded from both: captured traffic never leaves the device.
        val path = File(app.noBackupFilesDir, DATABASE_NAME).absolutePath
        dao = Room.databaseBuilder(app, KtorchDatabase::class.java, path)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
            .transactions()
    }

    /**
     * Rows are read one by one: a row too large for SQLite's CursorWindow (or otherwise broken)
     * is dropped on its own instead of failing the whole restore.
     */
    override suspend fun loadRecent(limit: Int): List<PersistedRecord> {
        val result = ArrayList<PersistedRecord>()
        val broken = ArrayList<Long>()
        for (id in dao.recentIds(limit)) {
            try {
                dao.byId(id)?.let { result += PersistedRecord(it.id, it.json) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                broken += id
            }
        }
        if (broken.isNotEmpty()) delete(broken)
        return result
    }

    override suspend fun upsert(records: List<PersistedRecord>) {
        dao.upsert(records.map { TransactionEntity(it.id, it.json) })
    }

    override suspend fun delete(ids: List<Long>) {
        // SQLite caps bound parameters (999 on older versions).
        ids.chunked(500).forEach { dao.delete(it) }
    }

    override suspend fun trim(keep: Int) {
        dao.trim(keep)
    }

    override suspend fun clear() {
        dao.clear()
    }

    private companion object {
        const val DATABASE_NAME = "ktorch.db"
    }
}
