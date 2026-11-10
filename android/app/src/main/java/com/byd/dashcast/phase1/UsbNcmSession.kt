package com.byd.dashcast.phase1

import android.content.Context
import android.hardware.usb.UsbDevice
import com.byd.dashcast.phase1.ncm.LogHub
import com.byd.dashcast.phase1.ncm.Ncm
import com.byd.dashcast.phase1.ncm.Net
import com.byd.dashcast.phase1.ncm.Prefs
import com.byd.dashcast.phase1.ncm.TcpConn
import com.byd.dashcast.phase1.ncm.Usb
import java.io.Closeable
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/**
 * One explicitly authorized, user-space NCM session; no IP/route/system permissions.
 * @author Codex
 * @date 2026/10/10
 */
internal class UsbNcmSession private constructor(
    private val usb: Usb.Session,
    private val network: Net,
    private val ncm: Ncm,
) : Closeable {
    private var detached = BooleanSupplier { false }

    @Throws(IOException::class)
    fun connect(port: Int, cancelled: AtomicBoolean?): Channel = connect(port, cancelled, 15_000_000_000L)

    @Throws(IOException::class)
    fun connectRecovery(cancelled: AtomicBoolean?): Channel = connect(NoAuthVideoProtocol.PORT, cancelled, 2_000_000_000L)

    fun observePhysicalDetach(observation: BooleanSupplier?) {
        detached = Objects.requireNonNull(observation)!!
    }

    @Throws(IOException::class)
    private fun connect(port: Int, cancelled: AtomicBoolean?, replyBudgetNanos: Long): Channel {
        if (port != NoAuthVideoProtocol.PORT) {
            throw IOException("Only the DCV1 private USB video port is permitted")
        }
        if (cancelled == null || cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("Video verification cancelled")
        }
        val connection = network.connect(network.peerIp(), port, 5000)
        if (!connection.isEstablished()) {
            val error = connection.error()
            connection.abort()
            throw IOException("DCV1_PORT_UNAVAILABLE port=$port: $error")
        }
        return Channel(connection, cancelled, detached, replyBudgetNanos)
    }

    fun statistics(): String = network.statsLine() + "\nNCM tx=" + ncm.txNtb + " rx=" + ncm.rxNtb +
        " invalid=" + ncm.rxBadNtb + " txErrors=" + ncm.txErr + " dropped=" + ncm.txDrop

    @Throws(IOException::class)
    override fun close() {
        network.stop()
        ncm.stop()
        val restored = usb.close()
        LogHub.i("session", "USB released; instrument role NOT automatically changed")
        if (!restored) {
            throw IOException("USB data deactivation unconfirmed; stop testing and unplug before role change")
        }
    }

    /** Only for an OS-observed physical removal. Missing bus acknowledgements remain unconfirmed. */
    @Throws(IOException::class)
    fun closeDetached() {
        if (!detached.asBoolean) {
            throw IOException("PHYSICAL_DETACH_PROOF_REQUIRED")
        }
        network.stop()
        ncm.stop()
        usb.close()
        if (!localResourcesClosed()) {
            throw IOException("CLEANUP_UNCONFIRMED detached local USB/pump resources")
        }
        LogHub.i("session", "DETACHED_LOCAL_USB_CLOSED; ALT0/remote cleanup not acknowledged")
    }

    fun localResourcesClosed(): Boolean = usb.connectionClosed() && ncm.stoppedConfirmed() && network.awaitStopped(500)

    fun physicalLinkAbsent(context: Context): Boolean = try {
        val manager = Usb.mgr(context)
        manager != null && !manager.deviceList.containsKey(usb.device.deviceName)
    } catch (error: RuntimeException) {
        LogHub.w("session", "USB removal observation unavailable; not treating uncertainty as detach")
        false
    }

    /** Single bounded TCP stream over NCM, with no alternate network fallback. */
    class Channel internal constructor(
        private val connection: TcpConn,
        private val cancelled: AtomicBoolean,
        private val detached: BooleanSupplier,
        private val replyBudgetNanos: Long,
    ) : NoAuthVideoTransport {
        private var cleanup = false

        @Throws(IOException::class)
        override fun write(data: ByteArray) {
            checkCancellation()
            if (connection.send(data, data.size, 5000) != data.size) {
                throw IOException("NCM TCP write not acknowledged: " + connection.statusLine() +
                    " peer_error=" + connection.error())
            }
        }

        @Throws(IOException::class)
        override fun readExact(count: Int): ByteArray {
            if (count < 1 || count > NoAuthVideoProtocol.MAX_REPLY_READ_BYTES) {
                throw IOException("DCV1 reply read limit")
            }
            // Error/STOP replies now follow owned media teardown (six bounded
            // 2s IPCs at worst). Never abandon USB before the cleanup reply.
            val deadline = System.nanoTime() + replyBudgetNanos
            val result = ByteArray(count)
            var offset = 0
            while (offset < count && System.nanoTime() < deadline) {
                checkCancellation()
                val received = connection.read(result, offset, count - offset, 200)
                if (received < 0) {
                    throw IOException("Instrument closed the NCM TCP connection")
                }
                if (received == 0) {
                    continue
                }
                offset += received
            }
            if (offset == count) {
                return result
            }
            throw IOException("DCV1 binary reply timeout")
        }

        override fun beginCleanup() {
            cleanup = true
        }

        @Throws(IOException::class)
        private fun checkCancellation() {
            if (detached.asBoolean || (!cleanup && cancelled.get()) || Thread.currentThread().isInterrupted) {
                throw IOException("Video verification cancelled")
            }
        }

        override fun close() {
            connection.close(600)
        }
    }

    companion object {
        /** Descriptor-only qualification; the role policy checks target VID/PID separately. */
        @JvmStatic
        fun isNcmDevice(device: UsbDevice): Boolean {
            var ncm = false
            var storage = false
            for (index in 0 until device.interfaceCount) {
                val item = device.getInterface(index)
                ncm = ncm or (item.interfaceClass == 2 && item.interfaceSubclass == 13)
                storage = storage or (item.interfaceClass == 8)
            }
            return ncm && !storage
        }

        @JvmStatic
        @Throws(IOException::class)
        fun findDevice(context: Context): UsbDevice {
            val manager = Usb.mgr(context) ?: throw IOException("UsbManager unavailable")
            var selected: UsbDevice? = null
            for (device in manager.deviceList.values) {
                if (!isNcmDevice(device)) {
                    continue
                }
                if (selected != null) {
                    throw IOException("Multiple NCM devices; connect only the target instrument")
                }
                selected = device
            }
            return selected ?: throw IOException("No dedicated NCM device. Switch instrument and connect its USB first")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun open(context: Context, cancelled: AtomicBoolean?): UsbNcmSession {
            val device = findDevice(context)
            if (device.vendorId != 0x1234 || device.productId != 0xfff1) {
                throw IOException("Non-target NCM device; instrument session was not opened")
            }
            var usb: Usb.Session? = null
            var network: Net? = null
            var ncm: Ncm? = null
            try {
                usb = Usb.open(context, device)
                var pump: Ncm? = null
                network = Net { frame, length -> pump?.send(frame, length) }
                network.configure(Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, Prefs.DEF_PEER_MAC, Prefs.DEF_PEER_IP, 30, 1500, true)
                ncm = Ncm(usb, network)
                pump = ncm
                network.start()
                ncm.start(cancelled)
                return UsbNcmSession(usb, network, ncm)
            } catch (error: Exception) {
                network?.stop()
                ncm?.stop()
                if (usb != null && !usb.close()) {
                    error.addSuppressed(IOException("USB_DATA_ALT0_CLEANUP_UNCONFIRMED; unplug before role change"))
                }
                LogHub.throwable("session", "NCM_OPEN_FAILED before TCP/video", error)
                throw IOException("USB NCM open failed: " + error.message, error)
            }
        }
    }
}
