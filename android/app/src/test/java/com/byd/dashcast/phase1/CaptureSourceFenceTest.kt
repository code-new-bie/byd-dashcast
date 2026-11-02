package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CaptureSourceFenceTest {
    @Test fun oldIdrAndNewPFramesCannotCrossAnOutputSourceEpoch() {
        val s = CaptureSourceFence(); s.changed(100_000_000_000)
        assertFalse(s.accept(99_999_999, true, 100_000_000_000))
        assertFalse(s.accept(100_000_000, false, 100_000_000_000))
        assertTrue(s.accept(100_000_001, true, 100_001_000_000))
        assertTrue(s.accept(100_033_333, false, 100_034_000_000))
        s.changed(101_000_000_000)
        assertFalse(s.accept(100_999_999, true, 101_001_000_000))
        assertFalse(s.accept(101_000_001, false, 101_001_000_000))
        assertTrue(s.accept(101_000_001, true, 101_001_000_000))
    }

    @Test fun syncRequestsAreBoundedAndAFailedHandoffIsNotAnUnlimitedVideoFreeze() {
        val s = CaptureSourceFence(); s.changed(100_000_000_000)
        assertTrue(s.needsSync(100_000_000_000))
        assertFalse(s.needsSync(100_249_999_999))
        assertTrue(s.needsSync(100_250_000_000))
        assertThrows(IOException::class.java) { s.accept(100_000_001, true, 104_000_000_000) }
        val silent = CaptureSourceFence(); silent.changed(100_000_000_000)
        assertThrows(IOException::class.java) { silent.needsSync(104_000_000_000) }
    }

    @Test fun aHealthySessionHasNoCumulativeFenceDurationLimit() {
        val s = CaptureSourceFence(); s.changed(100_000_000_000)
        assertTrue(s.accept(100_000_001, true, 100_001_000_000))
        assertFalse(s.needsSync(100_500_000_000))
        assertTrue(s.accept(100_000_000_000, false, 100_000_000_000_000))
        assertThrows(IOException::class.java) { s.accept(300_000_000_000, false, 100_000_000_000_000) }
    }
}
