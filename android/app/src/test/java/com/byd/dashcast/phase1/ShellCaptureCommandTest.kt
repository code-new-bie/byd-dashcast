package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCaptureCommandTest {
    @Test
    fun explicitGeometryUsesFixedWordsAndStillQuotesInstalledApk() {
        val fit = ShellCaptureCommand.continuous("/data/app/x'\$y/base.apk", 600,
            CaptureOptions.of(CaptureOptions.Mode.FIT))
        assertTrue(fit.contains("x'\\''\$y"))
        assertTrue(fit.endsWith("ShellScreenCapture 600 live fit"))
        assertTrue(ShellCaptureCommand.continuous("/data/app/x/base.apk", 1800,
            CaptureOptions.region(10, 15, 95, 90)).endsWith("1800 live region 10 15 95 90"))
        assertThrows(IllegalArgumentException::class.java) {
            ShellCaptureCommand.continuous("/data/app/x/base.apk", 600, null)
        }
    }

    @Test
    fun remoteTargetIsPassedBeforeGeometryWithoutChangingInstrumentProtocol() {
        val command = ShellCaptureCommand.continuous("/data/app/x/base.apk", 600,
            CaptureOptions.of(CaptureOptions.Mode.FIT),
            ProjectionTarget.remoteComponent("com.example.maps/com.example.maps.MainActivity", null))

        assertTrue(command.endsWith("ShellScreenCapture 600 live remote '" +
            "com.example.maps/com.example.maps.MainActivity' fit"))
    }

    @Test
    fun continuousModeIsSeparateAndStillQuotesOnlyAnInstalledApk() {
        assertTrue(ShellCaptureCommand.continuous("/data/app/x'\$y/base.apk", 1800)
            .endsWith("ShellScreenCapture 1800 live"))
        assertTrue(ShellCaptureCommand.continuous("/data/app/x'\$y/base.apk", 600).contains("x'\\''\$y"))
        assertThrows(IllegalArgumentException::class.java) {
            ShellCaptureCommand.continuous("/sdcard/base.apk", 1800)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCaptureCommand.continuous("/data/app/x/base.apk", 7201)
        }
    }

    @Test
    fun fixedEntryPointQuotesInstalledApkAndDoesNotDisableAuthentication() {
        assertEquals("CLASSPATH='/data/app/abc/base.apk' /system/bin/app_process /system/bin " +
            "com.byd.dashcast.phase1.ShellScreenCapture 5",
            ShellCaptureCommand.create("/data/app/abc/base.apk", 5))
        assertTrue(ShellCaptureCommand.create("/data/app/x'\$y/base.apk", 10).contains("x'\\''\$y"))
        assertTrue(ShellCaptureCommand.create("/data/app/abc/base.apk", 5,
            ProjectionTarget.remoteComponent("com.example.maps/com.example.maps.MainActivity", null))
            .endsWith("ShellScreenCapture 5 remote 'com.example.maps/com.example.maps.MainActivity'"))
    }

    @Test
    fun rejectOtherPathsAndUnboundedOrControlArguments() {
        for (path in arrayOf(null, "base.apk", "/sdcard/base.apk", "/data/app/x\n/base.apk",
            "/data/app/x\r/base.apk", "/data/app/x\u0000/base.apk", "/data/app/x/base.jar")) {
            assertThrows(IllegalArgumentException::class.java) { ShellCaptureCommand.create(path, 5) }
        }
        for (seconds in intArrayOf(-1, 0, 11, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ShellCaptureCommand.create("/data/app/x/base.apk", seconds)
            }
        }
    }
}
