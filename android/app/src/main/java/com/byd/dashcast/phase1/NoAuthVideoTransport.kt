package com.byd.dashcast.phase1

import java.io.Closeable
import java.io.IOException

/** Bounded binary NCM stream. Cleanup bypasses only the user's prior cancellation. */
internal interface NoAuthVideoTransport : Closeable {
    @Throws(IOException::class)
    fun write(data: ByteArray)

    @Throws(IOException::class)
    fun readExact(count: Int): ByteArray

    fun beginCleanup()

    override fun close()
}
