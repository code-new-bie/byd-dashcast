package com.byd.dashcast.phase1

/** No product total-duration budget; timed engineering tools retain their separate finite profile.
 * @author Codex
 * @date 2026/10/21
 */
internal object ContinuousSessionPolicy {
    const val UNLIMITED_SECONDS = 0
    const val UINT32_MASK = 0xffffffffL

    @JvmStatic fun requireCaptureSeconds(seconds: Int) {
        if (seconds != UNLIMITED_SECONDS) { ProjectionLimits.requireSeconds(seconds) }
    }

    @JvmStatic fun unlimited(seconds: Int): Boolean = seconds == UNLIMITED_SECONDS

    @JvmStatic fun timedOut(seconds: Int, began: Long, now: Long): Boolean =
        seconds != UNLIMITED_SECONDS && now - began >= seconds * 1_000_000_000L

    /** Statistics saturate; they never decide whether an otherwise healthy stream can continue. */
    @JvmStatic fun add(total: Long, amount: Long): Long {
        require(total >= 0 && amount >= 0) { "NONNEGATIVE_STREAM_COUNTER_REQUIRED" }
        return if (total > Long.MAX_VALUE - amount) Long.MAX_VALUE else total + amount
    }

    @JvmStatic fun rateX100(frames: Long, first: Long, last: Long, unitsPerSecond: Long): Long {
        if (frames < 2 || last <= first) { return 0 }
        return ((frames - 1).toDouble() * unitsPerSecond.toDouble() * 100.0 / (last - first).toDouble()).toLong()
    }
}

/** A wire serial is modular uint32, not a count budget. No reset of transport or decoder is needed. */
internal class StreamSerials @JvmOverloads constructor(
    private var frameBits: Int = 0,
    private var heartbeatBits: Int = 0
) {
    fun nextFrame(): Int = frameBits + 1
    fun acceptFrame(sequence: Int) { check(sequence == nextFrame()); frameBits = sequence }
    fun nextHeartbeat(): Int { heartbeatBits += 1; return heartbeatBits }
    fun frameBits(): Int = frameBits
}
