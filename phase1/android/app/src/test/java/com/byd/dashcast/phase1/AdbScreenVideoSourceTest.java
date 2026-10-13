package com.byd.dashcast.phase1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/** Only bounded numeric duration may enter the screenrecord command. */
public final class AdbScreenVideoSourceTest {
    @Test
    public void dcv1CommandMatchesReceiverSmallWindowWithoutExtraAuthorization() {
        assertEquals("screenrecord --output-format=h264 --size 640x240 --bit-rate 1000000 --time-limit 5 -",
                AdbScreenVideoSource.smallWindowCommand(5));
        assertThrows(IllegalArgumentException.class, () -> AdbScreenVideoSource.smallWindowCommand(11));
    }
    @Test
    public void emitsThePreviouslyValidatedCaptureGeometryToStdout() {
        assertEquals("screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000 --time-limit 5 -",
                AdbScreenVideoSource.command(5));
        assertFalse(AdbScreenVideoSource.command(10).contains(";"));
        assertFalse(AdbScreenVideoSource.command(10).contains("/sdcard"));
    }

    @Test
    public void rejectsUnboundedDuration() {
        for (int value : new int[] {-1, 0, 11, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> AdbScreenVideoSource.command(value));
        }
    }
}
