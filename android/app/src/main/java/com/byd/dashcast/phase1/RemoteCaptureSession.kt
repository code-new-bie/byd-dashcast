package com.byd.dashcast.phase1

import android.media.MediaCodec

/** Fixed owned display source. Neither backend may capture the current primary foreground. */
internal interface RemoteCaptureSession : AutoCloseable {
    @Throws(Exception::class)
    fun update(codec: MediaCodec)

    @Throws(Exception::class)
    fun acceptFrame(ptsMicros: Long, key: Boolean): Boolean

    fun renderAllowed(): Boolean = true

    fun advanceSourceAllowed(): Boolean = renderAllowed()

    fun renderSourceEpochNanos(): Long = 0L
}
