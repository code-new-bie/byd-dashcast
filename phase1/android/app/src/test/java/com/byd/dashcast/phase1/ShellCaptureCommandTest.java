package com.byd.dashcast.phase1;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellCaptureCommandTest {
    @Test
    public void explicitGeometryUsesFixedWordsAndStillQuotesInstalledApk() {
        String fit = ShellCaptureCommand.continuous("/data/app/x'$y/base.apk", 600,
                CaptureOptions.of(CaptureOptions.Mode.FIT));
        assertTrue(fit.contains("x'\\''$y"));
        assertTrue(fit.endsWith("ShellScreenCapture 600 live fit"));
        assertTrue(ShellCaptureCommand.continuous("/data/app/x/base.apk", 1800,
                CaptureOptions.region(10, 15, 95, 90)).endsWith("1800 live region 10 15 95 90"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCaptureCommand.continuous("/data/app/x/base.apk", 600, null));
    }
    @Test
    public void continuousModeIsSeparateAndStillQuotesOnlyAnInstalledApk() {
        assertTrue(ShellCaptureCommand.continuous("/data/app/x'$y/base.apk", 1800)
                .endsWith("ShellScreenCapture 1800 live"));
        assertTrue(ShellCaptureCommand.continuous("/data/app/x'$y/base.apk", 600).contains("x'\\''$y"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCaptureCommand.continuous("/sdcard/base.apk", 1800));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCaptureCommand.continuous("/data/app/x/base.apk", 1801));
    }
    @Test
    public void fixedEntryPointQuotesInstalledApkAndDoesNotDisableAuthentication() {
        assertEquals("CLASSPATH='/data/app/abc/base.apk' /system/bin/app_process /system/bin "
                + "com.byd.dashcast.phase1.ShellScreenCapture 5",
                ShellCaptureCommand.create("/data/app/abc/base.apk", 5));
        assertTrue(ShellCaptureCommand.create("/data/app/x'$y/base.apk", 10).contains("x'\\''$y"));
    }

    @Test
    public void rejectOtherPathsAndUnboundedOrControlArguments() {
        for (String path : new String[] {null, "base.apk", "/sdcard/base.apk", "/data/app/x\n/base.apk",
                "/data/app/x\r/base.apk", "/data/app/x\0/base.apk", "/data/app/x/base.jar"}) {
            assertThrows(IllegalArgumentException.class, () -> ShellCaptureCommand.create(path, 5));
        }
        for (int seconds : new int[] {-1, 0, 11, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ShellCaptureCommand.create("/data/app/x/base.apk", seconds));
        }
    }
}
