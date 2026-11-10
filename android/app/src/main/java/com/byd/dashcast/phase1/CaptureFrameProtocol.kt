package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DCS1, local authorized ADB stdout only: bounded AU and actual encoder PTS.
 * @author Codex
 * @date 2026/10/13
 */
internal object CaptureFrameProtocol {
    const val HEADER_BYTES = 24
    private const val MAGIC = 0x44435331

    @JvmStatic
    @Throws(IOException::class)
    fun header(bytes: Int, ptsMicros: Long, key: Boolean): ByteArray {
        if (bytes == 0) {
            throw IOException("DCS1_EMPTY_PICTURE")
        }
        requireValues(bytes, ptsMicros, if (key) 1 else 0, 0)
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(bytes).putLong(ptsMicros).putInt(if (key) 1 else 0).putInt(0).array()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun end(ptsMicros: Long): ByteArray {
        requireValues(0, ptsMicros, 2, 0)
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(0).putLong(ptsMicros).putInt(2).putInt(0).array()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parse(header: ByteArray?): ByteBuffer {
        if (header == null || header.size != HEADER_BYTES) {
            throw IOException("DCS1_HEADER_LENGTH")
        }
        val values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = values.getInt()
        if (magic != MAGIC) {
            // Four structural bytes only, not a dump of video, OEM paths or arbitrary stdout.
            throw IOException("DCS1_WRONG_CAPTURE_PROTOCOL magic_hex=" + Integer.toHexString(magic))
        }
        requireValues(values.getInt(), values.getLong(), values.getInt(), values.getInt())
        values.position(4)
        return values
    }

    @JvmStatic
    @Throws(IOException::class)
    fun idle(lastPicturePts: Long): ByteArray {
        requireValues(0, lastPicturePts, 3, 0)
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(0).putLong(lastPicturePts).putInt(3).putInt(0).array()
    }

    @Throws(IOException::class)
    private fun requireValues(bytes: Int, ptsMicros: Long, flags: Int, reserved: Int) {
        val end = bytes == 0 && (flags == 2 || flags == 3)
        if ((!end && (bytes < 1 || flags < 0 || flags > 1)) || bytes > NoAuthVideoProtocol.MAX_FRAME_BYTES ||
            ptsMicros < 0 || reserved != 0) {
            throw IOException("DCS1_INVALID_FRAME_VALUES")
        }
    }
}
