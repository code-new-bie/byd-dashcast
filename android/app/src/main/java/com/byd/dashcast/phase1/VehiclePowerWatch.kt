package com.byd.dashcast.phase1

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps [ShellVehiclePower] running through the authorized local ADB while started, and reports a confirmed
 * switch-off and the car coming back on. Only started after ADB was authorized in this process, so it never
 * brings up the debugging dialog by itself; failures are retried with a growing pause (at most 30 s).
 */
internal class VehiclePowerWatch(
    private val apk: String,
    private val log: (String) -> Unit,
    private val onOff: (VehiclePowerReading) -> Unit,
    private val onOn: (VehiclePowerReading) -> Unit,
    private val detector: VehiclePowerDetector = VehiclePowerDetector(),
) {
    private var thread: Thread? = null
    private var cancelled = AtomicBoolean()
    @Volatile var last: VehiclePowerReading? = null
        private set

    val running: Boolean @Synchronized get() = thread != null

    @Synchronized fun start() {
        if (thread != null) { return }
        val stop = AtomicBoolean().also { cancelled = it }
        thread = Thread({ run(stop) }, "dashcast-vehicle-power").apply { isDaemon = true; start() }
        log("VEHICLE_POWER_WATCH_START")
    }

    @Synchronized fun stop() {
        val old = thread ?: return
        cancelled.set(true)
        old.interrupt()
        thread = null
        last = null
        log("VEHICLE_POWER_WATCH_STOP")
    }

    /** Feeds one helper line; also used by tests. */
    internal fun accept(line: String, nowNanos: Long = System.nanoTime()) {
        if (line.startsWith(ShellVehiclePower.UNAVAILABLE)) { log("VEHICLE_POWER_UNAVAILABLE ${line.take(80)}"); return }
        val reading = VehiclePowerReading.parse(line) ?: return
        if (reading != last) { log("VEHICLE_POWER ${reading.describe()}") }
        last = reading
        when (detector.update(reading, nowNanos)) {
            VehiclePowerDetector.Event.OFF -> onOff(reading)
            VehiclePowerDetector.Event.ON -> onOn(reading)
            VehiclePowerDetector.Event.NONE -> {}
        }
    }

    private fun run(stop: AtomicBoolean) {
        var failures = 0
        while (!stop.get()) {
            try {
                // The helper prints at least every 2 s, so an 8 s read deadline only trips on a stuck session.
                AdbShellClient("127.0.0.1", DashcastPorts.LOCAL_ADB, 8000, AndroidAdbIdentity.load(), {}, stop)
                    .executeShellV2(ShellVehiclePower.command(apk), ShellLineSplitter { accept(it) })
                failures = 0
            } catch (error: Exception) {
                if (stop.get()) { break }
                failures++
                if (failures == 1 || failures % 10 == 0) {
                    log("VEHICLE_POWER_WATCH_FAILED count=$failures type=${error.javaClass.simpleName} " +
                        "${error.message?.take(120)}")
                }
            }
            if (failures > 0) {
                try { Thread.sleep(pause(failures)) } catch (interrupted: InterruptedException) { break }
            }
        }
    }

    companion object {
        fun pause(failures: Int): Long = minOf(30_000L, 1_000L shl minOf(failures - 1, 5))
    }
}
