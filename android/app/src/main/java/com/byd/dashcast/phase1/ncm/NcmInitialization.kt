package com.byd.dashcast.phase1.ncm

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Capability-driven CDC NCM negotiation, while the data interface is inactive.
 * Never probes a data-interface index or sends unadvertised optional requests.
 * @author Codex
 * @date 2026/10/11
 */
object NcmInitialization {
    private const val CONTROL_TIMEOUT_MS = 1000
    private const val NTB_PARAMETER_BYTES = 28
    private const val MIN_NTB_BYTES = 2048
    private const val MAX_NTB16_BYTES = 65535
    private const val PACKET_FILTER_CAPABILITY = 1
    private const val CRC_CAPABILITY = 0x10
    private const val INPUT_DATAGRAM_CAPABILITY = 0x20

    /** USB adapter; platform operations are injected for independent JVM tests. */
    interface Io {
        /** Selects the already-claimed data interface's alternate setting. */
        @Throws(IOException::class)
        fun alternate(setting: Int)

        /** Performs one bounded, interface-scoped class request. */
        @Throws(IOException::class)
        fun control(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, timeoutMs: Int): Int
    }

    /** Negotiated bounds for the NTB16/no-CRC framing implementation. */
    class Parameters internal constructor(data: ByteArray) {
        @JvmField val inBytes: Int
        @JvmField val outBytes: Int
        @JvmField val outAlignment: Int
        @JvmField val outDivisor: Int
        @JvmField val outRemainder: Int
        @JvmField val maxDatagrams: Int
        internal val formats: Int

        init {
            formats = u16(data, 2)
            val input = u32(data, 4)
            val output = u32(data, 16)
            outDivisor = u16(data, 20)
            outRemainder = u16(data, 22)
            outAlignment = u16(data, 24)
            val datagrams = u16(data, 26)
            if (u16(data, 0) != NTB_PARAMETER_BYTES || (formats != 1 && formats != 3) ||
                input < MIN_NTB_BYTES || output < MIN_NTB_BYTES ||
                outAlignment < 4 || outAlignment > 64 || outDivisor < 1 || outDivisor > 64 ||
                outRemainder >= outDivisor) {
                throw IOException("GET_NTB_PARAMETERS returned unsupported NTB16 bounds")
            }
            inBytes = minOf(input, 16384L).toInt()
            outBytes = minOf(output, 32768L).toInt()
            maxDatagrams = if (datagrams == 0) 8 else minOf(datagrams, 32)
        }
    }

    /** Completes all mandatory negotiation before activating bulk endpoints. */
    @JvmStatic
    @Throws(IOException::class)
    fun initialize(io: Io?, descriptor: NcmDescriptor?, cancelled: AtomicBoolean?): Parameters {
        if (io == null || descriptor == null || cancelled == null) {
            throw IllegalArgumentException("Missing NCM negotiation dependency")
        }
        checkCancellation(cancelled)
        io.alternate(0)
        val data = ByteArray(NTB_PARAMETER_BYTES)
        request(io, descriptor, cancelled, 0xa1, 0x80, 0, data, "GET_NTB_PARAMETERS")
        val parameters = Parameters(data)
        LogHub.i("ncm", "CAPABILITIES=0x" + Integer.toHexString(descriptor.capabilities) +
            " formats=" + parameters.formats + " control_if=" + descriptor.controlInterface +
            " data_if=" + descriptor.dataInterface)
        if (parameters.formats and 2 != 0) {
            request(io, descriptor, cancelled, 0x21, 0x84, 0, null, "SET_NTB_FORMAT")
        } else {
            LogHub.i("ncm", "SKIP_SET_NTB_FORMAT: device advertises NTB16 only")
        }
        if (descriptor.capabilities and CRC_CAPABILITY != 0) {
            request(io, descriptor, cancelled, 0x21, 0x8a, 0, null, "SET_CRC_MODE")
        } else {
            LogHub.i("ncm", "SKIP_SET_CRC_MODE: no CRC-mode capability; NCM0 framing")
        }
        val extended = descriptor.capabilities and INPUT_DATAGRAM_CAPABILITY != 0
        val inputSize = ByteArray(if (extended) 8 else 4)
        put32(inputSize, minOf(parameters.inBytes, MAX_NTB16_BYTES))
        if (extended) {
            inputSize[4] = 8
        }
        request(io, descriptor, cancelled, 0x21, 0x86, 0, inputSize, "SET_NTB_INPUT_SIZE")
        if (descriptor.capabilities and PACKET_FILTER_CAPABILITY != 0) {
            request(io, descriptor, cancelled, 0x21, 0x43, 0x0f, null, "SET_ETHERNET_PACKET_FILTER")
        } else {
            LogHub.i("ncm", "SKIP_SET_ETHERNET_PACKET_FILTER: capability absent")
        }
        checkCancellation(cancelled)
        io.alternate(1)
        LogHub.i("ncm", "NCM_INITIALIZED: mandatory negotiation accepted; TCP/video not yet verified")
        return parameters
    }

    @Throws(IOException::class)
    private fun request(io: Io, descriptor: NcmDescriptor, cancelled: AtomicBoolean, type: Int,
        request: Int, value: Int, buffer: ByteArray?, name: String) {
        checkCancellation(cancelled)
        val expected = buffer?.size ?: 0
        val result = io.control(type, request, value, descriptor.controlInterface, buffer, CONTROL_TIMEOUT_MS)
        LogHub.i("ncm", name + " index=" + descriptor.controlInterface + " bytes=" + expected + " -> " + result)
        if (result != expected) {
            throw IOException("$name rejected: returned=$result expected=$expected")
        }
    }

    @Throws(IOException::class)
    private fun checkCancellation(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("NCM negotiation cancelled; no next control request sent")
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xffL) or ((data[offset + 1].toLong() and 0xffL) shl 8) or
            ((data[offset + 2].toLong() and 0xffL) shl 16) or ((data[offset + 3].toLong() and 0xffL) shl 24)

    private fun put32(data: ByteArray, value: Int) {
        for (index in 0 until 4) {
            data[index] = (value ushr (8 * index)).toByte()
        }
    }
}
