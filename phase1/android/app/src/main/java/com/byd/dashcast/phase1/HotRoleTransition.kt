package com.byd.dashcast.phase1

import java.io.IOException

/** Ordered, testable hot transition. A disappearing USB device is never a Host acknowledgement.
 * @author Codex
 * @date 2026/10/21
 */
internal class HotRoleTransition(private val backend: Backend) {
    data class Observation(val allNcm: Int, val targetNcm: Int) {
        fun requireUnique() {
            if (allNcm < 0 || targetNcm < 0 || targetNcm > allNcm) {
                throw IllegalArgumentException("Invalid USB observation")
            }
            if (allNcm != targetNcm || allNcm > 1) {
                throw IOException("USB_AMBIGUOUS: 请只连接本仪表的 USB 设备")
            }
        }
    }

    interface Backend {
        fun observe(): Observation
        fun stopAndAwaitClean()
        fun acquire(): AutoCloseable
        fun requireClean()
        fun releaseTargetData()
        fun send(command: InstrumentUsbCommand)
        fun awaitObservation(command: InstrumentUsbCommand): Boolean
    }

    enum class Result { EXISTING_NCM, NCM_ENUMERATED, REQUEST_SENT_PEER_UNKNOWN, HOST_DETACHED_NOT_ACKNOWLEDGED }

    fun execute(command: InstrumentUsbCommand): Result {
        val first = backend.observe().also { it.requireUnique() }
        // Preserve a running session, its interface and lease on a repeated NCM request.
        if (command == InstrumentUsbCommand.NCM && first.targetNcm == 1) { return Result.EXISTING_NCM }
        backend.stopAndAwaitClean()
        backend.acquire().use {
            backend.requireClean()
            val fresh = backend.observe().also { it.requireUnique() }
            if (command == InstrumentUsbCommand.NCM && fresh.targetNcm == 1) { return Result.EXISTING_NCM }
            if (command == InstrumentUsbCommand.HOST && fresh.targetNcm == 1) {
                // This must prove ALT 0 + both claims released, not merely close a socket.
                backend.releaseTargetData()
            }
            backend.requireClean()
            backend.send(command) // exactly one OEM request; no repeated reset loop
            val observed = backend.awaitObservation(command)
            return when {
                command == InstrumentUsbCommand.NCM && observed -> Result.NCM_ENUMERATED
                command == InstrumentUsbCommand.HOST && observed -> Result.HOST_DETACHED_NOT_ACKNOWLEDGED
                else -> Result.REQUEST_SENT_PEER_UNKNOWN
            }
        }
    }
}
