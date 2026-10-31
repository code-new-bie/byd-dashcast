package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class MirrorWorkspace1180Test {
    @Test fun twoHighResolutionSourcesPreserveCarrierAspectAndLogicalSize() {
        for (small in listOf(false, true)) {
            val profile = WorkspaceDisplayProfile.forMode(small)
            val carrier = if (small) ContentViewport.center() else ContentViewport.full()
            val viewport = ContentViewport.forWorkspace(profile, small)
            val factor = profile.density / 160f
            assertTrue(factor >= 2)
            assertEquals(carrier.physicalAspect, profile.width.toFloat() / profile.height, 0.0001f)
            assertEquals(if (small) 768f else 1696f, profile.width / factor, 0.001f)
            assertEquals(if (small) 432f else 456f, profile.height / factor, 0.001f)
            for (crop in listOf(false, true)) {
                val mapping = SourceRenderMapping.create(viewport, crop)
                assertArrayEquals(carrier.normalized(), mapping.content, 0f)
                assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), mapping.source, 0f)
            }
        }
    }

    @Test fun mainControlWindowUsesCompleteSourceWithoutUpscalingOnTargetClient() {
        for (small in listOf(false, true)) {
            val profile = WorkspaceDisplayProfile.forMode(small)
            val window = MirrorGeometry.fit(profile.width, profile.height, 1920, 990)
            assertTrue(window.width <= profile.width && window.height <= profile.height)
            assertEquals(profile.width / profile.height.toFloat(), window.width / window.height, 0.0001f)
            assertTrue(window.width >= 1700 || window.height >= 900)
            assertFalse(window.contains(window.left - 1, window.top - 1))
        }
    }

    @Test fun workspaceTargetsRoundTripWithoutChangingLegacyNativeRoutes() {
        val original = ProjectionTarget.remoteComponent("com.example.app/.Main", "Example")
        val workspace = ProjectionTarget.workspaceComponent(original.component(), original.displayLabel())
        assertTrue(workspace.isRemote && workspace.isWorkspace)
        assertFalse(original.isWorkspace)
        assertNotEquals(original, workspace)
        assertEquals(workspace, ProjectionTarget.parse(workspace.token()))
        assertEquals(workspace, ProjectionTarget.fromShellArguments(arrayOf("0", "live", "workspace", original.component())))
        assertEquals("workspace 'com.example.app/.Main'", workspace.shellArguments())
        val options = CaptureOptions.fromShellArguments(arrayOf("0", "live", "workspace",
            original.component(), "viewport-full-fit"))
        assertTrue(options.contentViewport())
        assertFalse(options.centerWindow())
    }

    @Test fun workspaceCannotAcceptUnsafeComponents() {
        for (component in listOf("com.example.app/.Main;reboot", "com.example.app/", "com.example.app/.Main'")) {
            try {
                ProjectionTarget.workspaceComponent(component, null)
                fail("Unsafe component accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
