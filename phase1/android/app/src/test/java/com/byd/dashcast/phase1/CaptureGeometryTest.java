package com.byd.dashcast.phase1;

import org.junit.Test;

import static org.junit.Assert.*;

/** Pixel geometry tests; they do not execute SurfaceFlinger or QNX Screen.
 * @author Codex
 * @date 2026/10/14
 */
public final class CaptureGeometryTest {
    @Test
    public void fullHdFitPreservesTheEntireSourceAndDoesNotStretchToEightToThree() {
        CaptureGeometry geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.FIT));
        assertBounds(geometry.source(), 0, 0, 1920, 1080);
        assertBounds(geometry.destination(), 107, 0, 533, 240);
    }

    @Test
    public void fullHdCenterCropUsesThe720LineMiddleWithoutChangingTheEncoderSize() {
        CaptureGeometry geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.CROP));
        assertBounds(geometry.source(), 0, 180, 1920, 900);
        assertBounds(geometry.destination(), 0, 0, 640, 240);
    }

    @Test
    public void customRegionIsRelativeToTheDetectedDisplayNotHardcodedFullHd() {
        CaptureGeometry geometry = CaptureGeometry.create(1280, 720, CaptureOptions.region(25, 10, 100, 90));
        assertBounds(geometry.source(), 320, 72, 1280, 648);
        assertBounds(geometry.destination(), 120, 0, 520, 240);
    }

    @Test
    public void portraitAndUltrawideFitAndCropStayInsideTheirOwnBounds() {
        CaptureGeometry portrait = CaptureGeometry.create(1080, 1920, CaptureOptions.of(CaptureOptions.Mode.FIT));
        assertBounds(portrait.destination(), 252, 0, 387, 240);
        CaptureGeometry wide = CaptureGeometry.create(3840, 720, CaptureOptions.of(CaptureOptions.Mode.CROP));
        assertBounds(wide.source(), 960, 0, 2880, 720);
    }

    @Test
    public void alreadyEightToThreeHasNoLetterboxingOrCrop() {
        for (CaptureOptions.Mode mode : new CaptureOptions.Mode[] {CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP}) {
            CaptureGeometry geometry = CaptureGeometry.create(1920, 720, CaptureOptions.of(mode));
            assertBounds(geometry.source(), 0, 0, 1920, 720);
            assertBounds(geometry.destination(), 0, 0, 640, 240);
        }
    }

    @Test
    public void stretchIsByteGeometryCompatibleWithTheObservedOldMirror() {
        CaptureGeometry geometry = CaptureGeometry.create(1920, 1080, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
        assertBounds(geometry.source(), 0, 0, 1920, 1080);
        assertBounds(geometry.destination(), 0, 0, 640, 240);
    }

    @Test
    public void rejectInvalidDisplayAndZeroPixelRegion() {
        for (int[] size : new int[][] {{0, 720}, {1920, 0}, {1, 1}, {-1, 240}, {4097, 720},
                {640, Integer.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CaptureGeometry.create(size[0], size[1], CaptureOptions.of(CaptureOptions.Mode.FIT)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CaptureGeometry.create(2, 2, CaptureOptions.region(0, 0, 5, 5)));
    }

    @Test
    public void manyLegalSizesNeverEscapeSourceOrEncoderSurface() {
        for (int width : new int[] {2, 135, 640, 1080, 1921, 3840, 4096}) {
            for (int height : new int[] {2, 240, 721, 1080, 1920, 4096}) {
                for (CaptureOptions.Mode mode : new CaptureOptions.Mode[] {
                        CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP, CaptureOptions.Mode.STRETCH}) {
                    CaptureGeometry geometry = CaptureGeometry.create(width, height, CaptureOptions.of(mode));
                    assertInside(geometry.source(), width, height);
                    assertInside(geometry.destination(), 640, 240);
                }
            }
        }
    }

    private static void assertInside(CaptureGeometry.Bounds bounds, int width, int height) {
        assertTrue(bounds.left() >= 0 && bounds.top() >= 0);
        assertTrue(bounds.right() <= width && bounds.bottom() <= height);
        assertTrue(bounds.width() > 0 && bounds.height() > 0);
    }

    private static void assertBounds(CaptureGeometry.Bounds bounds, int left, int top, int right, int bottom) {
        assertEquals(left, bounds.left());
        assertEquals(top, bounds.top());
        assertEquals(right, bounds.right());
        assertEquals(bottom, bounds.bottom());
    }
}
