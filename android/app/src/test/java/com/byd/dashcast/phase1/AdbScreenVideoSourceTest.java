package com.byd.dashcast.phase1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Only bounded numeric duration may enter the screenrecord command. */
public final class AdbScreenVideoSourceTest {
    @Test
    public void confirmedFailureDoesNotBecomeUnknownProcessCleanup() {
        AdbScreenVideoSource source = failedSource(true, true);
        assertThrows(java.io.IOException.class, () -> source.runContinuous(3, "/data/app/test/base.apk",
                new java.util.concurrent.atomic.AtomicBoolean(), () -> {}));
        assertTrue(source.exitConfirmed());
    }

    @Test
    public void missingExitOrCleanupEvidenceStillRequiresQuarantine() {
        for (boolean[] state : new boolean[][] {{false, true}, {true, false}}) {
            AdbScreenVideoSource source = failedSource(state[0], state[1]);
            assertThrows(java.io.IOException.class, () -> source.runContinuous(3, "/data/app/test/base.apk",
                    new java.util.concurrent.atomic.AtomicBoolean(), () -> {}));
            assertFalse(source.exitConfirmed());
        }
    }

    private static AdbScreenVideoSource failedSource(boolean exited, boolean released) {
        AdbScreenVideoSource.ControlledCapture capture = (command, handler, diagnostics, stopped, beforeStop, exit) -> {
            if (released) {
                diagnostics.accept("ADB_CAPTURE_STDERR SHELL_CAPTURE_RESOURCES_RELEASED");
            }
            exit.accept(exited);
            throw new java.io.IOException("test capture EXIT=1");
        };
        VideoPatternProbe.Observer observer = new VideoPatternProbe.Observer() {
            @Override
            public void log(String text) {
            }

            @Override
            public void frame(byte[] bytes, long pts, boolean key) {
            }
        };
        return new AdbScreenVideoSource(capture, observer, text -> {});
    }

    @Test
    public void dcv1CommandMatchesReceiverSmallWindowWithoutExtraAuthorization() {
        assertEquals("screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000 --time-limit 5 -",
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
