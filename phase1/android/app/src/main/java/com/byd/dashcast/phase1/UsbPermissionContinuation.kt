package com.byd.dashcast.phase1

/** One-shot, exact-device continuation. Permission dialogs do not grant a new operation.
 * @author Codex
 * @date 2026/10/23
 */
internal class UsbPermissionContinuation<T> {
    enum class Result { UNRELATED, STALE, DENIED, GRANTED }
    data class Outcome<T>(val result: Result, val request: T? = null)
    private data class Pending<T>(val device: String, val generation: Long, val request: T)
    private var pending: Pending<T>? = null

    @Synchronized fun begin(device: String, generation: Long, request: T) {
        require(device.isNotBlank() && generation > 0)
        pending = Pending(device, generation, request)
    }

    @Synchronized fun consume(device: String, generation: Long, granted: Boolean): Outcome<T> {
        val waiting = pending ?: return Outcome(Result.UNRELATED)
        if (waiting.device != device) { return Outcome(Result.UNRELATED) }
        pending = null
        if (waiting.generation != generation) { return Outcome(Result.STALE) }
        return if (granted) Outcome(Result.GRANTED, waiting.request) else Outcome(Result.DENIED)
    }

    @Synchronized fun clear() { pending = null }
}
