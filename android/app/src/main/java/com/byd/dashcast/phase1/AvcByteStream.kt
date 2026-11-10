package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Validates Annex-B or converts four-byte length-prefixed AVC access units.
 *
 * @author Codex
 * @date 2026/10/10
 */
object AvcByteStream {
    /** @param data Annex-B access unit; @return NALs except existing AUDs, before the muxer adds one AUD */
    @JvmStatic
    fun withoutAud(data: ByteArray?): ByteArray {
        if (data == null || prefixLength(data, 0) == 0) {
            throw IllegalArgumentException("Annex-B access unit required")
        }
        val output = ByteArrayOutputStream(data.size)
        var cursor = 0
        while (cursor < data.size) {
            val prefix = prefixLength(data, cursor)
            if (prefix == 0 || cursor + prefix >= data.size) {
                throw IllegalArgumentException("Truncated Annex-B NAL")
            }
            var next = cursor + prefix + 1
            while (next < data.size && prefixLength(data, next) == 0) {
                next++
            }
            if (data[cursor + prefix].toInt() and 31 != 9) {
                output.write(data, cursor, next - cursor)
            }
            cursor = next
        }
        return output.toByteArray()
    }

    private fun prefixLength(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size || data[offset].toInt() != 0 || data[offset + 1].toInt() != 0) {
            return 0
        }
        if (data[offset + 2].toInt() == 1) {
            return 3
        }
        return if (offset + 4 <= data.size && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1) 4 else 0
    }

    /** AVCDecoderConfigurationRecord or ordinary Annex-B/AVCC codec configuration. */
    @JvmStatic
    fun normalizeConfiguration(input: ByteBuffer?): ByteArray {
        if (input == null || !input.hasRemaining()) {
            throw IllegalArgumentException("Empty AVC configuration")
        }
        val source = input.duplicate()
        val data = ByteArray(source.remaining())
        source.get(data)
        if (data.size < 7 || data[0].toInt() != 1 || data[4].toInt() and 0xfc != 0xfc) {
            return normalize(input)
        }
        if (data[4].toInt() and 3 != 3 || data[5].toInt() and 0xe0 != 0xe0) {
            throw IllegalArgumentException("Only four-byte AVC length configuration supported")
        }
        val output = ByteArrayOutputStream(data.size + 16)
        var offset = 6
        val spsCount = data[5].toInt() and 31
        if (spsCount == 0) {
            throw IllegalArgumentException("SPS missing in AVC configuration")
        }
        repeat(spsCount) {
            offset = appendConfigurationNal(data, offset, 7, output)
        }
        if (offset >= data.size || data[offset].toInt() == 0) {
            throw IllegalArgumentException("PPS missing in AVC configuration")
        }
        val ppsCount = data[offset].toInt() and 255
        offset++
        repeat(ppsCount) {
            offset = appendConfigurationNal(data, offset, 8, output)
        }
        if (offset != data.size) {
            throw IllegalArgumentException("Unsupported AVC configuration extension")
        }
        return output.toByteArray()
    }

    private fun appendConfigurationNal(data: ByteArray, start: Int, type: Int, output: ByteArrayOutputStream): Int {
        if (start > data.size - 2) {
            throw IllegalArgumentException("Truncated AVC configuration NAL length")
        }
        val length = (data[start].toInt() and 255 shl 8) or (data[start + 1].toInt() and 255)
        val offset = start + 2
        if (length < 1 || length > data.size - offset || data[offset].toInt() and 128 != 0 ||
            data[offset].toInt() and 31 != type) {
            throw IllegalArgumentException("Invalid AVC configuration NAL")
        }
        output.write(0)
        output.write(0)
        output.write(0)
        output.write(1)
        output.write(data, offset, length)
        return offset + length
    }

    /** @param input encoded bytes; @return Annex-B copy; @throws IllegalArgumentException on malformed input */
    @JvmStatic
    fun normalize(input: ByteBuffer?): ByteArray {
        if (input == null || !input.hasRemaining()) {
            throw IllegalArgumentException("Empty AVC input")
        }
        val source = input.duplicate()
        val data = ByteArray(source.remaining())
        source.get(data)
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))) {
            return data
        }
        val output = ByteArrayOutputStream(data.size + 16)
        var cursor = 0
        while (cursor < data.size) {
            if (data.size - cursor < 4) {
                throw IllegalArgumentException("Truncated AVC length")
            }
            val length = ((data[cursor].toLong() and 0xff) shl 24) or ((data[cursor + 1].toLong() and 0xff) shl 16) or
                ((data[cursor + 2].toLong() and 0xff) shl 8) or (data[cursor + 3].toLong() and 0xff)
            cursor += 4
            if (length == 0L || length > data.size - cursor) {
                throw IllegalArgumentException("Invalid AVC NAL length")
            }
            output.write(0)
            output.write(0)
            output.write(0)
            output.write(1)
            output.write(data, cursor, length.toInt())
            cursor += length.toInt()
        }
        return output.toByteArray()
    }
}
