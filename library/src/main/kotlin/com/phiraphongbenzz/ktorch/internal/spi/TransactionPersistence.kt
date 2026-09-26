package com.phiraphongbenzz.ktorch.internal.spi

import android.content.Context

/**
 * Contract between `library` and `library-room`. Public only because it crosses a module
 * boundary; it is not part of the Ktorch API and may change in any release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal contract between Ktorch modules. Do not use it from app code.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class KtorchInternalApi

/** One stored transaction: its id and an opaque, already-redacted JSON document. */
@KtorchInternalApi
public class PersistedRecord(public val id: Long, public val json: String)

/**
 * Disk storage, discovered at runtime with [java.util.ServiceLoader] so `library` never depends
 * on Room. Implementations need a public no-arg constructor. All calls come from one coroutine,
 * one at a time, on `Dispatchers.IO`.
 */
@KtorchInternalApi
public interface TransactionPersistence {
    public fun init(context: Context)

    /** Newest first. */
    public suspend fun loadRecent(limit: Int): List<PersistedRecord>

    public suspend fun upsert(records: List<PersistedRecord>)

    public suspend fun delete(ids: List<Long>)

    /** Keeps only the [keep] newest records. */
    public suspend fun trim(keep: Int)

    public suspend fun clear()
}
