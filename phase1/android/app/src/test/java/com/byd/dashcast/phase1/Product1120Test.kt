package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Product1120Test {
    private fun window(id: Int = 7, uid: Int = 10123, visible: String = "0x0", surface: Boolean = true) =
        "Window #0 Window{ignored-title}:\n mDisplayId=$id\n mOwnerUid=$uid\n mViewVisibility=$visible\n mHasSurface=$surface\n"
    @Test fun namedOwnersExcludeOwnMainAndSystemAndRejectUnknownFields() {
        assertEquals(setOf(10123), NamedWindowPolicy.owners(window(), 7, 10124, 0))
        assertTrue(NamedWindowPolicy.owners(window(id=0), 7, 10124, 0).isEmpty())
        assertTrue(NamedWindowPolicy.owners(window(uid=10124), 7, 10124, 0).isEmpty())
        assertTrue(NamedWindowPolicy.owners(window(uid=1000), 7, 10124, 0).isEmpty())
        assertTrue(NamedWindowPolicy.owners(window(visible="0x8"), 7, 10124, 0).isEmpty())
        assertTrue(NamedWindowPolicy.owners(window(surface=false), 7, 10124, 0).isEmpty())
        assertThrows(IOException::class.java) { NamedWindowPolicy.owners(window(uid=110123), 7, 10124, 0) }
        for (field in listOf("mDisplayId=7", "mOwnerUid=10123", "mViewVisibility=0x0")) {
            assertThrows(IOException::class.java) { NamedWindowPolicy.owners(window().replace(field,""), 7, 10124, 0) }
        }
        assertThrows(IOException::class.java) { NamedWindowPolicy.owners("unknown output", 7, 10124, 0) }
    }
    @Test fun namedTargetCannotAccidentallySelectPrimaryAndNamesNeverCollideSilently() {
        val target = ProjectionTarget.namedDisplay()
        assertEquals(target, ProjectionTarget.parse(target.token()))
        assertEquals(target, ProjectionTarget.fromShellArguments(arrayOf("0","live","named","viewport-center-fit")))
        assertNotEquals(ProjectionTarget.primary(), target)
        assertFalse(target.isRemote)
        assertEquals("named", target.shellArguments())
        assertTrue(NamedDisplayContract.matchesMeter(NamedDisplayContract.NAME))
        assertTrue(NamedDisplayContract.matchesMeter("叠加视图 #1"))
        assertFalse(NamedDisplayContract.matchesMeter("dashcast-independent-1"))
    }
    @Test fun onlyExactMapButtonIdsWithOneSecondDebounce() {
        val id = "com.byd.automap:id/v_main_home_expand_screen_btn"
        assertTrue(MapButtonPolicy.accepts("com.byd.automap",id,1000,0))
        assertFalse(MapButtonPolicy.accepts("com.byd.automap",id,999,0))
        assertFalse(MapButtonPolicy.accepts("com.byd.automap",id,999,1000))
        assertFalse(MapButtonPolicy.accepts("other.app",id,1000,0))
        assertFalse(MapButtonPolicy.accepts("com.byd.automap",id+"x",1000,0))
    }
    @Test fun renderDeadlinesDoNotAccumulatePollingDelayOrBurstAfterMissedFrames() {
        val clock = FrameSubmissionClock(100)
        var now=0L
        repeat(1000) {
            if (clock.due(now,true)) { clock.submitted(now) }
            now += 7
        }
        assertTrue(clock.delayNanos(now) <= 100)
        assertTrue(clock.due(10000,true));clock.submitted(10000)
        assertFalse(clock.due(10000,true));assertFalse(clock.due(10001,true))
        assertFalse(clock.due(0,true));assertFalse(clock.due(10100,false))
        assertTrue(clock.due(10100,true))
    }
    @Test fun usableMainAreaIsTheBusinessAspectWithoutPillarboxOrDistortion() {
        val box = MirrorGeometry.fit(1920,936,1920,936)
        assertEquals(0f,box.left,0f);assertEquals(0f,box.top,0f)
        assertEquals(1920f,box.width,0f);assertEquals(936f,box.height,0f)
        val video = SourceRenderMapping.create(ContentViewport(1920,936,64,112,1792,448),false)
        assertNotNull(video)
    }
    @Test fun fullModeMirrorBusinessFillsCarrierAndMirrorKeepsCompleteSource() {
        // Use the actual main-screen/client-area business profiles. The former
        // carrier-shaped 3392x912 fixture concealed FIT shrinking ordinary apps.
        for ((width, height) in listOf(1920 to 1080, 1920 to 936, 1080 to 1794)) {
            val profile = BusinessDisplayProfile(width, height, 160)
            for (small in listOf(false, true)) {
                val viewport = ContentViewport.forBusiness(profile, small)
                assertEquals(width, viewport.sourceWidth)
                assertEquals(height, viewport.sourceHeight)
                val mapping = SourceRenderMapping.create(viewport, ProductSettings.DEFAULT_CROP)
                assertArrayEquals(viewport.normalized(), mapping.content, 1e-6f)
                assertTrue(mapping.source[2] < 1f || mapping.source[3] < 1f)
                // Main control always fits the complete source to its own client
                // area; the instrument's crop does not modify either dimension.
                val mirror = MirrorGeometry.fit(width, height, width, height)
                assertEquals(0f, mirror.left, 0f);assertEquals(0f, mirror.top, 0f)
                assertEquals(width.toFloat(), mirror.width, 0f)
                assertEquals(height.toFloat(), mirror.height, 0f)
            }
        }
    }
}
