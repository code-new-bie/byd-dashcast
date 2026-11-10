package com.byd.dashcast.phase1

import android.content.Context
import android.os.Looper
import android.os.Process
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Reads the vehicle power level and gear for the app, as the shell user, and prints them as
 * [VehiclePowerReading] lines: at once on a change, then every 2 s (every 0.5 s while the level reads OFF, so the
 * app can confirm a switch-off quickly). Read-only: nothing is set on the vehicle.
 *
 * A BYD head unit kills every third-party app about ten seconds after the car is switched off, so the app watches
 * this to end a projection while it still can. The SDK device's getInstance() checks a BYDAUTO permission the shell
 * lacks; the constructor skips that check and the vehicle service accepts the shell user.
 * Ends when stdin closes (the app went away), after [LIFETIME_MS] (the app starts it again), or on a failure.
 */
internal object ShellVehiclePower {
    const val UNAVAILABLE = "DASHCAST_POWER_UNAVAILABLE"
    const val LIFETIME_MS = 10 * 60_000L
    private const val POLL_MS = 300L
    private const val REPEAT_MS = 2_000L
    private const val REPEAT_OFF_MS = 500L

    internal fun command(apk: String): String =
        // Reuse the installed-path validation/quoting; only replace its fixed main entry point.
        ShellCaptureCommand.create(apk, 1).substringBefore(" com.byd.dashcast.phase1.ShellScreenCapture ") +
            " com.byd.dashcast.phase1.ShellVehiclePower"

    private class Device(name: String, context: Context) {
        private val type = Class.forName("android.hardware.bydauto.$name")
        private val instance: Any = try {
            type.getMethod("getInstance", Context::class.java).invoke(null, context)
                ?: throw IllegalStateException("no instance")
        } catch (error: Exception) {
            if (error !is InvocationTargetException && error !is IllegalStateException) { throw error }
            type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }

        fun read(method: String): Int? = try { (type.getMethod(method).invoke(instance) as Number).toInt() }
            catch (error: Exception) { null }
    }

    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 2000) { "shell uid required" }
            if (args.isNotEmpty()) { throw IllegalArgumentException("no arguments") }
            if (Looper.myLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
            val context = RemoteDisplaySession.shellContext()
            val body = Device("bodywork.BYDAutoBodyworkDevice", context)
            val gearbox = try { Device("gearbox.BYDAutoGearboxDevice", context) } catch (error: Exception) { null }
            // The app holds stdin open; EOF means it is gone, so stop at once.
            Thread({
                try { while (System.`in`.read() >= 0) { } } catch (ignored: Exception) { }
                exitProcess(0)
            }, "dashcast-power-stdin").apply { isDaemon = true; start() }
            val started = System.nanoTime()
            var last: String? = null
            var printed = 0L
            while ((System.nanoTime() - started) / 1_000_000L < LIFETIME_MS) {
                val level = body.read("getPowerLevel")
                val line = VehiclePowerReading.format(level, gearbox?.read("getGearboxAutoModeType"))
                val now = System.nanoTime()
                val repeat = if (level == VehiclePowerDetector.LEVEL_OFF) REPEAT_OFF_MS else REPEAT_MS
                if (line != last || (now - printed) / 1_000_000L >= repeat) {
                    println(line)
                    System.out.flush()
                    if (System.out.checkError()) { break }
                    last = line
                    printed = now
                }
                Thread.sleep(POLL_MS)
            }
            exitProcess(0)
        } catch (error: Throwable) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            println("$UNAVAILABLE ${cause.javaClass.simpleName}")
            System.out.flush()
            exitProcess(3)
        }
    }
}
