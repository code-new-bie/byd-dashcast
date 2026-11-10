package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel geometry tests; they do not execute SurfaceFlinger or QNX Screen.
 * @author Codex
 * @date 2026/10/14
 */
class CaptureGeometryTest {
    @Test
    fun fullHdFitPreservesTheEntireSourceAndDoesNotStretchToEightToThree() {
        val geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.FIT))
        assertBounds(geometry.source(), 0, 0, 1920, 1080)
        assertBounds(geometry.destination(), 320, 0, 1600, 720)
    }

    @Test
    fun fullHdCenterCropUsesThe720LineMiddleWithoutChangingTheEncoderSize() {
        val geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.CROP))
        assertBounds(geometry.source(), 0, 180, 1920, 900)
        assertBounds(geometry.destination(), 0, 0, 1920, 720)
    }

    @Test
    fun customRegionIsRelativeToTheDetectedDisplayNotHardcodedFullHd() {
        val geometry = CaptureGeometry.create(1280, 720, CaptureOptions.region(25, 10, 100, 90))
        assertBounds(geometry.source(), 320, 72, 1280, 648)
        assertBounds(geometry.destination(), 360, 0, 1560, 720)
    }

    @Test
    fun portraitAndUltrawideFitAndCropStayInsideTheirOwnBounds() {
        val portrait = CaptureGeometry.create(1080, 1920, CaptureOptions.of(CaptureOptions.Mode.FIT))
        assertBounds(portrait.destination(), 757, 0, 1162, 720)
        val wide = CaptureGeometry.create(3840, 720, CaptureOptions.of(CaptureOptions.Mode.CROP))
        assertBounds(wide.source(), 960, 0, 2880, 720)
    }

    @Test
    fun alreadyEightToThreeHasNoLetterboxingOrCrop() {
        for (mode in arrayOf(CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP)) {
            val geometry = CaptureGeometry.create(1920, 720, CaptureOptions.of(mode))
            assertBounds(geometry.source(), 0, 0, 1920, 720)
            assertBounds(geometry.destination(), 0, 0, 1920, 720)
        }
    }

    @Test
    fun stretchIsByteGeometryCompatibleWithTheObservedOldMirror() {
        val geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.STRETCH))
        assertBounds(geometry.source(), 0, 0, 1920, 1080)
        assertBounds(geometry.destination(), 0, 0, 1920, 720)
    }

    @Test
    fun rejectInvalidDisplayAndZeroPixelRegion() {
        for (size in arrayOf(intArrayOf(0, 720), intArrayOf(1920, 0), intArrayOf(1, 1), intArrayOf(-1, 240),
            intArrayOf(4097, 720), intArrayOf(640, Int.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) {
                CaptureGeometry.create(size[0], size[1], CaptureOptions.of(CaptureOptions.Mode.FIT))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureGeometry.create(2, 2, CaptureOptions.region(0, 0, 5, 5))
        }
    }

    @Test
    fun manyLegalSizesNeverEscapeSourceOrEncoderSurface() {
        for (width in intArrayOf(2, 135, 640, 1080, 1921, 3840, 4096)) {
            for (height in intArrayOf(2, 240, 721, 1080, 1920, 4096)) {
                for (mode in arrayOf(CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP, CaptureOptions.Mode.STRETCH)) {
                    val geometry = CaptureGeometry.create(width, height, CaptureOptions.of(mode))
                    assertInside(geometry.source(), width, height)
                    assertInside(geometry.destination(), 1920, 720)
                }
            }
        }
    }

    private companion object {
        fun assertInside(bounds: CaptureGeometry.Bounds, width: Int, height: Int) {
            assertTrue(bounds.left() >= 0 && bounds.top() >= 0)
            assertTrue(bounds.right() <= width && bounds.bottom() <= height)
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
        }

        fun assertBounds(bounds: CaptureGeometry.Bounds, left: Int, top: Int, right: Int, bottom: Int) {
            assertEquals(left, bounds.left())
            assertEquals(top, bounds.top())
            assertEquals(right, bounds.right())
            assertEquals(bottom, bounds.bottom())
        }
    }
}
