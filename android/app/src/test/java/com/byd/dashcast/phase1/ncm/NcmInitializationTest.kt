package com.byd.dashcast.phase1.ncm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact instrument descriptor/request regressions; no Android/USB runtime is involved. */
class NcmInitializationTest {
    @Test
    fun exactInstrumentDescriptorHasNoOptionalControlCapabilities() {
        val descriptor = NcmDescriptor.parse(instrumentConfig())
        assertEquals(0, descriptor.controlInterface)
        assertEquals(1, descriptor.dataInterface)
        assertEquals(0, descriptor.capabilities)
        assertEquals(0x0100, descriptor.version)
        assertEquals(1514, descriptor.maxSegmentBytes)
    }

    @Test
    fun qnxMinimalRequestsSucceedAndDataActivatesLast() {
        val io = FakeIo()
        val negotiated = initialize(io, 0)
        assertEquals(listOf("ALT0", "a1:80:index0:28", "21:86:index0:4", "ALT1"), io.calls)
        assertEquals(16384, negotiated.inBytes)
        assertEquals(32768, negotiated.outBytes)
        assertEquals(4, negotiated.outAlignment)
        assertEquals(4, negotiated.outDivisor)
        assertEquals(0, negotiated.outRemainder)
        assertEquals(8, negotiated.maxDatagrams)
        assertArrayEquals(byteArrayOf(0, 64, 0, 0), io.inputSize)
    }

    @Test
    fun oldUnconditionalFormatRequestIsRejectedByExactQnxContract() {
        val io = FakeIo()
        assertEquals(-1, io.control(0x21, 0x84, 0, 0, null, 1000))
        assertEquals(-1, io.control(0x21, 0x8a, 0, 0, null, 1000))
        assertEquals(-1, io.control(0x21, 0x43, 15, 0, null, 1000))
        assertEquals(-1, io.control(0xa1, 0x80, 0, 1, ByteArray(28), 1000))
        assertFalse(io.active)
    }

    @Test
    fun capabilitiesAreUsedRatherThanManufacturerOrVidPidGuessing() {
        val io = FakeIo()
        io.optional = true
        io.parameters[2] = 3
        initialize(io, 0x31)
        assertEquals(listOf("ALT0", "a1:80:index0:28", "21:84:index0:0", "21:8a:index0:0",
            "21:86:index0:8", "21:43:index0:0", "ALT1"), io.calls)
        assertArrayEquals(byteArrayOf(0, 64, 0, 0, 8, 0, 0, 0), io.inputSize)
    }

    @Test
    fun inputSizeRejectionDoesNotProbeOtherIndexesOrActivateData() {
        val io = FakeIo()
        io.reject = 0x86
        val error = assertThrows(IOException::class.java) { initialize(io, 0) }
        assertTrue(error.message!!.contains("SET_NTB_INPUT_SIZE"))
        assertEquals(listOf("ALT0", "a1:80:index0:28", "21:86:index0:4"), io.calls)
        assertFalse(io.active)
    }

    @Test
    fun shortParameterReplyFailsBeforeAnySetRequest() {
        val io = FakeIo()
        io.parameterLength = 27
        assertThrows(IOException::class.java) { initialize(io, 0) }
        assertEquals(listOf("ALT0", "a1:80:index0:28"), io.calls)
    }

    @Test
    fun invalidFramingBoundsFailBeforeInputSizeAndBulkActivation() {
        for (offset in intArrayOf(0, 2, 4, 16, 20, 24)) {
            val io = FakeIo()
            io.parameters[offset] = 0
            io.parameters[offset + 1] = 0
            assertThrows(IOException::class.java) { initialize(io, 0) }
            assertEquals(listOf("ALT0", "a1:80:index0:28"), io.calls)
        }
        val io = FakeIo()
        io.parameters[22] = 4
        assertThrows(IOException::class.java) { initialize(io, 0) }
    }

    @Test
    fun smallValidLimitsAreRespectedAndZeroDatagramCountIsNotRejected() {
        val io = FakeIo()
        put32(io.parameters, 4, 4096)
        put32(io.parameters, 16, 8192)
        val parameters = initialize(io, 0)
        assertEquals(4096, parameters.inBytes)
        assertEquals(8192, parameters.outBytes)
        assertEquals(8, parameters.maxDatagrams)
        assertArrayEquals(byteArrayOf(0, 16, 0, 0), io.inputSize)
    }

    @Test
    fun cancelledInitializationSendsNoControlOrAlternateWrites() {
        val io = FakeIo()
        assertThrows(IOException::class.java) {
            NcmInitialization.initialize(io, NcmDescriptor.parse(instrumentConfig()), AtomicBoolean(true))
        }
        assertTrue(io.calls.isEmpty())
    }

    @Test
    fun cancellationDuringReadDoesNotPerformTheNextWrite() {
        val io = FakeIo()
        val cancelled = AtomicBoolean()
        io.cancelOnRead = cancelled
        assertThrows(IOException::class.java) {
            NcmInitialization.initialize(io, NcmDescriptor.parse(instrumentConfig()), cancelled)
        }
        assertEquals(listOf("ALT0", "a1:80:index0:28"), io.calls)
    }

