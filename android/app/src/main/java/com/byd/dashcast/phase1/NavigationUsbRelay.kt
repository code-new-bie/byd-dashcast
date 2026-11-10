package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Explicitly authorized OEM relay workflow; one send, no raw frames or automatic write retries.
 * @author Codex
 * @date 2026/10/10
 */
class NavigationUsbRelay(backend: Backend?, cancelled: AtomicBoolean?, log: Consumer<String>?) {
    private val backend: Backend = Objects.requireNonNull(backend)!!
    private val cancelled: AtomicBoolean = Objects.requireNonNull(cancelled)!!
    private val log: Consumer<String> = Objects.requireNonNull(log)!!

    /** @param command only NCM/Host; @throws IOException if checks fail (submission never certifies USB role) */
    @Throws(IOException::class)
    fun request(command: InstrumentUsbCommand?) {
        Objects.requireNonNull(command)
        command!!
        checkCancelled()
        backend.verifyProfile()
        checkCancelled()
        val uid = backend.executeChecked("id -u").trim()
        if ("2000" != uid && "0" != uid) {
            throw IOException("ADB 执行身份不是 shell/root；未发送。uid=$uid")
        }
        log.accept("ADB_IDENTITY uid=$uid; original navigation relay, not a direct setBuffer call")
        checkCancelled()
        val user = backend.executeChecked("am get-current-user").trim()
        if ("0" != user) {
            throw IOException("当前 Android 用户不是已核对的 user 0；未发送 USB 命令。请导出日志。")
        }
        log.accept("NAV_ANDROID_USER=0; matches OEM service UID=1000")
        checkCancelled()
        backend.executeChecked(NavigationUsbProtocol.START_SERVICE)
        log.accept("NAV_SERVICE_START_REQUESTED; waiting for the live receiver")
        awaitReceiver()
        checkCancelled()
        val output = backend.executeChecked(NavigationUsbProtocol.broadcastCommand(command))
        log.accept("NAV_BROADCAST_SUBMITTED " + command.commandText() +
            "; submission only, service processing / USB role NOT confirmed")
        if (!output.contains("Broadcast completed:")) {
            log.accept("NAV_BROADCAST_OUTPUT_UNEXPECTED; do not resend automatically")
        }
    }

    @Throws(IOException::class)
    private fun awaitReceiver() {
        repeat(6) {
            checkCancelled()
            if (backend.receiverRegistered()) {
                log.accept("NAV_RECEIVER_REGISTERED package=" + NavigationUsbProtocol.PACKAGE)
                return
            }
            backend.pause()
        }
        throw IOException("原厂导航接收器未注册；未发送 USB 命令。请导出日志。")
    }

    @Throws(IOException::class)
    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("操作已取消；不再发送后续命令（已发送的命令不能撤销）")
        }
    }

    /** Platform boundary suitable for deterministic offline tests. */
    interface Backend {
        /** @throws IOException if runtime service code or framework profile is unverified */
        @Throws(IOException::class)
        fun verifyProfile()

        /** @param command fixed internal command; @return stdout; @throws IOException on nonzero/unknown exit */
        @Throws(IOException::class)
        fun executeChecked(command: String): String

        /** @return live package/action receiver; @throws IOException on failed probe */
        @Throws(IOException::class)
        fun receiverRegistered(): Boolean

        /** @throws IOException if polling is cancelled */
        @Throws(IOException::class)
        fun pause()
    }
}
