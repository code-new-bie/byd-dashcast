package com.byd.dashcast.phase1

import java.io.IOException

/** Attempts all proven-owned cleanup steps without touching unrelated primary tasks.
 * @author Codex
 * @date 2026/10/23
 */
internal object IndependentTaskCleanup {
    interface Backend {
        fun tasks(): List<AppTaskPolicy.Task>
        fun remove(task: AppTaskPolicy.Task)
        fun releaseDisplay()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun close(policy: IndependentTaskPolicy?, backend: Backend) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (error is InterruptedException) { Thread.currentThread().interrupt() }
                val previous = failure
                if (previous == null) { failure = error }
                else if (error is Error && previous !is Error) {
                    error.addSuppressed(previous)
                    failure = error
                } else { previous.addSuppressed(error) }
            }
        }
        var candidates = emptyList<AppTaskPolicy.Task>()
        if (policy != null) { attempt { candidates = policy.cleanupCandidates(backend.tasks()) } }
        candidates.forEach { task -> attempt { backend.remove(task) } }
        attempt { backend.releaseDisplay() }
        failure?.let {
            // OEM linkage/runtime errors remain fatal after releasing our display; never report success.
            if (it is Error) { throw it }
            throw IOException("CLEANUP_UNCONFIRMED independent task/display", it)
        }
    }
}
