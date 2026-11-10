package com.byd.dashcast.phase1

import java.io.IOException

/** Ends the shell capture helper left behind by a session whose helper exit was never confirmed.
 *
 * Only the capture helper class started by [ShellCaptureCommand] and the processes it started are touched; the
 * standby-display and USB-grant helpers, the cast application and every non-shell process are left alone. A
 * killed helper takes its SurfaceFlinger capture display and its virtual display with it (both die with the
 * process), and Android moves a mirrored task on a removed display back to the main screen.
 */
internal object LeftoverCapture {
    const val HELPER = "com.byd.dashcast.phase1.ShellScreenCapture"
    const val LIST = "ps -A -o PID,PPID,USER,ARGS"

    data class Proc(val pid: Int, val ppid: Int, val user: String, val args: String)

    fun parse(listing: String): List<Proc> = listing.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val ppid = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        Proc(pid, ppid, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" })
    }.toList()

    private fun helper(proc: Proc) = proc.user == "shell" && proc.args.split(' ').let { HELPER in it && "app_process" in it.first() }

    /** Shell-owned capture helpers and everything below them, children before parents. */
    fun targets(procs: List<Proc>): List<Proc> {
        val byParent = procs.groupBy { it.ppid }
        val ordered = mutableListOf<Proc>()
        fun visit(proc: Proc, depth: Int) {
            if (depth > 8 || ordered.any { it.pid == proc.pid }) { return }
            byParent[proc.pid].orEmpty().filter { it.user == "shell" }.forEach { visit(it, depth + 1) }
            ordered += proc
        }
        procs.filter(::helper).forEach { visit(it, 0) }
        return ordered
    }

    /** Returns how many processes were ended; throws when any of them is still there after ~3 s. */
    fun clean(shell: (String) -> String, log: (String) -> Unit, pause: () -> Unit = { Thread.sleep(100) }): Int {
        val found = targets(parse(shell(LIST)))
        if (found.isEmpty()) {
            log("LEFTOVER_CAPTURE_NONE helper already gone")
            return 0
        }
        log("LEFTOVER_CAPTURE_FOUND " + found.joinToString { "${it.pid}:${it.args.take(80)}" })
        // A process that has already exited between the listing and the kill is fine.
        shell("kill -9 ${found.joinToString(" ") { it.pid.toString() }} 2>/dev/null; true")
        repeat(30) {
            val now = parse(shell(LIST))
            val alive = found.filter { old -> now.any { it.pid == old.pid && it.args == old.args } }
            if (alive.isEmpty() && now.none(::helper)) {
                log("LEFTOVER_CAPTURE_ENDED count=${found.size}")
                return found.size
            }
            pause()
        }
        throw IOException("LEFTOVER_CAPTURE_STILL_RUNNING " + found.joinToString { it.pid.toString() })
    }
}
