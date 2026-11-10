package com.byd.dashcast.phase1

/** Real GL submissions, not duplicated encoded pictures or a synthetic 30fps PTS timeline.
 * Static owned UI still needs input buffers: OEM codecs need not implement repeat-previous-frame.
 * @author Codex
 * @date 2026/10/25
 */
internal class FrameSubmissionClock(private val intervalNanos: Long = 33_333_334L) {
    private var last: Long? = null
    private var deadline: Long? = null
    init { require(intervalNanos > 0) }
    fun due(now: Long, hasSource: Boolean): Boolean {
        if (!hasSource) { return false }
        val previous = last
        return previous == null || now > previous && now >= checkNotNull(deadline)
    }
    fun submitted(now: Long) {
        check(due(now, true)) { "GL_SUBMISSION_CLOCK_NOT_DUE" }
        val due = deadline
        deadline = if (due == null) Math.addExact(now, intervalNanos) else
            Math.addExact(due, Math.multiplyExact((now - due) / intervalNanos + 1, intervalNanos))
        last = now
    }
    fun delayNanos(now: Long): Long {
        val previous = last ?: return intervalNanos
        return if (now < previous) intervalNanos else maxOf(0L, checkNotNull(deadline)-now)
    }
    companion object {
        fun sourceReady(timestamp: Long, epoch: Long): Boolean = timestamp >= 0 && epoch >= 0 && timestamp >= epoch
        fun validProducer(timestamp: Long, previous: Long, now: Long): Boolean = timestamp >= 0 &&
            timestamp >= previous && now >= 0 && (timestamp <= now || timestamp-now <= 1_000_000_000L)
    }
}
