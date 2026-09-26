package com.phiraphongbenzz.ktorch.internal.capture

/**
 * Byte buffer that never holds more than [limit] bytes. Writes past the limit are dropped
 * and flagged, so memory stays bounded no matter how large the body is.
 */
internal class BoundedBuffer(private val limit: Int) {

    private var data = ByteArray(minOf(limit, INITIAL_CAPACITY))

    var size: Int = 0
        private set

    var overflowed: Boolean = false
        private set

    fun write(src: ByteArray, length: Int) {
        val room = limit - size
        val n = minOf(length, room)
        if (n < length) overflowed = true
        if (n <= 0) return
        if (size + n > data.size) {
            data = data.copyOf(minOf(limit, maxOf(size + n, data.size * 2)))
        }
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 8 * 1024
    }
}
