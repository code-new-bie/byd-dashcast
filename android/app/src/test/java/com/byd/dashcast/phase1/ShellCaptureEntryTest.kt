package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated command -> source/geometry parser regressions from the named-entry failure. */
class ShellCaptureEntryTest {
    @Test
    fun actualNamedCommandRoundTripsEveryProductGeometryAndUnlimitedDuration() {
        for (token in listOf("viewport-center-crop", "viewport-center-fit",
            "viewport-full-crop", "viewport-full-fit", "fit", "crop", "region:0:5:100:95")) {
            val options = CaptureOptions.parse(token)
            val args = arguments(ShellCaptureCommand.continuous(APK, 0, options, ProjectionTarget.namedDisplay()))
            assertEquals("0", args[0])
            assertEquals("live", args[1])
            assertEquals("named", args[2])
            assertEquals(ProjectionTarget.namedDisplay(), ProjectionTarget.fromShellArguments(args))
            assertEquals(token, CaptureOptions.fromShellArguments(args).token())
        }
    }

    @Test
    fun primaryAndRemoteGeneratedCommandsKeepTheOriginalContracts() {
        val remote = ProjectionTarget.remoteComponent("com.example.map/com.example.map.MainActivity", null)
        for (target in listOf(ProjectionTarget.primary(), remote)) {
            val options = CaptureOptions.parse("viewport-full-fit")
            val args = arguments(ShellCaptureCommand.continuous(APK, 0, options, target))
            assertEquals(target, ProjectionTarget.fromShellArguments(args))
            assertEquals(options.token(), CaptureOptions.fromShellArguments(args).token())
        }
        val probe = arguments(ShellCaptureCommand.create(APK, 5, remote))
        assertEquals(remote, ProjectionTarget.fromShellArguments(probe))
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(probe).mode())
    }

    @Test
    fun malformedNamedGeometryAndUncontrolledPrefixesAreRejected() {
        for (args in arrayOf(
            arrayOf("0", "live", "named", "fit", "extra"),
            arrayOf("0", "live", "named", "remote", "com.test/.Main", "fit"),
            arrayOf("0", "live", "named", "region", "0", "0", "100"),
            arrayOf("0", "live", "remote"))) {
            assertThrows(IllegalArgumentException::class.java) { CaptureOptions.fromShellArguments(args) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTarget.fromShellArguments(arrayOf("0", "fit", "named"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTarget.fromShellArguments(arrayOf("5", "named"))
        }
    }

    private companion object {
        const val APK = "/data/app/test/base.apk"

        fun arguments(command: String): Array<String> {
            val marker = "com.byd.dashcast.phase1.ShellScreenCapture "
            val offset = command.indexOf(marker)
            assertTrue(offset >= 0)
            // Only the fixed suffix is decoded; nothing is executed by this test.
            // A component is already allowlisted and can contain no quote/space.
            // String.split in the Java version dropped trailing empty fields.
            return command.substring(offset + marker.length).replace("'", "").split(" ")
                .dropLastWhile { it.isEmpty() }.toTypedArray()
        }
    }
}
