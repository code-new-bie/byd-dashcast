package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DCV1/1: one private USB TCP stream, version negotiation without video credentials.
 * ACK reports media/window state, never a decoded-frame count or human-observed picture.
 * @author Codex
 * @date 2026/10/11
 */
internal object NoAuthVideoProtocol {
    const val PORT = DashcastPorts.PROJECTION
    const val HEADER_BYTES = 24
    const val ACK_BYTES = 16
    const val OFFER_BYTES = 80
    const val MAX_FRAME_BYTES = 0x200000
    const val MAX_TEST_FRAMES = 1800
    const val WIDTH = 1920
    const val HEIGHT = 720
    const val FPS = 30

    // START is x, y, window width, window height, FPS, flags; encoded size comes from SPS.
    const val WINDOW_X = 640
    const val WINDOW_Y = 240
    const val WINDOW_WIDTH = 640
    const val WINDOW_HEIGHT = 240
    const val START = 1
    const val FRAME = 2
    const val STOP = 3
    const val HELLO = 7
    const val HEARTBEAT = 8
    const val LOG_FETCH = 11
    const val LOG_TAIL_BYTES = 16384

    /** One read: the largest reply body the instrument sends. */
    const val MAX_REPLY_READ_BYTES = LOG_TAIL_BYTES
    const val LOG_FILE_VERSION = 2
    private val MAGIC = "DCV1".toByteArray(Charsets.US_ASCII)

    @JvmStatic
    @JvmOverloads
    fun hello(continuous: Boolean = false): ByteArray = helloVersion(if (continuous) 2 else 1)

    @JvmStatic
    fun helloUnlimited(): ByteArray = helloVersion(3)

    private fun helloVersion(version: Int): ByteArray {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(0).put(MAGIC).putInt(version)
        return packet(HELLO, 0, 0, 0, payload.array())
    }

    @JvmStatic
    @JvmOverloads
    fun start(compositionTrial: Boolean = false): ByteArray {
        val payload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(if (compositionTrial) 0 else WINDOW_X).putInt(if (compositionTrial) 0 else WINDOW_Y)
            .putInt(if (compositionTrial) WIDTH else WINDOW_WIDTH).putInt(if (compositionTrial) HEIGHT else WINDOW_HEIGHT)
            .putInt(FPS).putInt(if (compositionTrial) 1 else 0)
        return packet(START, 0, 0, 0, payload.array())
    }

