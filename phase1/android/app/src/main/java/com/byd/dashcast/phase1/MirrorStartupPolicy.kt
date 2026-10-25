package com.byd.dashcast.phase1

/** Wait for a forwarding launcher to settle BEFORE binding an exact task/root identity.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorStartupPolicy(private val packageName: String, private val user: Int,
    private val display: Int, before: Set<Int>) {
    private val before = before.toSet()
    private var last: Pair<Int, String>? = null
    private var since = 0L
    fun observe(tasks: List<AppTaskPolicy.Task>, now: Long): AppTaskPolicy.Task? {
        val candidates = tasks.filter { it.id >= 0 && it.id !in before && it.user == user &&
            it.display == display && it.root.substringBefore('/') == packageName &&
            it.top.substringBefore('/') == packageName }
        if (candidates.size != 1) { last = null; return null }
        val task = candidates.single()
        if (tasks.count { it.id == task.id } != 1) { last = null; return null }
        val identity = task.id to task.root
        if (last != identity) { last = identity; since = now; return null }
        return if (now >= since && now - since >= 200_000_000L) task else null
    }
}
