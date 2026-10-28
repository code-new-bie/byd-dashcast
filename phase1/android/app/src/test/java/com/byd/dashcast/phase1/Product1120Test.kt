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
        // Real-vehicle feedback: a primary-sized portrait business source left the
        // full-mode video as a narrow strip. The mirror-hosted business display now
        // carries the carrier aspect itself.
        assertEquals(1696f / 440f,
            ContentViewport.FULL_MIRROR_SOURCE_WIDTH.toFloat() / ContentViewport.FULL_MIRROR_SOURCE_HEIGHT, 1e-4f)
        assertTrue(BusinessDisplayProfile.valid(ContentViewport.FULL_MIRROR_SOURCE_WIDTH,
            ContentViewport.FULL_MIRROR_SOURCE_HEIGHT, 480))
        val viewport = ContentViewport(ContentViewport.FULL_MIRROR_SOURCE_WIDTH,
            ContentViewport.FULL_MIRROR_SOURCE_HEIGHT, 112, 120, 1696, 440)
        val fit = SourceRenderMapping.create(viewport, false).content
        val crop = SourceRenderMapping.create(viewport, true).source
        assertEquals(112f / 1920f, fit[0], 1e-6f)
        assertEquals((720 - 120 - 440) / 720f, fit[1], 1e-6f)
        assertEquals(1696f / 1920f, fit[2], 1e-6f)
        assertEquals(440f / 720f, fit[3], 1e-6f)
        // Identical aspect: FIT performs no letterboxing and CROP clips nothing.
        assertEquals(1f, crop[2], 1e-6f);assertEquals(1f, crop[3], 1e-6f)
        // A portrait mirror page letterboxes the complete wide source; the carrier
        // never shrinks back to a primary-shaped strip when the preview attaches.
        val mirror = MirrorGeometry.fit(ContentViewport.FULL_MIRROR_SOURCE_WIDTH,
            ContentViewport.FULL_MIRROR_SOURCE_HEIGHT, 1080, 1920)
        assertEquals(1080f, mirror.width, 0.5f)
        assertEquals(1080f * 440f / 1696f, mirror.height, 0.5f)
    }
}
