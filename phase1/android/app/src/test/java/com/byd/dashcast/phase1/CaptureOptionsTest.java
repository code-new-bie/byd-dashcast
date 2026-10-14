package com.byd.dashcast.phase1;

import org.junit.Test;

import static org.junit.Assert.*;

/** Non-interactive invalid-input and immutable-configuration tests.
 * @author Codex
 * @date 2026/10/14
 */
public final class CaptureOptionsTest {
    @Test
    public void knownModesRoundTripWithoutWindowParameters() {
        for (CaptureOptions.Mode mode : new CaptureOptions.Mode[] {
                CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP, CaptureOptions.Mode.STRETCH}) {
            CaptureOptions value = CaptureOptions.of(mode);
            assertEquals(mode, CaptureOptions.parse(value.token()).mode());
            assertFalse(value.shellArguments().contains("1920"));
        }
    }

    @Test
    public void customRegionRoundTripsCanonicalUnsignedPercentages() {
        CaptureOptions value = CaptureOptions.parse("region:10:15:95:90");
        assertEquals(CaptureOptions.Mode.REGION, value.mode());
        assertEquals("region:10:15:95:90", value.token());
        assertEquals("region 10 15 95 90", value.shellArguments());
        assertEquals(10, value.leftPercent());
        assertEquals(90, value.bottomPercent());
    }

    @Test
    public void rejectShellInjectionUnicodeSignsUnknownTokensAndOversize() {
        for (String token : new String[] {null, "", "fit;reboot", "fit\n", "fit ", "FIT", "region",
                "region:0:0:100:100:", "region:0:0:100:99;id", "region:0:0:100:99\n",
                "region:+1:0:100:100", "region:-1:0:100:100", "region:１:0:100:100",
                "region:0000:0:100:100", "region:0:0:100:99999999999999999999999"}) {
            assertThrows(token, IllegalArgumentException.class, () -> CaptureOptions.parse(token));
        }
    }

    @Test
    public void rejectEmptyInvertedTooSmallAndOutsideRegionsBeforeArithmetic() {
        for (int[] bounds : new int[][] {{0, 0, 0, 100}, {0, 0, 100, 4}, {90, 0, 20, 100},
                {-1, 0, 100, 100}, {0, -1, 100, 100}, {0, 0, 101, 100}, {0, 0, 100, 101},
                {101, 0, 100, 100}, {0, 101, 100, 100}, {Integer.MAX_VALUE, 0, Integer.MIN_VALUE + 10, 100},
                {0, Integer.MAX_VALUE, 100, Integer.MIN_VALUE + 10}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CaptureOptions.region(bounds[0], bounds[1], bounds[2], bounds[3]));
        }
        assertEquals("region:95:95:100:100", CaptureOptions.region(95, 95, 100, 100).token());
    }

    @Test
    public void requireExplicitRegionAndNonNullMode() {
        assertThrows(IllegalArgumentException.class, () -> CaptureOptions.of(CaptureOptions.Mode.REGION));
        assertThrows(NullPointerException.class, () -> CaptureOptions.of(null));
    }

    @Test
    public void legacyShellInvocationsKeepTheExactStretchMode() {
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(new String[] {"5"}).mode());
        assertEquals(CaptureOptions.Mode.STRETCH,
                CaptureOptions.fromShellArguments(new String[] {"600", "live"}).mode());
    }

    @Test
    public void shellGeometrySuffixIsStrictAndRoundTrips() {
        assertEquals("fit", CaptureOptions.fromShellArguments(new String[] {"600", "live", "fit"}).token());
        assertEquals("crop", CaptureOptions.fromShellArguments(new String[] {"600", "live", "crop"}).token());
        assertEquals("region:10:15:95:90", CaptureOptions.fromShellArguments(
                new String[] {"600", "live", "region", "10", "15", "95", "90"}).token());
        assertEquals("region:10:15:95:90", CaptureOptions.fromShellArguments(
                new String[] {"600", "live", "remote", "com.example.maps/com.example.MainActivity", "region",
                        "10", "15", "95", "90"}).token());
        for (String[] args : new String[][] {{}, {"600", "other"}, {"5", "fit"},
                {"600", "live", "region", "0", "0", "100"}, {"600", "live", "fit", "other"},
                {"600", "live", "region", "0", "0", "100", "100", "other"}}) {
            assertThrows(IllegalArgumentException.class, () -> CaptureOptions.fromShellArguments(args));
        }
    }

    @Test
    public void acceptedSnapshotCannotBeChangedByLaterSelections() {
        CaptureOptions accepted = CaptureOptions.region(10, 15, 95, 90);
        CaptureOptions later = CaptureOptions.of(CaptureOptions.Mode.FIT);
        assertEquals("region:10:15:95:90", accepted.token());
        assertEquals("fit", later.token());
    }
}
