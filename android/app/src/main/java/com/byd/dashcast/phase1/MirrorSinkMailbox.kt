package com.byd.dashcast.phase1

import android.view.Surface

/** Binder threads transfer ownership; only the capture thread touches EGL. Stale detach cannot unbind a new page.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorSinkMailbox : AutoCloseable {
    data class Sink(val lease: Long, val surface: Surface?, val width: Int, val height: Int)
    private var lease = 0L
    private var pending: Sink? = null
    private var changed = false
    private var closed = false

    @Synchronized fun attach(next: Sink) {
        if (closed || next.lease <= lease || next.width !in 1..8192 || next.height !in 1..8192) {
            next.surface?.release()
            return
        }
        pending?.surface?.release()
        lease = next.lease
        pending = next
        changed = true
    }

    @Synchronized fun detach(owner: Long) {
        if (closed || owner != lease) { return }
        pending?.surface?.release()
        pending = Sink(lease, null, 0, 0)
        changed = true
    }

    @Synchronized fun take(): Sink? {
        if (!changed) { return null }
        changed = false
        return pending.also { pending = null }
    }

    @Synchronized override fun close() {
        closed = true
        pending?.surface?.release()
        pending = null
        changed = false
    }
}
