package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

/** Policy/geometry regressions; separate runtime probes exercise real GL/Android tasks.
 * @author Codex
 * @date 2026/10/25
 */
class ProjectionRefinementTest {
    @Test fun redrawCannotLaunderAPreHandoffOrUninitializedProducerBuffer() {
        assertFalse(FrameSubmissionClock.sourceReady(-1, 0))
        assertFalse(FrameSubmissionClock.sourceReady(99_999, 100_000))
        assertTrue(FrameSubmissionClock.sourceReady(100_000, 100_000))
        assertFalse(FrameSubmissionClock.sourceReady(100_000, 200_000))
        assertTrue(FrameSubmissionClock.sourceReady(200_001, 200_000))
        assertFalse(FrameSubmissionClock.validProducer(99_999, 100_000, 200_000))
        assertFalse(FrameSubmissionClock.validProducer(1_000_200_001, 100_000, 200_000))
        assertTrue(FrameSubmissionClock.validProducer(100_000, 100_000, 200_000))
        assertTrue(FrameSubmissionClock.validProducer(100_001, 100_000, 200_000))
    }
    @Test fun noSubmittedPictureUntilAnActualSourceBufferExists() {
        val clock = FrameSubmissionClock()
        assertFalse(clock.due(0, false)); assertFalse(clock.due(10_000_000_000, false))
        assertTrue(clock.due(0, true)); clock.submitted(0)
        assertFalse(clock.due(33_333_333, true)); assertTrue(clock.due(33_333_334, true))
        assertEquals(3_333_334L,clock.delayNanos(30_000_000))
        assertEquals(0L,clock.delayNanos(33_333_334))
    }
    @Test fun staticFrameIsSubmittedAtActualTimesNotRetimedAfterDelay() {
        val clock = FrameSubmissionClock()
        for (now in listOf(1L, 40_000_001L, 90_000_001L, 9_000_000_001L)) {
            assertTrue(clock.due(now, true)); clock.submitted(now)
            assertFalse(clock.due(now, true))
        }
        assertFalse(clock.due(1L, true))
    }
    @Test fun noMoreThanThirtyRealSubmissionsPerSecond() {
        val clock = FrameSubmissionClock()
        var total = 0
        for (millis in 0L until 1000) {
            if (clock.due(millis * 1_000_000, true)) { clock.submitted(millis * 1_000_000); total++ }
        }
        assertEquals(30, total)
    }
    @Test fun coldForwardMayAdoptOnlyOneNewSettledPrimaryTask() {
        val startup = MirrorStartupPolicy("example.app", 0, 7, setOf(1))
        val task = AppTaskPolicy.Task(2, 3, 0, 0, "example.app/.Main", "example.app/.Main", true, true)
        assertNull(startup.observe(listOf(task), 0))
        assertEquals(task, startup.observe(listOf(task), 200_000_000))
        assertNull(startup.observe(listOf(task, task.copy(id = 3, display = 7)), 300_000_000))
        assertNull(startup.observe(listOf(task), 400_000_000))
        assertEquals(task, startup.observe(listOf(task), 600_000_000))
    }
    @Test fun primaryToOwnedMigrationMustSettleAgain() {
        val startup = MirrorStartupPolicy("example.app", 0, 7, emptySet())
        val task = AppTaskPolicy.Task(2, 3, 0, 0, "example.app/.Main", "example.app/.Main", true, true)
        assertNull(startup.observe(listOf(task), 0))
        assertNull(startup.observe(listOf(task.copy(display = 7)), 200_000_000))
        assertEquals(task.copy(display = 7), startup.observe(listOf(task.copy(display = 7)), 400_000_000))
    }
    @Test fun sourceGeometryKeepsPrimaryAspectIndependentOfFullAndCenter() {
        val profile = BusinessDisplayProfile(1920, 1080, 240)
        for (base in listOf(ContentViewport.full(), ContentViewport.center())) {
            val viewport = base.copy(sourceWidth = profile.width, sourceHeight = profile.height)
            val fit = SourceRenderMapping.create(viewport, false)
            val physicalAspect = if (base == ContentViewport.center())
                fit.content[2] * 1920 * .4f / (fit.content[3] * 720 * .6f)
                else fit.content[2] * 1920 / (fit.content[3] * 720)
            assertEquals(16f / 9, physicalAspect, .0001f)
            assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), fit.source, 0f)
            val mirror = MirrorGeometry.fit(profile.width, profile.height, 1920, 1080)
            assertEquals(0f, mirror.left, 0f); assertEquals(0f, mirror.top, 0f)
        }
    }
    @Test fun cropIsExplicitDoesNotChangePrimarySourceOrStretchText() {
        val source = ContentViewport.full().copy(sourceWidth = 1920, sourceHeight = 1080)
        val mapping = SourceRenderMapping.create(source, true)
        assertArrayEquals(source.normalized(), mapping.content, 0f)
        assertEquals(source.encodedWidth.toFloat()/source.encodedHeight,
            1920f * mapping.source[2] / (1080f * mapping.source[3]), .0001f)
        assertEquals(1920, source.sourceWidth); assertEquals(1080, source.sourceHeight)
    }
    @Test fun nativeMeterMappingKeepsExistingIdentityAspect() {
        for (viewport in listOf(ContentViewport.full(), ContentViewport.center())) {
            for (crop in listOf(false, true)) {
                val mapping = SourceRenderMapping.create(viewport, crop)
                assertArrayEquals(viewport.normalized(), mapping.content, .0001f)
                assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), mapping.source, .0001f)
            }
        }
    }
    @Test fun unreasonablePrimaryProfilesAreRejectedWithoutWmMutation() {
        assertTrue(BusinessDisplayProfile.valid(1920, 1080, 240))
        for (values in listOf(listOf(0, 1080, 240), listOf(1920, 2161, 240), listOf(4096, 2160, 240), listOf(1920, 1080, 0))) {
            assertThrows(IllegalArgumentException::class.java) { BusinessDisplayProfile(values[0], values[1], values[2]) }
        }
    }
    @Test fun missingOrDeniedAccessibilityHasNonCrashingFallback() {
        val attempted = mutableListOf<String>()
        assertEquals(SafeSettingsNavigator.Result.UNAVAILABLE, SafeSettingsNavigator.choose { attempted.add(it); false })
        assertEquals(2, attempted.size)
        var calls = 0
        assertEquals(SafeSettingsNavigator.Result.GENERAL, SafeSettingsNavigator.choose { ++calls == 2 })
        assertEquals(SafeSettingsNavigator.Result.ACCESSIBILITY, SafeSettingsNavigator.choose { true })
    }
}
