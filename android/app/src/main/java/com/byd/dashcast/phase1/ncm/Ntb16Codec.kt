package com.byd.dashcast.phase1.ncm

import java.io.IOException

/**
 * Strict NTB16/no-CRC framing for the negotiated instrument profile.
 * @author Codex
 * @date 2026/10/10
 */
object Ntb16Codec {
    private const val HEADER_BYTES = 12
    private const val MAX_FRAMES = 32
    private const val MAX_ETHERNET_BYTES = 1514

    @JvmStatic
    @Throws(IOException::class)
    fun encode(frames: List<ByteArray?>?, sequence: Int, maxBytes: Int,
        ndpAlignment: Int, divisor: Int, remainder: Int): ByteArray {
        if (frames == null || frames.isEmpty() || frames.size > MAX_FRAMES ||
            maxBytes < 2048 || maxBytes > 65535 || ndpAlignment < 4 || ndpAlignment > 64 ||
            divisor < 1 || divisor > 64 || remainder < 0 || remainder >= divisor) {
            throw IOException("Unsupported NTB16 parameters")
        }
        val ndp = align(HEADER_BYTES, ndpAlignment)
        val ndpBytes = 8 + 4 * (frames.size + 1)
        val offsets = IntArray(frames.size)
        var end = ndp + ndpBytes
        for (index in frames.indices) {
            val frame = frames[index]
            if (frame == null || frame.size < 14 || frame.size > MAX_ETHERNET_BYTES) {
                throw IOException("Ethernet datagram outside negotiated MTU")
            }
            end += (remainder - end % divisor + divisor) % divisor
            offsets[index] = end
            end += frame.size
        }
        end = align(end, 4)
        if (end > maxBytes) {
            throw IOException("NTB16 exceeds device output limit")
        }
        val result = ByteArray(end)
        signature(result, 0, "NCMH")
        put16(result, 4, HEADER_BYTES)
        put16(result, 6, sequence and 65535)
        put16(result, 8, end)
        put16(result, 10, ndp)
        signature(result, ndp, "NCM0")
        put16(result, ndp + 4, ndpBytes)
        for (index in frames.indices) {
            val frame = frames[index]!!
            put16(result, ndp + 8 + 4 * index, offsets[index])
            put16(result, ndp + 10 + 4 * index, frame.size)
            System.arraycopy(frame, 0, result, offsets[index], frame.size)
        }
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(data: ByteArray?, received: Int): List<ByteArray> {
        if (data == null || received < HEADER_BYTES || received > data.size ||
            !matches(data, 0, "NCMH") || get16(data, 4) != HEADER_BYTES) {
            throw IOException("Invalid NTB16 header")
        }
        val blockBytes = get16(data, 8)
        val ndp = get16(data, 10)
        if (blockBytes != received || ndp < HEADER_BYTES || ndp > blockBytes - 12 ||
            !matches(data, ndp, "NCM0") || get16(data, ndp + 6) != 0) {
            throw IOException("Unsupported or truncated NTB16 block/NDP")
        }
        val ndpBytes = get16(data, ndp + 4)
        if (ndpBytes < 12 || ndpBytes % 4 != 0 || ndpBytes > blockBytes - ndp ||
            (ndpBytes - 8) / 4 > MAX_FRAMES + 1) {
            throw IOException("Invalid NDP16 length")
        }
        val frames = ArrayList<ByteArray>(MAX_FRAMES)
        var previousEnd = HEADER_BYTES
        var terminated = false
        var entry = ndp + 8
        while (entry < ndp + ndpBytes) {
            val offset = get16(data, entry)
            val length = get16(data, entry + 2)
            if (offset == 0 && length == 0) {
                terminated = true
                break
            }
            if (offset < previousEnd || length < 14 || length > MAX_ETHERNET_BYTES ||
                offset > blockBytes - length ||
                (offset < ndp + ndpBytes && offset + length > ndp)) {
                throw IOException("Invalid or overlapping Ethernet datagram")
            }
            frames.add(data.copyOfRange(offset, offset + length))
            previousEnd = offset + length
            entry += 4
        }
        if (!terminated) {
            throw IOException("NDP16 terminator missing")
        }
        return frames
    }

    private fun matches(data: ByteArray, offset: Int, value: String): Boolean {
        for (index in value.indices) {
            if (data[offset + index].toInt() != value[index].code) {
                return false
            }
        }
        return true
    }

    private fun signature(data: ByteArray, offset: Int, value: String) {
        for (index in value.indices) {
            data[offset + index] = value[index].code.toByte()
        }
    }

    private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    private fun get16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }
}
