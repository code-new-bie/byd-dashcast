package com.byd.dashcast.phase1

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.function.Consumer

class AvcReceiverContractTest {
    private class Bits {
        val text = StringBuilder()

        fun raw(value: Int, size: Int): Bits {
            for (index in size - 1 downTo 0) { text.append((value ushr index) and 1) }
            return this
        }

        fun ue(value: Int): Bits {
            val size = 32 - Integer.numberOfLeadingZeros(value + 1)
            raw(0, size - 1); return raw(value + 1, size)
        }

        fun nal(type: Int): ByteArray {
            raw(1, 1)
            while (text.length % 8 != 0) { raw(0, 1) }
            val out = ByteArrayOutputStream()
            out.write(0); out.write(0); out.write(1); out.write(0x60 or type)
            var zeros = 0
            var i = 0
            while (i < text.length) {
                val value = Integer.parseInt(text.substring(i, i + 8), 2)
                if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0 }
                out.write(value); zeros = if (value == 0) zeros + 1 else 0
                i += 8
            }
            return out.toByteArray()
        }
    }

    private fun frame(profile: Int, width: Int, height: Int, spsId: Int, ppsId: Int, progressive: Boolean): ByteArray {
        val sps = Bits().raw(profile, 8).raw(0xc0, 8).raw(40, 8).ue(spsId)
            .ue(0).ue(2).ue(1).raw(0, 1).ue(width / 16 - 1).ue(height / 16 - 1)
            .raw(if (progressive) 1 else 0, 1).raw(1, 1).raw(0, 1).nal(7)
        val pps = Bits().ue(ppsId).ue(spsId).nal(8)
        val idr = Bits().ue(0).ue(2).ue(ppsId).nal(5)
        val out = ByteArrayOutputStream()
        for (nal in arrayOf(sps, pps, idr)) { out.write(nal, 0, nal.size) }
        return out.toByteArray()
    }

    @Test
    fun baselineProgressiveGeometryAndFirstIdrPassWithHeaderDiagnostics() {
        val contract = AvcReceiverContract()
        val log = ArrayList<String>()
        contract.accept(frame(66, 1920, 720, 0, 0, true), Consumer { log.add(it) })
        contract.accept(Bits().ue(0).ue(0).ue(0).nal(1), Consumer { log.add(it) })
        assertTrue(log.any { it.contains("profile=66") })
        assertTrue(log.any { it.contains("1920x720") })
    }

    @Test
    fun mainHighWrongSizeIdsOrInterlaceFailBeforeAnyMediaOperation() {
        val invalid = arrayOf(frame(77, 1920, 720, 0, 0, true), frame(100, 1920, 720, 0, 0, true),
            frame(66, 1280, 720, 0, 0, true), frame(66, 640, 240, 1, 0, true),
            frame(66, 1920, 720, 0, 1, true), frame(66, 1920, 720, 0, 0, false),
            Bits().ue(0).ue(2).ue(0).nal(5))
        for (data in invalid) {
            assertThrows(IOException::class.java) { AvcReceiverContract().accept(data, Consumer {}) }
        }
    }

    @Test
    fun rejectMalformedTruncatedOversizeAndBSlices() {
        for (data in arrayOf<ByteArray?>(null, ByteArray(0), byteArrayOf(0, 0, 1, 0x67),
            ByteArray(0x200001), byteArrayOf(0, 0, 1, 0xe7.toByte(), 1))) {
            assertThrows(IOException::class.java) { AvcReceiverContract().accept(data, Consumer {}) }
        }
        val contract = AvcReceiverContract()
        contract.accept(frame(66, 1920, 720, 0, 0, true), Consumer {})
        assertThrows(IOException::class.java) {
            contract.accept(Bits().ue(0).ue(1).ue(0).nal(1), Consumer {})
        }
    }
}
