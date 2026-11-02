package com.byd.dashcast.phase1

/** One shared polling budget for encoder input and output, independent of encoded FPS. */
internal class TaskPollSchedule(private val interval: Long = 100_000_000L) {
    private var last: Long? = null
    var queries: Long = 0
        private set

    init { require(interval > 0) }

    fun due(now: Long): Boolean {
        val previous = last
        if (previous != null && now >= previous && now - previous < interval) { return false }
        last = now
        if (queries < Long.MAX_VALUE) { queries++ }
        return true
    }
}
