package com.byd.dashcast.phase1

import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Streams Annex-B H.264 bytes to disk while counting key NAL units.
 *
 * @author Codex
 * @date 2026/10/09
 */
class H264StreamAnalyzer(outputStream: OutputStream?) : AdbShellClient.ShellOutputHandler {
    private val outputStream: OutputStream =
        outputStream ?: throw IllegalArgumentException("H.264 output stream must not be null")
    private var totalBytes = 0L
    private var startCodeCount = 0
    private var spsCount = 0
    private var ppsCount = 0
    private var idrCount = 0
    private var sliceCount = 0
    private var zeroCount = 0
    private var waitingForNalHeader = false

    /**
     * Accepts and analyzes one output chunk.
     *
     * @param data H.264 bytes
     * @param length valid byte count
     * @throws IOException when writing fails
     */
    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("invalid H.264 output chunk")
        }
        outputStream.write(data, 0, length)
        totalBytes += length
        for (index in 0 until length) {
            consumeByte(data[index].toInt() and 0xff)
        }
    }

    /** Flushes the captured stream; @throws IOException when flushing fails */
    @Throws(IOException::class)
    fun flush() {
        outputStream.flush()
    }

    /** @return a point-in-time statistics snapshot */
    fun snapshot(): Statistics = Statistics(totalBytes, startCodeCount, spsCount, ppsCount, idrCount, sliceCount)

    private fun consumeByte(value: Int) {
        if (waitingForNalHeader) {
            countNal(value and 0x1f)
            waitingForNalHeader = false
            zeroCount = 0
            return
        }
        if (value == 0) {
            zeroCount = minOf(zeroCount + 1, 4)
            return
        }
        if (value == 1 && zeroCount >= 2) {
            startCodeCount++
            waitingForNalHeader = true
        }
        zeroCount = 0
    }

    private fun countNal(nalType: Int) {
        when (nalType) {
            7 -> spsCount++
            8 -> ppsCount++
            5 -> idrCount++
            1 -> sliceCount++
        }
    }

    /**
     * Immutable H.264 statistics.
     *
     * @author Codex
     * @date 2026/10/09
     */
    class Statistics internal constructor(
        val totalBytes: Long,
        val startCodeCount: Int,
        val spsCount: Int,
        val ppsCount: Int,
        val idrCount: Int,
        val sliceCount: Int,
    ) {
        override fun toString(): String = String.format(Locale.US,
            "bytes=%d startCodes=%d sps=%d pps=%d idr=%d slices=%d",
            totalBytes, startCodeCount, spsCount, ppsCount, idrCount, sliceCount)
    }
}
