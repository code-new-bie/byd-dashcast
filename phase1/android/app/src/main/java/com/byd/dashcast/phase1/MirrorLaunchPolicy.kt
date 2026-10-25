package com.byd.dashcast.phase1

/** Recognizes only fresh, same-user MAIN/LAUNCHER starts of the selected package.
 * No Intent data, labels or unrelated log lines are retained.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorLaunchPolicy(private val packageName: String, private val user: Int,
    private val ownerUid: Int, private val targetUid: Int, private val startNanos: Long) {
    private val clock = Regex("^\\s*(\\d{1,10})\\.(\\d{3,9})\\s")
    private val caller = Regex("from uid (\\d{1,10})(?:\\s|$)")
    private val start = Regex("\\bSTART u(\\d{1,6}) \\{")
    private val component = Regex("(?:\\s|\\{)cmp=([^\\s}]+)")
    private val categories = Regex("(?:\\s|\\{)cat=\\[([^]]{0,2048})]")
    private var lastTimestamp = -1L

    @Synchronized fun accept(line: String, nowNanos: Long): Boolean {
        if (line.length > 8192 || !line.contains("act=android.intent.action.MAIN ") ||
            categories.find(line)?.groupValues?.get(1)?.split(',', ' ')
                ?.contains("android.intent.category.LAUNCHER") != true) { return false }
        return try {
            val target = component.find(line)?.groupValues?.get(1) ?: return false
            if (!target.startsWith("$packageName/") || target.substringAfter('/').isBlank() ||
                start.find(line)?.groupValues?.get(1)?.toInt() != user) { return false }
            val uid = caller.find(line)?.groupValues?.get(1)?.toInt() ?: return false
            if (uid == 2000 || uid == ownerUid || uid == targetUid || uid < 0) { return false }
            val stamp = clock.find(line)?.groupValues ?: return false
            val time = stamp[1].toLong() * 1_000_000_000L + stamp[2].padEnd(9, '0').toLong()
            if (time < startNanos || time > nowNanos + 1_000_000_000L || time <= lastTimestamp) { return false }
            lastTimestamp = time
            true
        } catch (invalid: NumberFormatException) { false }
    }
}
