package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream

/** One sample of the vehicle power level and gear, as [ShellVehiclePower] prints it.
 *
 * The level is BYDAutoBodyworkDevice.getPowerLevel(): OFF 0, ACC 1, ON 2, OK 3, FAKE_OK 4 (255 and anything else
 * is not a level). The gear is BYDAutoGearboxDevice.getGearboxAutoModeType(): P 1, R/N/D/M/S 2..6.
 */
internal data class VehiclePowerReading(val level: Int?, val gear: Int?) {
    fun describe(): String = "level=${level ?: "-"} gear=${gear ?: "-"}"

    companion object {
        const val PREFIX = "DASHCAST_POWER"

        fun format(level: Int?, gear: Int?): String = "$PREFIX ${level ?: "-"} ${gear ?: "-"}"

        fun parse(line: String): VehiclePowerReading? {
            val fields = line.trim().split(' ')
            if (fields.size != 3 || fields[0] != PREFIX) { return null }
            fun value(text: String): Int? = if (text == "-") null else text.toIntOrNull()?.takeIf { it in 0..65535 }
            if (fields[1] != "-" && value(fields[1]) == null || fields[2] != "-" && value(fields[2]) == null) { return null }
            return VehiclePowerReading(value(fields[1]), value(fields[2]))
        }
    }
}

/**
 * Decides when the car was switched off and when it came back on.
 *
 * OFF fires once, when the level reads OFF for [confirmNanos] after the car was seen on, and the gear is not a
 * driving gear (an unknown gear does not hold it back). A head unit that starts while the car is already off never
 * fires. ON fires once when a valid non-OFF level is read again after OFF fired.
 */
internal class VehiclePowerDetector(private val confirmNanos: Long = CONFIRM_NANOS) {
    enum class Event { NONE, OFF, ON }

    private var wasOn = false
    private var offSince: Long? = null
    private var fired = false

    @Synchronized
    fun update(reading: VehiclePowerReading, nowNanos: Long): Event {
        val level = reading.level?.takeIf { it in LEVEL_OFF..LEVEL_FAKE_OK } ?: return Event.NONE
        if (level != LEVEL_OFF) {
            val back = fired
            wasOn = true
            offSince = null
            fired = false
            return if (back) Event.ON else Event.NONE
        }
        if (!wasOn || fired) { return Event.NONE }
        val since = offSince ?: nowNanos.also { offSince = it }
        if (nowNanos - since < confirmNanos || reading.gear in DRIVING_GEARS) { return Event.NONE }
        fired = true
        return Event.OFF
    }

    /** True between a fired OFF and the next ON. */
    @get:Synchronized val off: Boolean get() = fired

    companion object {
        const val CONFIRM_NANOS = 1_000_000_000L
        const val LEVEL_OFF = 0
        const val LEVEL_FAKE_OK = 4
        private val DRIVING_GEARS = 2..6
    }
}

/** Splits shell stdout chunks into lines; a line longer than [limit] bytes is dropped, never buffered without bound. */
internal class ShellLineSplitter(private val limit: Int = 256, private val line: (String) -> Unit) :
    AdbShellClient.ShellOutputHandler {
    private val current = ByteArrayOutputStream()
    private var overflow = false

    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) { throw IllegalArgumentException("invalid shell output chunk") }
        for (index in 0 until length) {
            val byte = data[index]
            if (byte == '\n'.code.toByte()) {
                if (!overflow) { line(String(current.toByteArray(), Charsets.UTF_8).trimEnd('\r')) }
                current.reset()
                overflow = false
            } else if (current.size() < limit) {
                current.write(byte.toInt())
            } else {
                overflow = true
            }
        }
    }
}
