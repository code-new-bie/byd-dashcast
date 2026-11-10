package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Only bounded numeric duration may enter the screenrecord command. */
class AdbScreenVideoSourceTest {
    @Test
    fun confirmedFailureDoesNotBecomeUnknownProcessCleanup() {
        val source = failedSource(true, true)
        assertThrows(IOException::class.java) {
            source.runContinuous(3, "/data/app/test/base.apk", AtomicBoolean(), Runnable {})
        }
        assertTrue(source.exitConfirmed())
    }

    @Test
    fun missingExitOrCleanupEvidenceStillRequiresQuarantine() {
        for (state in arrayOf(booleanArrayOf(false, true), booleanArrayOf(true, false))) {
            val source = failedSource(state[0], state[1])
            assertThrows(IOException::class.java) {
                source.runContinuous(3, "/data/app/test/base.apk", AtomicBoolean(), Runnable {})
            }
            assertFalse(source.exitConfirmed())
        }
    }

    @Test
    fun dcv1CommandMatchesReceiverSmallWindowWithoutExtraAuthorization() {
        assertEquals("screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000 --time-limit 5 -",
            AdbScreenVideoSource.smallWindowCommand(5))
        assertThrows(IllegalArgumentException::class.java) { AdbScreenVideoSource.smallWindowCommand(11) }
    }

    @Test
    fun emitsThePreviouslyValidatedCaptureGeometryToStdout() {
        assertEquals("screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000 --time-limit 5 -",
            AdbScreenVideoSource.command(5))
        assertFalse(AdbScreenVideoSource.command(10).contains(";"))
        assertFalse(AdbScreenVideoSource.command(10).contains("/sdcard"))
    }

    @Test
    fun rejectsUnboundedDuration() {
        for (value in intArrayOf(-1, 0, 11, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { AdbScreenVideoSource.command(value) }
        }
    }

    private companion object {
        fun failedSource(exited: Boolean, released: Boolean): AdbScreenVideoSource {
            val capture = AdbScreenVideoSource.ControlledCapture { _, _, diagnostics, _, _, exit ->
                if (released) {
                    diagnostics.accept("ADB_CAPTURE_STDERR SHELL_CAPTURE_RESOURCES_RELEASED")
                }
                exit.accept(exited)
                throw IOException("test capture EXIT=1")
            }
            val observer = object : VideoPatternProbe.Observer {
                override fun log(text: String) {
                }

                override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) {
                }
            }
            return AdbScreenVideoSource(capture, observer, Consumer {})
        }
    }
}
