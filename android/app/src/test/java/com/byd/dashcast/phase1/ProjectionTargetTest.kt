package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTargetTest {
    @Test
    fun primaryTargetRemainsTheDefaultAndHasNoRemoteSuffix() {
        val target = ProjectionTarget.primary()

        assertFalse(target.isRemote)
        assertEquals("primary", target.token())
        assertEquals("", target.shellArguments())
    }

    @Test
    fun remoteTargetUsesAnAllowlistedComponentAndFixedShellWords() {
        val target = ProjectionTarget.remote("com.example.maps", "com.example.maps.MainActivity",
            "地图")

        assertTrue(target.isRemote)
        assertEquals("remote:com.example.maps/com.example.maps.MainActivity", target.token())
        assertEquals("remote 'com.example.maps/com.example.maps.MainActivity'", target.shellArguments())
        assertEquals(target, ProjectionTarget.parse(target.token()))
    }

    @Test
    fun shellArgumentsDecodeRemoteTargetBeforeGeometry() {
        val args = arrayOf("600", "live", "remote", "com.example.maps/com.example.maps.MainActivity", "fit")

        assertTrue(ProjectionTarget.fromShellArguments(args).isRemote)
        assertEquals(CaptureOptions.Mode.FIT, CaptureOptions.fromShellArguments(args).mode())
    }

    @Test
    fun nonLiveRemoteProbeKeepsTheRawH264EntryPoint() {
        val args = arrayOf("5", "remote", "com.example.maps/com.example.maps.MainActivity")

        assertTrue(ProjectionTarget.fromShellArguments(args).isRemote)
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(args).mode())
    }

    @Test
    fun unsafeOrMalformedComponentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTarget.remoteComponent("com.example.maps;id/com.example.MainActivity", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTarget.remote("com.example.maps", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionTarget.parse("remote:com.example.maps/com.example.MainActivity bad")
        }
    }
}
