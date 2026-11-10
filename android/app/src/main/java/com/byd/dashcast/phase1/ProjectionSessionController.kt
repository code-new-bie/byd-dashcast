package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single owned session, independent of Activity lifetime. Cleanup uncertainty blocks retries.
 * @author Codex
 * @date 2026/10/13
 */
internal class ProjectionSessionController(executor: Executor?, observer: Observer?) {
    enum class State {
        IDLE, STARTING, STREAMING, STOPPING, STOPPED, FAILED, DISCONNECTED, RECOVERY_REQUIRED, QUARANTINED
    }

    fun interface Task {
        /** Must return only after owned capture, media and USB cleanup; throws on uncertainty. */
        @Throws(IOException::class)
        fun run(stop: AtomicBoolean, abort: AtomicBoolean, streaming: Runnable, cleaning: Runnable)
    }

    /** What a QUARANTINED session left unconfirmed, which decides how it is cleaned up afterwards. */
    enum class CleanupScope {
        /** Only the shell capture helper's exit; USB and the instrument receiver were confirmed closed. */
        CAPTURE,

        /** The capture helper's exit and the instrument receiver's reply; local USB was confirmed closed. */
        CAPTURE_AND_PEER,

        /** Anything held by this app process (USB, threads) or unknown: only a process restart releases it. */
        LOCAL
    }

    fun interface Observer {
        fun state(state: State, detail: String)

        /** QUARANTINED only; observers that do not care about the scope get the plain callback. */
        fun quarantined(detail: String, scope: CleanupScope) {
            state(State.QUARANTINED, detail)
        }
    }

    open class CleanupUnconfirmedException(message: String?, cause: Throwable?) : IOException(message, cause) {
        private companion object {
            const val serialVersionUID = 1L
        }
    }

    /** The capture helper's exit was not confirmed, while this process's own USB resources were. */
    class CaptureExitUnconfirmedException(
        message: String?,
        cause: Throwable?,
        @JvmField val receiverConfirmed: Boolean,
    ) : CleanupUnconfirmedException(message, cause) {
        private companion object {
            const val serialVersionUID = 1L
        }
    }

    /** Physical link loss with owned Android capture/USB resources already closed. */
    class DetachedSessionException(cause: Throwable?) :
        IOException("DETACHED_RECOVERY_REQUIRED: local resources closed; peer recovery not yet confirmed", cause) {
        private companion object {
            const val serialVersionUID = 1L
        }
    }

    /** Only for a completed local teardown; a missing peer reply is not a local resource leak. */
    class PeerCleanupPendingException(detail: String?, cause: Throwable?) :
        IOException("PEER_RECOVERY_REQUIRED: $detail", cause) {
        private companion object {
            const val serialVersionUID = 1L
        }
    }

    private val executor: Executor = Objects.requireNonNull(executor)!!
    private val observer: Observer = Objects.requireNonNull(observer)!!
    private var state = State.IDLE
    private var stop: AtomicBoolean? = null
    private var abort: AtomicBoolean? = null
    private var active = false

    @Synchronized
    fun start(task: Task?): Boolean {
        Objects.requireNonNull(task)
        task!!
        if (active || state == State.QUARANTINED || state == State.DISCONNECTED || state == State.RECOVERY_REQUIRED) {
            return false
        }
        active = true
        val ownedStop = AtomicBoolean()
        val ownedAbort = AtomicBoolean()
        stop = ownedStop
        abort = ownedAbort
        change(State.STARTING, "starting one bounded session")
        try {
            executor.execute { execute(task, ownedStop, ownedAbort) }
        } catch (error: RuntimeException) {
            active = false
            change(State.FAILED, "Worker rejected before session start: $error")
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (active) {
            stop!!.set(true)
            change(State.STOPPING, "graceful stop requested; waiting for owned cleanup")
        }
    }

    @Synchronized
    fun abort(reason: String) {
        if (active) {
            stop!!.set(true)
            abort!!.set(true)
            change(State.STOPPING, "$reason; cleanup required, no auto Host/reconnect")
        }
    }

    @Synchronized
    fun active(): Boolean = active

    @Synchronized
    fun state(): State = state

    private fun execute(task: Task, ownedStop: AtomicBoolean, ownedAbort: AtomicBoolean) {
        var outcome = State.STOPPED
        var scope = CleanupScope.LOCAL
        var fatal: Error? = null
        var detail = "owned capture/receiver/USB cleanup confirmed; check instrument window manually"
        try {
            task.run(ownedStop, ownedAbort, Runnable { streaming() }, Runnable { cleaning() })
        } catch (error: DetachedSessionException) {
            outcome = State.DISCONNECTED
            detail = error.message.toString()
        } catch (error: PeerCleanupPendingException) {
            outcome = State.RECOVERY_REQUIRED
            detail = error.message.toString()
        } catch (error: CaptureExitUnconfirmedException) {
            outcome = State.QUARANTINED
            scope = if (error.receiverConfirmed) CleanupScope.CAPTURE else CleanupScope.CAPTURE_AND_PEER
            detail = error.message + "; leftover capture helper cleanup follows; no guard deletion"
        } catch (error: CleanupUnconfirmedException) {
            outcome = State.QUARANTINED
            detail = error.message + "; app restart required; no guard deletion"
        } catch (error: IOException) {
            outcome = State.FAILED
            detail = "Session failed with owned cleanup confirmed: $error"
        } catch (error: RuntimeException) {
            outcome = State.QUARANTINED
            detail = "Unexpected worker failure; owned cleanup not confirmed: $error"
        } catch (error: Error) {
            outcome = State.QUARANTINED
            detail = "Fatal worker failure; cleanup not confirmed: " + error.javaClass.simpleName
            fatal = error
        } finally {
            synchronized(this) {
                active = false
                if (outcome == State.QUARANTINED) {
                    state = outcome
                    observer.quarantined(detail, scope)
                } else {
                    change(outcome, detail)
                }
            }
        }
        if (fatal != null) {
            throw fatal
        }
    }

    @Synchronized
    private fun streaming() {
        if (active && state == State.STARTING) {
            change(State.STREAMING, "first FRAME ACK; actual instrument picture requires observation")
        }
    }

    @Synchronized
    private fun cleaning() {
        if (active && state != State.STOPPING) {
            change(State.STOPPING, "owned resource cleanup in progress; role changes remain blocked")
        }
    }

    private fun change(next: State, detail: String) {
        state = next
        observer.state(next, detail)
    }
}