    @Test
    fun inactiveAlternateFailureStopsBeforeClassRequests() {
        val io = FakeIo()
        io.rejectAlternate = 0
        assertThrows(IOException::class.java) { initialize(io, 0) }
        assertEquals(listOf("ALT0"), io.calls)
    }

    @Test
    fun activeAlternateFailureDoesNotPretendBulkStarted() {
        val io = FakeIo()
        io.rejectAlternate = 1
        assertThrows(IOException::class.java) { initialize(io, 0) }
        assertFalse(io.active)
    }

    @Test
    fun incompleteAmbiguousAndStorageDescriptorsAreRejectedBeforeIo() {
        assertThrows(IOException::class.java) { NcmDescriptor.parse(null) }
        val raw = instrumentConfig()
        assertThrows(IOException::class.java) { NcmDescriptor.parse(raw.copyOf(raw.size - 1)) }
        for (alteration in arrayOf(intArrayOf(0, 1), intArrayOf(2, 0), intArrayOf(26, 2), intArrayOf(43, 0x1b),
            intArrayOf(45, 2), intArrayOf(59, 8), intArrayOf(81, 0x83))) {
            val changed = raw.clone()
            changed[alteration[0]] = alteration[1].toByte()
            assertThrows(IOException::class.java) { NcmDescriptor.parse(changed) }
        }
    }

    @Test
    fun descriptorNamesAndConfigurationPowerAreCorrect() {
        val dump = UsbDesc.dump(instrumentConfig())
        assertTrue(dump.contains("subtype=0x06 UNION"))
        assertTrue(dump.contains("subtype=0x0f ETHERNET"))
        assertTrue(dump.contains("subtype=0x1a NCM"))
        assertTrue(dump.contains("0mA"))
    }

    @Test
    fun retriesRetainEarlierLowLevelFailureEvidence() {
        LogHub.clear()
        LogHub.banner("VIDEO_ATTEMPT first")
        LogHub.e("ncm", "FIRST_FAILURE_GET_NTB_PARAMETERS")
        LogHub.banner("VIDEO_ATTEMPT second")
        assertTrue(LogHub.snapshot().contains("FIRST_FAILURE_GET_NTB_PARAMETERS"))
        assertTrue(LogHub.snapshot().contains("VIDEO_ATTEMPT second"))
    }

    private class FakeIo : NcmInitialization.Io {
        val calls = ArrayList<String>()
        val parameters = ByteArray(28)
        var reject = -1
        var rejectAlternate = -1
        var parameterLength = 28
        var optional = false
        var active = false
        var inputSize: ByteArray? = null
        var cancelOnRead: AtomicBoolean? = null

        init {
            parameters[0] = 28
            parameters[2] = 1
            put32(parameters, 4, 65535)
            parameters[8] = 1
            parameters[12] = 4
            put32(parameters, 16, 65535)
            parameters[20] = 4
            parameters[24] = 4
        }

        override fun alternate(setting: Int) {
            calls.add("ALT" + setting)
            if (setting == rejectAlternate) {
                throw IOException("SET_INTERFACE rejected")
            }
            active = setting == 1
        }

        override fun control(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, timeoutMs: Int): Int {
            assertEquals(1000, timeoutMs)
            calls.add(Integer.toHexString(type) + ":" + Integer.toHexString(request) +
                ":index" + index + ":" + (buffer?.size ?: 0))
            if (index != 0 || request == reject || active) {
                return -1
            }
            if (type == 0xa1 && request == 0x80) {
                System.arraycopy(parameters, 0, buffer!!, 0, parameterLength)
                cancelOnRead?.set(true)
                return parameterLength
            }
            if (type == 0x21 && request == 0x86) {
                inputSize = buffer!!.clone()
                return buffer.size
            }
            if (optional && type == 0x21 && (request == 0x84 || request == 0x8a || request == 0x43)) {
                return 0
            }
            return -1
        }
    }

    companion object {
        // Byte-for-byte HS configuration at VA 0xa12c in the factory OS's devu-usbncm DLL.
        private const val INSTRUMENT_CONFIG =
            "0902560002010440000904000001020d000605240010010524060001" +
                "0d240f0800000000ea0500000006241a00010007058103100004" +
                "09040100000a00010709040101020a0001070705820200020007050302000200"

        private fun initialize(io: FakeIo, capabilities: Int): NcmInitialization.Parameters {
            val descriptor = instrumentConfig()
            descriptor[46] = capabilities.toByte()
            return NcmInitialization.initialize(io, NcmDescriptor.parse(descriptor), AtomicBoolean())
        }

        @JvmStatic
        fun instrumentConfig(): ByteArray {
            val data = ByteArray(INSTRUMENT_CONFIG.length / 2)
            for (index in data.indices) {
                data[index] = Integer.parseInt(INSTRUMENT_CONFIG.substring(2 * index, 2 * index + 2), 16).toByte()
            }
            return data
        }

        private fun put32(data: ByteArray, offset: Int, value: Int) {
            for (index in 0 until 4) {
                data[offset + index] = (value ushr (8 * index)).toByte()
            }
        }
    }
}