    /** RWVG: the small layout also uses the whole 1:1 frame; flag 2 still selects the small HMI layout. */
    @JvmStatic
    fun startCenterWindow(): ByteArray {
        val payload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(0).putInt(0).putInt(WIDTH).putInt(HEIGHT).putInt(FPS).putInt(2)
        return packet(START, 0, 0, 0, payload.array())
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun heartbeat(counter: Int, continuous: Boolean = false): ByteArray {
        if (counter < 1 || counter > frameLimit(continuous) + 4) {
            throw IOException("DCV1_HEARTBEAT_LIMIT")
        }
        return heartbeatUnlimited(counter)
    }

    @JvmStatic
    fun heartbeatUnlimited(counter: Int): ByteArray {
        val payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(0).putLong(0).putInt(counter)
        return packet(HEARTBEAT, 0, 0, 0, payload.array())
    }

    @JvmStatic
    fun stop(): ByteArray = packet(STOP, 0, 0, 0, ByteArray(0))

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun frame(annexB: ByteArray?, sequence: Int, ptsMillis: Long, continuous: Boolean = false): ByteArray {
        if (sequence < 1 || sequence > frameLimit(continuous)) {
            throw IOException("DCV1_FRAME_LIMIT")
        }
        return frameUnlimited(annexB, sequence, ptsMillis, sequence == 1)
    }

    /** Version 3 serial/PTS are uint32 bit patterns, not cumulative session budgets. */
    @JvmStatic
    @Throws(IOException::class)
    fun frameUnlimited(annexB: ByteArray?, sequence: Int, ptsMillis: Long, first: Boolean): ByteArray {
        if (annexB == null || annexB.isEmpty() || annexB.size > MAX_FRAME_BYTES ||
            ptsMillis < 0 || ptsMillis > 0xffffffffL) {
            throw IOException("DCV1_FRAME_LIMIT")
        }
        val types = inspectAnnexB(annexB)
        val idr = types and (1 shl 5) != 0
        if (first && (!idr || types and (1 shl 7) == 0 || types and (1 shl 8) == 0)) {
            throw IOException("DCV1_FIRST_FRAME_NEEDS_SPS_PPS_IDR")
        }
        if ((types and (1 shl 1) == 0 && !idr) || (idr && types and (1 shl 1) != 0)) {
            throw IOException("DCV1_INVALID_ACCESS_UNIT")
        }
        return packet(FRAME, sequence, ptsMillis, if (idr) 1 else 0, annexB)
    }

    private fun packet(type: Int, sequence: Int, pts: Long, flags: Int, payload: ByteArray): ByteArray {
        val result = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        result.put(MAGIC).putInt(type).putInt(payload.size).putInt(sequence).putInt(pts.toInt()).putInt(flags)
        result.put(payload)
        return result.array()
    }

    /** Asks for the end of one instrument log (0 the USB service, 1 the receiver) or its version (2). */
    @JvmStatic
    fun logRequest(file: Int): ByteArray = packet(LOG_FETCH, 0, 0, 0,
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(file).array())

    /** The byte count announced by a log reply. */
    @JvmStatic
    @Throws(IOException::class)
    fun logReplyLength(header: ByteArray?): Int {
        if (header == null || header.size != HEADER_BYTES) {
            throw IOException("DCV1_BAD_HEADER_LENGTH")
        }
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(8)
        if (length < 0 || length > LOG_TAIL_BYTES) {
            throw IOException("DCV1_LOG_LENGTH_INVALID")
        }
        validateEnvelope(header, LOG_FETCH or 0x80, length, 0, 0)
        return length
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateOffer(header: ByteArray?, payload: ByteArray?) {
        validateEnvelope(header, 0x85, OFFER_BYTES, 0, 0)
        if (payload == null || payload.size != OFFER_BYTES) {
            throw IOException("DCV1_BAD_OFFER_LENGTH")
        }
        for (value in payload) {
            if (value.toInt() != 0) {
                throw IOException("DCV1_UNEXPECTED_IDENTITY_OR_AUTH_OFFER")
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun reply(header: ByteArray?, payload: ByteArray?, operation: Int, sequence: Int, pts: Long,
        continuous: Boolean = false): Reply =
        replyInternal(header, payload, operation, sequence, pts, continuous, false)

    @JvmStatic
    @Throws(IOException::class)
    fun replyUnlimited(header: ByteArray?, payload: ByteArray?, operation: Int, sequence: Int, pts: Long): Reply =
        replyInternal(header, payload, operation, sequence, pts, true, true)

    @Throws(IOException::class)
    private fun replyInternal(header: ByteArray?, payload: ByteArray?, operation: Int, sequence: Int, pts: Long,
        continuous: Boolean, unlimited: Boolean): Reply {
        validateEnvelope(header, operation or 0x80, ACK_BYTES, sequence, pts)
        if (payload == null || payload.size != ACK_BYTES) {
            throw IOException("DCV1_BAD_ACK_LENGTH")
        }
        val values = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val result = Integer.toUnsignedLong(values.getInt())
        val flags = values.getInt()
        val frames = values.getInt()
        val lastPts = Integer.toUnsignedLong(values.getInt())
        if (flags and 31.inv() != 0 || (!unlimited && (frames < 0 || frames > frameLimit(continuous)))) {
            throw IOException("DCV1_INVALID_OR_FAILED_MEDIA_STATE")
        }
        if (result != 0L) {
            throw Rejected(operation, result, Reply(flags, frames, lastPts, unlimited))
        }
        if (flags and 8 != 0) {
            throw IOException("DCV1_INVALID_OR_FAILED_MEDIA_STATE cleanup_error=true")
        }
        if (operation == FRAME && (frames != sequence || lastPts != pts)) {
            throw IOException("DCV1_SUBMISSION_READBACK_MISMATCH")
        }
        return Reply(flags, frames, lastPts, unlimited)
    }

    private fun frameLimit(continuous: Boolean): Int =
        if (continuous) ProjectionLimits.MAX_FRAMES else MAX_TEST_FRAMES

    @Throws(IOException::class)
    private fun validateEnvelope(raw: ByteArray?, type: Int, length: Int, sequence: Int, pts: Long) {
        if (raw == null || raw.size != HEADER_BYTES) {
            throw IOException("DCV1_BAD_HEADER_LENGTH")
        }
        for (index in MAGIC.indices) {
            if (raw[index] != MAGIC[index]) {
                throw IOException("DCV1_WRONG_FIRMWARE_PROTOCOL")
            }
        }
        val values = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        values.position(4)
        if (values.getInt() != type || values.getInt() != length || values.getInt() != sequence ||
            Integer.toUnsignedLong(values.getInt()) != pts || values.getInt() != 0) {
            throw IOException("DCV1_REPLY_ENVELOPE_MISMATCH")
        }
    }

    @Throws(IOException::class)
    private fun inspectAnnexB(data: ByteArray): Int {
        var types = 0
        var offset = 0
        var count = 0
        while (offset < data.size) {
            val prefix = prefix(data, offset)
            if (prefix == 0 || offset + prefix >= data.size || ++count > 256) {
                throw IOException("DCV1_MALFORMED_ANNEX_B")
            }
            val start = offset + prefix
            val header = data[start].toInt() and 255
            val type = header and 31
            if (header and 128 != 0 || type < 1 || type > 23) {
                throw IOException("DCV1_INVALID_NAL_HEADER")
            }
            types = types or (1 shl type)
            offset = start + 1
            while (offset < data.size && prefix(data, offset) == 0) {
                offset++
            }
        }
        return types
    }

    private fun prefix(data: ByteArray, offset: Int): Int {
        if (offset + 3 <= data.size && data[offset].toInt() == 0 && data[offset + 1].toInt() == 0) {
            if (data[offset + 2].toInt() == 1) {
                return 3
            }
            if (offset + 4 <= data.size && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1) {
                return 4
            }
        }
        return 0
    }

    class Reply @JvmOverloads constructor(
        @JvmField val flags: Int,
        @JvmField val submissions: Int,
        @JvmField val lastPts: Long,
        private val modularCount: Boolean = false,
    ) {
        fun windowReported(): Boolean = flags == 23 && (modularCount || submissions >= 2)

        fun cleanupReported(): Boolean = flags == 0

        fun summary(): String = "RECEIVER_MEDIA context=" + (flags and 1 != 0) +
            " submitted=" + Integer.toUnsignedLong(submissions) + " video_window=" + (flags and 4 != 0) +
            " screen_visible=" + (flags and 16 != 0) + " last_pts_ms=" + lastPts +
            "; NOT decoded/post-frame counts, manual picture observation required"
    }

    /** A negative ACK is terminal; media state remains available for cleanup decisions. */
    class Rejected(
        @JvmField val operation: Int,
        @JvmField val code: Long,
        @JvmField val evidence: Reply,
    ) : IOException("DCV1_RECEIVER_REJECTED operation=" + operation + " code=" + code +
        " stage=" + stage(code) + " flags=" + evidence.flags +
        " submissions=" + evidence.submissions + " last_pts_ms=" + evidence.lastPts +
        " cleanup_error=" + (evidence.flags and 8 != 0)) {

        fun terminalCleanupReported(): Boolean =
            // Only the new FRAME stage codes guarantee cleanup BEFORE the negative ACK.
            operation == FRAME && code >= 2 && code <= 5 && evidence.cleanupReported()

        companion object {
            private const val serialVersionUID = 1L

            private fun stage(code: Long): String = when (code) {
                2L -> "AVC_PARSER"
                3L -> "MEDIA_SUBMIT"
                4L -> "VIDEO_WINDOW"
                5L -> "MEDIA_STATE"
                else -> "LEGACY_OR_OTHER"
            }
        }
    }
}
