package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Non-interactive invalid-input and immutable-configuration tests.
 * @author Codex
 * @date 2026/10/14
 */
class CaptureOptionsTest {
    @Test
    fun knownModesRoundTripWithoutWindowParameters() {
        for (mode in arrayOf(CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP, CaptureOptions.Mode.STRETCH)) {
            val value = CaptureOptions.of(mode)
            assertEquals(mode, CaptureOptions.parse(value.token()).mode())
            assertFalse(value.shellArguments().contains("1920"))
        }
    }

    @Test
    fun customRegionRoundTripsCanonicalUnsignedPercentages() {
        val value = CaptureOptions.parse("region:10:15:95:90")
        assertEquals(CaptureOptions.Mode.REGION, value.mode())
        assertEquals("region:10:15:95:90", value.token())
        assertEquals("region 10 15 95 90", value.shellArguments())
        assertEquals(10, value.leftPercent())
        assertEquals(90, value.bottomPercent())
    }

    @Test
    fun rejectShellInjectionUnicodeSignsUnknownTokensAndOversize() {
        for (token in arrayOf(null, "", "fit;reboot", "fit\n", "fit ", "FIT", "region",
            "region:0:0:100:100:", "region:0:0:100:99;id", "region:0:0:100:99\n",
            "region:+1:0:100:100", "region:-1:0:100:100", "region:１:0:100:100",
            "region:0000:0:100:100", "region:0:0:100:99999999999999999999999")) {
            assertThrows(token, IllegalArgumentException::class.java) { CaptureOptions.parse(token) }
        }
    }

    @Test
    fun rejectEmptyInvertedTooSmallAndOutsideRegionsBeforeArithmetic() {
        for (bounds in arrayOf(intArrayOf(0, 0, 0, 100), intArrayOf(0, 0, 100, 4), intArrayOf(90, 0, 20, 100),
            intArrayOf(-1, 0, 100, 100), intArrayOf(0, -1, 100, 100), intArrayOf(0, 0, 101, 100),
            intArrayOf(0, 0, 100, 101), intArrayOf(101, 0, 100, 100), intArrayOf(0, 101, 100, 100),
            intArrayOf(Int.MAX_VALUE, 0, Int.MIN_VALUE + 10, 100),
            intArrayOf(0, Int.MAX_VALUE, 100, Int.MIN_VALUE + 10))) {
            assertThrows(IllegalArgumentException::class.java) {
                CaptureOptions.region(bounds[0], bounds[1], bounds[2], bounds[3])
            }
        }
        assertEquals("region:95:95:100:100", CaptureOptions.region(95, 95, 100, 100).token())
    }

    @Test
    fun requireExplicitRegionAndNonNullMode() {
        assertThrows(IllegalArgumentException::class.java) { CaptureOptions.of(CaptureOptions.Mode.REGION) }
        assertThrows(NullPointerException::class.java) { CaptureOptions.of(null) }
    }

    @Test
    fun legacyShellInvocationsKeepTheExactStretchMode() {
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(arrayOf("5")).mode())
        assertEquals(CaptureOptions.Mode.STRETCH,
            CaptureOptions.fromShellArguments(arrayOf("600", "live")).mode())
    }

    @Test
    fun shellGeometrySuffixIsStrictAndRoundTrips() {
        assertEquals("fit", CaptureOptions.fromShellArguments(arrayOf("600", "live", "fit")).token())
        assertEquals("crop", CaptureOptions.fromShellArguments(arrayOf("600", "live", "crop")).token())
        assertEquals("region:10:15:95:90", CaptureOptions.fromShellArguments(
            arrayOf("600", "live", "region", "10", "15", "95", "90")).token())
        assertEquals("region:10:15:95:90", CaptureOptions.fromShellArguments(
            arrayOf("600", "live", "remote", "com.example.maps/com.example.MainActivity", "region",
                "10", "15", "95", "90")).token())
        for (args in arrayOf(arrayOf(), arrayOf("600", "other"), arrayOf("5", "fit"),
            arrayOf("600", "live", "region", "0", "0", "100"), arrayOf("600", "live", "fit", "other"),
            arrayOf("600", "live", "region", "0", "0", "100", "100", "other"))) {
            assertThrows(IllegalArgumentException::class.java) { CaptureOptions.fromShellArguments(args) }
        }
    }

    @Test
    fun acceptedSnapshotCannotBeChangedByLaterSelections() {
        val accepted = CaptureOptions.region(10, 15, 95, 90)
        val later = CaptureOptions.of(CaptureOptions.Mode.FIT)
        assertEquals("region:10:15:95:90", accepted.token())
        assertEquals("fit", later.token())
    }
}
