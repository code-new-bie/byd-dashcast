package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Objects

/**
 * Bounded progressive P/I AVC framing for screenrecord output, with or without AUD NALs.
 * ADB chunk boundaries are not video frame boundaries. Reject B/SP/SI/data-partition streams.
 *
 * @author Codex
 * @date 2026/10/11
 */
internal class AnnexBAccessUnitReader(observer: Observer?) : AdbShellClient.ShellOutputHandler {
    private val observer: Observer = Objects.requireNonNull(observer)!!
    private val nal = ByteArrayOutputStream(16 * 1024)
    private val unit = ByteArrayOutputStream(64 * 1024)
    private var zeros = 0
    private var inputBytes = 0
    private var frames = 0
    private var unitNals = 0
    private var started = false
    private var hasSlice = false
    private var keyFrame = false
    private var finished = false
    private var failed = false

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid Annex-B output chunk")
        }
        if (finished || failed) {
            throw IOException("AVC stream already finished/failed")
        }
        if (length > MAX_BYTES - inputBytes) {
            failed = true
            throw IOException("Screenrecord validation byte limit exceeded")
        }
        inputBytes += length
        try {
            for (index in 0 until length) {
                consumeByte(data[index].toInt() and 255)
            }
        } catch (error: IOException) {
            failed = true
            throw error
        }
    }

    @Throws(IOException::class)
    private fun consumeByte(value: Int) {
        if (value == 0) {
            if (++zeros > MAX_FRAME) {
                throw IOException("Malformed/oversize zero run in AVC stream")
            }
            return
        }
        if (value == 1 && zeros >= 2) {
            if (started) {
                consumeNal()
            }
            started = true
            zeros = 0
            return
        }
        if (!started) {
            throw IOException("Expected Annex-B screenrecord output, not text/another format")
        }
        if (zeros > MAX_FRAME - nal.size() - 1) {
            throw IOException("AVC NAL limit exceeded")
        }
        while (zeros > 0) {
            nal.write(0)
            zeros--
        }
        nal.write(value)
    }

    @Throws(IOException::class)
    private fun consumeNal() {
        val bytes = nal.toByteArray()
        nal.reset()
        if (bytes.isEmpty()) {
            throw IOException("Empty AVC NAL")
        }
        val type = bytes[0].toInt() and 31
        if (bytes[0].toInt() and 128 != 0 || type < 1 || type > 12 || (type >= 2 && type <= 4)) {
            throw IOException("Unsupported AVC NAL type=$type")
        }
        val slice = type == 1 || type == 5
        if (slice) {
            val bits = SliceBits(bytes)
            val firstMb = bits.unsignedExpGolomb()
            val sliceType = bits.unsignedExpGolomb()
            if (firstMb > 8191 || sliceType > 9 || (sliceType % 5 != 0 && sliceType % 5 != 2)) {
                throw IOException("Only bounded progressive P/I AVC slices are supported")
            }
            if (hasSlice && firstMb == 0) {
                emit()
            } else if (!hasSlice && firstMb != 0) {
                throw IOException("AVC picture missing its first slice")
            }
            if (hasSlice && keyFrame != (type == 5)) {
                throw IOException("Mixed IDR/non-IDR slices within one picture")
            }
        } else if (hasSlice && (type == 6 || type == 7 || type == 8 || type == 9)) {
            emit()
        }
        if (++unitNals > 256 || bytes.size > MAX_FRAME - PREFIX.size - unit.size()) {
            throw IOException("AVC access-unit limit exceeded")
        }
        unit.write(PREFIX, 0, PREFIX.size)
        unit.write(bytes, 0, bytes.size)
        if (slice) {
            hasSlice = true
            keyFrame = type == 5
        }
    }

    @Throws(IOException::class)
    private fun emit() {
        if (!hasSlice || frames >= NoAuthVideoProtocol.MAX_TEST_FRAMES) {
            throw IOException("AVC picture/frame-count limit")
        }
        observer.frame(unit.toByteArray(), keyFrame)
        frames++
        unit.reset()
        unitNals = 0
        hasSlice = false
        keyFrame = false
    }

    /** Call only after a successful shell-v2 EXIT, never after transport/capture failure. */
    @Throws(IOException::class)
    fun finish() {
        if (failed) {
            throw IOException("Cannot finish a failed AVC stream")
        }
        if (finished) {
            return
        }
        try {
            if (!started || nal.size() == 0) {
                throw IOException("No complete final AVC NAL")
            }
            consumeNal()
            // trailing_zero_8bits are not NAL payload. Non-VCL trailer may follow the last picture.
            zeros = 0
            if (hasSlice) {
                emit()
            }
            if (frames == 0) {
                throw IOException("Screenrecord produced no AVC pictures")
            }
            finished = true
        } catch (error: IOException) {
            failed = true
            throw error
        }
    }

    fun frameCount(): Int = frames

    fun byteCount(): Int = inputBytes

    fun interface Observer {
        @Throws(IOException::class)
        fun frame(data: ByteArray, keyFrame: Boolean)
    }

    /** Reads only the two required slice prefix fields, skipping RBSP emulation-prevention bytes. */
    private class SliceBits(private val bytes: ByteArray) {
        private var offset = 1
        private var bitsLeft = 0
        private var current = 0
        private var zeroBytes = 0

        @Throws(IOException::class)
        fun unsignedExpGolomb(): Int {
            var leading = 0
            while (bit() == 0) {
                if (++leading > 30) {
                    throw IOException("Malformed AVC Exp-Golomb prefix")
                }
            }
            var suffix = 0
            repeat(leading) {
                suffix = (suffix shl 1) or bit()
            }
            return (1 shl leading) - 1 + suffix
        }

        @Throws(IOException::class)
        private fun bit(): Int {
            if (bitsLeft == 0) {
                if (offset < bytes.size && zeroBytes >= 2 && bytes[offset].toInt() and 255 == 3) {
                    offset++
                    zeroBytes = 0
                }
                if (offset >= bytes.size) {
                    throw IOException("Truncated AVC slice header")
                }
                current = bytes[offset++].toInt() and 255
                zeroBytes = if (current == 0) zeroBytes + 1 else 0
                bitsLeft = 8
            }
            bitsLeft--
            return (current ushr bitsLeft) and 1
        }
    }

    private companion object {
        val PREFIX = byteArrayOf(0, 0, 0, 1)
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FRAME = NoAuthVideoProtocol.MAX_FRAME_BYTES
    }
}
