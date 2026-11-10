package com.byd.dashcast.phase1

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CaptureStopControlTest {
    @Test
    fun fixedStopAndOwnerPipeEofAreBoundedControlNotShellCommands() {
        for (input in arrayOf("STOP\n", "", "ST", "STOP\nignored")) {
            CaptureStopControl.readControl(ByteArrayInputStream(input.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun otherCommandsAndUnboundedLinesAreRejectedWithinFiveBytes() {
        for (input in arrayOf("stop\n", "STOPx", "kill", "x".repeat(10000))) {
            assertThrows(IOException::class.java) {
                CaptureStopControl.readControl(ByteArrayInputStream(input.toByteArray(Charsets.US_ASCII)))
            }
        }
    }
}
