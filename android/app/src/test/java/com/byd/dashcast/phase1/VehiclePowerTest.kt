package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switch-off detection from the shell helper's power lines; no vehicle, ADB or Android runtime. */
class VehiclePowerTest {
    private val second = 1_000_000_000L
    private fun reading(level: Int?, gear: Int? = 1) = VehiclePowerReading(level, gear)

    @Test
    fun linesRoundTripAndBadLinesAreIgnored() {
        assertEquals(reading(2, 1), VehiclePowerReading.parse(VehiclePowerReading.format(2, 1)))
        assertEquals(reading(null, null), VehiclePowerReading.parse("DASHCAST_POWER - -"))
        assertEquals(reading(0, null), VehiclePowerReading.parse("DASHCAST_POWER 0 -\r"))
        for (line in listOf("", "DASHCAST_POWER", "DASHCAST_POWER 1", "DASHCAST_POWER 1 2 3", "OTHER 1 2",
                "DASHCAST_POWER x 1", "DASHCAST_POWER -1 1", "DASHCAST_POWER 1 99999")) {
            assertNull(line, VehiclePowerReading.parse(line))
        }
    }

    @Test
    fun switchOffFiresOnceAfterOneSecondOfOff() {
        val detector = VehiclePowerDetector()
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(2), 0))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 1 * second))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 1 * second + second / 2))
        assertEquals(VehiclePowerDetector.Event.OFF, detector.update(reading(0), 2 * second))
        assertTrue(detector.off)
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 3 * second))
    }

    @Test
    fun aHeadUnitStartedWhileOffNeverFires() {
        val detector = VehiclePowerDetector()
        repeat(10) { assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), it * second)) }
        assertFalse(detector.off)
    }

    @Test
    fun aShortDropDoesNotFireAndRestartsTheConfirmation() {
        val detector = VehiclePowerDetector()
        detector.update(reading(3), 0)
        detector.update(reading(0), 1 * second)
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(1), 1 * second + second / 2))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 2 * second))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 2 * second + second / 2))
        assertEquals(VehiclePowerDetector.Event.OFF, detector.update(reading(0), 3 * second))
    }

    @Test
    fun aDrivingGearHoldsItBackAndAnUnknownGearDoesNot() {
        val driving = VehiclePowerDetector()
        driving.update(reading(2, 4), 0)
        assertEquals(VehiclePowerDetector.Event.NONE, driving.update(reading(0, 4), 1 * second))
        assertEquals(VehiclePowerDetector.Event.NONE, driving.update(reading(0, 4), 5 * second))
        assertEquals(VehiclePowerDetector.Event.OFF, driving.update(reading(0, 1), 6 * second))
        val unknown = VehiclePowerDetector()
        unknown.update(reading(2, null), 0)
        unknown.update(reading(0, null), 1 * second)
        assertEquals(VehiclePowerDetector.Event.OFF, unknown.update(reading(0, null), 2 * second))
    }

    @Test
    fun invalidLevelsAreIgnoredAndOnFiresAfterOff() {
        val detector = VehiclePowerDetector()
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(255), 0))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(null), 0))
        detector.update(reading(255), 0)
        detector.update(reading(0), second)
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(0), 3 * second))
        detector.update(reading(2), 4 * second)
        detector.update(reading(0), 5 * second)
        assertEquals(VehiclePowerDetector.Event.OFF, detector.update(reading(0), 6 * second))
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(255), 7 * second))
        assertEquals(VehiclePowerDetector.Event.ON, detector.update(reading(1), 8 * second))
        assertFalse(detector.off)
        assertEquals(VehiclePowerDetector.Event.NONE, detector.update(reading(2), 9 * second))
    }

    @Test
    fun watchReportsEachChangeOnceAndCallsBack() {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val watch = VehiclePowerWatch("/data/app/x/base.apk", { logs += it }, { events += "off ${it.level}" },
            { events += "on ${it.level}" })
        watch.accept("DASHCAST_POWER 2 1", 0)
        watch.accept("DASHCAST_POWER 2 1", second / 2)
        watch.accept("DASHCAST_POWER 0 1", second)
        watch.accept("DASHCAST_POWER 0 1", 2 * second)
        watch.accept("DASHCAST_POWER 2 1", 3 * second)
        watch.accept("DASHCAST_POWER_UNAVAILABLE ClassNotFoundException", 4 * second)
        watch.accept("noise", 4 * second)
        assertEquals(listOf("off 0", "on 2"), events)
        assertEquals(listOf("VEHICLE_POWER level=2 gear=1", "VEHICLE_POWER level=0 gear=1", "VEHICLE_POWER level=2 gear=1",
            "VEHICLE_POWER_UNAVAILABLE DASHCAST_POWER_UNAVAILABLE ClassNotFoundException"), logs)
        assertEquals(reading(2, 1), watch.last)
    }

    @Test
    fun linesAreSplitAcrossChunksAndOverlongLinesDropped() {
        val lines = mutableListOf<String>()
        val splitter = ShellLineSplitter(32) { lines += it }
        val first = "DASHCAST_POWER 2 1\r\nab".toByteArray()
        splitter.onOutput(first, 9)
        splitter.onOutput(first.copyOfRange(9, first.size), first.size - 9)
        val rest = "c\n${"x".repeat(40)}\nok\n".toByteArray()
        splitter.onOutput(rest, rest.size)
        assertEquals(listOf("DASHCAST_POWER 2 1", "abc", "ok"), lines)
        assertThrows(IllegalArgumentException::class.java) { splitter.onOutput(ByteArray(2), 3) }
    }

    @Test
    fun retriesBackOffToThirtySeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { VehiclePowerWatch.pause(it) })
    }

    @Test
    fun helperCommandUsesTheInstalledApkAndTheFixedEntryPoint() {
        val command = ShellVehiclePower.command("/data/app/com.byd.dashcast.phase1.debug-1/base.apk")
        assertEquals("CLASSPATH='/data/app/com.byd.dashcast.phase1.debug-1/base.apk' /system/bin/app_process " +
            "/system/bin ${ShellVehiclePower::class.java.name}", command)
        assertThrows(IllegalArgumentException::class.java) { ShellVehiclePower.command("/sdcard/base.apk") }
        assertThrows(IllegalArgumentException::class.java) { ShellVehiclePower.command("/data/app/a\nb.apk") }
    }
}
