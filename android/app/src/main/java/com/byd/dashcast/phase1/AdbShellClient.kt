package com.byd.dashcast.phase1

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Objects
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.LongSupplier

/**
 * Bounded local ADB client with standard user-approved RSA authentication.
 *
 * @author Codex
 * @date 2026/10/09
 */
class AdbShellClient internal constructor(
    host: String?,
    port: Int,
    timeoutMillis: Int,
    private val authenticator: AdbAuthenticator?,
    log: Consumer<String>?,
    cancelled: AtomicBoolean?,
    nanoTime: LongSupplier?,
) {
    private val host: String
    private val port: Int
    private val timeoutMillis: Int
    private val log: Consumer<String>
    private val cancelled: AtomicBoolean
    private val nanoTime: LongSupplier
    private var readDeadline = 0L
    private var readPulse = ReadPulse {}

    init {
        if (host == null || host.isEmpty()) {
            throw IllegalArgumentException("ADB host must not be empty")
        }
        if (port <= 0 || port > 65535) {
            throw IllegalArgumentException("ADB port is invalid")
        }
        if (timeoutMillis <= 0) {
            throw IllegalArgumentException("ADB timeout must be positive")
        }
        this.host = host
        this.port = port
        this.timeoutMillis = timeoutMillis
        this.log = Objects.requireNonNull(log)!!
        this.cancelled = Objects.requireNonNull(cancelled)!!
        this.nanoTime = Objects.requireNonNull(nanoTime)!!
    }

    /**
     * Creates a local ADB client.
     *
     * @param host adbd host
     * @param port adbd port
     * @param timeoutMillis socket connect/read timeout
     */
    constructor(host: String?, port: Int, timeoutMillis: Int) :
        this(host, port, timeoutMillis, null, Consumer {}, AtomicBoolean())

    /** Local transport using only an app-owned identity and normal device confirmation. */
    constructor(host: String?, port: Int, timeoutMillis: Int, authenticator: AdbAuthenticator?,
        log: Consumer<String>?, cancelled: AtomicBoolean?) :
        this(host, port, timeoutMillis, authenticator, log, cancelled, LongSupplier { System.nanoTime() })

    /**
     * Executes one command through the ADB exec service and streams raw stdout bytes.
     *
     * @param command shell command
     * @param outputHandler output callback
     * @throws IOException when the ADB session fails
     */
    @Throws(IOException::class)
    fun executeShell(command: String?, outputHandler: ShellOutputHandler?) {
        executeNetwork(command, outputHandler, false, null, Runnable {})
    }

    /** Streams stdout only and requires a successful shell-v2 EXIT; stderr never enters binary video. */
    @Throws(IOException::class)
    fun executeShellV2(command: String, outputHandler: ShellOutputHandler) {
        executeShellV2(command, outputHandler, Consumer {})
    }

    /** Bounded stderr diagnostics for the explicitly requested capture, never mixed into AVC. */
    @Throws(IOException::class)
    fun executeShellV2(command: String, outputHandler: ShellOutputHandler, diagnostics: Consumer<String>) {
        executeShellV2Controlled(command, outputHandler, diagnostics, null)
    }

    /** Graceful stop sends STOP to this shell's stdin only, then requires EXIT within a finite drain budget. */
    @Throws(IOException::class)
    fun executeShellV2Controlled(command: String, outputHandler: ShellOutputHandler, diagnostics: Consumer<String>,
        stopRequested: AtomicBoolean?) {
        executeShellV2Controlled(command, outputHandler, diagnostics, stopRequested, Runnable {})
    }

    @Throws(IOException::class)
    fun executeShellV2Controlled(command: String, outputHandler: ShellOutputHandler, diagnostics: Consumer<String>,
        stopRequested: AtomicBoolean?, beforeStop: Runnable) {
        executeShellV2Controlled(command, outputHandler, diagnostics, stopRequested, beforeStop, Consumer {})
    }

    @Throws(IOException::class)
    fun executeShellV2Controlled(command: String, outputHandler: ShellOutputHandler, diagnostics: Consumer<String>,
        stopRequested: AtomicBoolean?, beforeStop: Runnable, exitConfirmation: Consumer<Boolean>) {
        val output = ShellV2Output(outputHandler, diagnostics, stopRequested != null)
        try {
            executeNetwork(command, output, true, stopRequested, beforeStop)
            output.requireSuccess()
            log.accept("ADB_SHELL_V2_EXIT=0 stderr_bytes=" + output.stderrBytes())
        } catch (error: IOException) {
            throw output.preserveConsumerFailure(error)
        } finally {
            // Preserve already received capture evidence even when the frame consumer rejects.
            output.reportDiagnostics()
            log.accept(output.completionStatus())
            exitConfirmation.accept(output.exitConfirmed())
        }
    }

    @Throws(IOException::class)
    private fun executeNetwork(command: String?, outputHandler: ShellOutputHandler?, shellV2: Boolean,
        stopRequested: AtomicBoolean?, beforeStop: Runnable) {
        if (command == null || command.isEmpty() || command.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("ADB shell command must not be empty")
        }
        if (outputHandler == null) {
            throw IllegalArgumentException("ADB output handler must not be null")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = minOf(timeoutMillis, 1000)
            runProtocol(socket.getInputStream(), socket.getOutputStream(), command, outputHandler,
                shellV2, stopRequested, beforeStop)
        }
    }

    // Same production state machine is exercised by in-memory peers in unit tests.
    @Throws(IOException::class)
    fun executeProtocol(input: InputStream, output: OutputStream, command: String, handler: ShellOutputHandler) {
        runProtocol(input, output, command, handler, false, null, Runnable {})
    }

    @Throws(IOException::class)
    fun executeShellV2Protocol(input: InputStream, output: OutputStream, command: String, handler: ShellOutputHandler) {
        val decoded = ShellV2Output(handler)
        runProtocol(input, output, command, decoded, true, null, Runnable {})
        decoded.requireSuccess()
    }

    @Throws(IOException::class)
    fun executeControlledProtocol(input: InputStream, output: OutputStream, command: String,
        handler: ShellOutputHandler, stopRequested: AtomicBoolean?) {
        executeControlledProtocol(input, output, command, handler, stopRequested, Runnable {})
    }

    @Throws(IOException::class)
    fun executeControlledProtocol(input: InputStream, output: OutputStream, command: String,
        handler: ShellOutputHandler, stopRequested: AtomicBoolean?, beforeStop: Runnable) {
        val decoded = ShellV2Output(handler, Consumer {}, true)
        try {
            runProtocol(input, output, command, decoded, true, stopRequested, beforeStop)
            decoded.requireSuccess()
        } catch (error: IOException) {
            throw decoded.preserveConsumerFailure(error)
        }
    }

    @Throws(IOException::class)
    private fun runProtocol(input: InputStream, output: OutputStream, command: String, handler: ShellOutputHandler,
        shellV2: Boolean, stopRequested: AtomicBoolean?, beforeStop: Runnable) {
        checkCancelled()
        renewReadDeadline()
        sendPacket(output, COMMAND_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
        val connected = authenticate(input, output)
        if (shellV2 && !supportsShellV2(connected.payload)) {
            throw IOException("Authorized adbd did not advertise shell_v2; capture not started")
        }
        val service = ((if (shellV2) "shell,v2,raw:" else "exec:") + command + "\u0000").toByteArray(Charsets.UTF_8)
        if (service.size > minOf(MAX_PAYLOAD, connected.arg1)) {
            throw IOException("ADB command exceeds the negotiated payload limit")
        }
        checkCancelled()
        renewReadDeadline()
        sendPacket(output, COMMAND_OPEN, 1, 0, service)
        val control = ShellControl(output, stopRequested, beforeStop, handler)
        readPulse = ReadPulse { control.beforeRead() }
        try {
            readShellPackets(input, output, handler, 1, control)
        } finally {
            readPulse = ReadPulse {}
        }
    }

    @Throws(IOException::class)
    private fun authenticate(input: InputStream, output: OutputStream): Packet {
        var signed = false
        var published = false
        while (true) {
            val packet = readPacket(input)
            if (packet.command == COMMAND_CNXN) {
                if (packet.arg0 < ADB_VERSION || packet.arg1 < 1024) {
                    throw IOException("Unsupported ADB protocol version/payload")
                }
                log.accept("ADB_CONNECTED shell_v2=" + supportsShellV2(packet.payload) +
                    "; shell identity still needs a read-only check")
                return packet
            }
            if (packet.command != COMMAND_AUTH || packet.arg0 != 1 || packet.arg1 != 0 || packet.payload.size != 20) {
                throw IOException("Unexpected ADB handshake/challenge: " + commandName(packet.command))
            }
            val identity = authenticator ?: throw IOException("ADB requires an application-owned authorized identity")
            if (!signed) {
                readDeadline = nanoTime.asLong + TimeUnit.MILLISECONDS.toNanos(AUTH_TIMEOUT_MILLIS.toLong())
                val signature = identity.sign(packet.payload)
                if (signature.size != 256) {
                    throw IOException("Invalid ADB authentication signature length")
                }
                sendPacket(output, COMMAND_AUTH, 2, 0, signature)
                signed = true
                log.accept("ADB_AUTH_SIGNATURE sent using this APK's key")
            } else if (!published) {
                log.accept("ADB_AUTH_CONFIRM: 请在车机的 USB 调试授权弹窗点允许；最多等待 60 秒。")
                val key = identity.publicKey()
                if (key.isEmpty() || key.size > MAX_PAYLOAD || key[key.size - 1].toInt() != 0) {
                    throw IOException("Invalid ADB public-key payload")
                }
                sendPacket(output, COMMAND_AUTH, 3, 0, key)
                published = true
            } else {
                throw IOException("ADB 未信任本 APK 密钥；未执行命令。请允许调试授权后重新连接。")
            }
        }
    }

    @Throws(IOException::class)
    private fun readShellPackets(input: InputStream, output: OutputStream, outputHandler: ShellOutputHandler,
        localId: Int, control: ShellControl) {
        var remoteId = 0
        var opened = false
        while (true) {
            val packet = readPacket(input)
            if (packet.arg1 != localId) {
                throw IOException("ADB packet addressed an unexpected stream")
            }
            if (packet.command == COMMAND_OKAY) {
                if (packet.arg0 == 0 || packet.payload.isNotEmpty() || (opened && packet.arg0 != remoteId)) {
                    throw IOException("Invalid ADB stream acknowledgement")
                }
                remoteId = packet.arg0
                opened = true
                control.remoteId = remoteId
                continue
            }
            if (packet.command == COMMAND_WRTE) {
                if (!opened || packet.arg0 != remoteId) {
                    throw IOException("ADB sent data before OPEN or on the wrong stream")
                }
                if (packet.payload.isNotEmpty()) {
                    outputHandler.onOutput(packet.payload, packet.payload.size)
                }
                sendPacket(output, COMMAND_OKAY, localId, remoteId, ByteArray(0))
                // A long stream is bounded by capture/session limits, not by an absolute 20s ADB deadline.
                // Only valid consumed shell data renews the idle timeout; empty ACK traffic does not.
                if (packet.payload.isNotEmpty() && control.stop != null) {
                    renewReadDeadline()
                }
                continue
            }
            if (packet.command == COMMAND_CLSE) {
                if (!opened) {
                    throw IOException("ADB exec service rejected OPEN; no command completion")
                }
                if ((packet.arg0 != 0 && packet.arg0 != remoteId) || packet.payload.isNotEmpty()) {
                    throw IOException("Invalid ADB stream close")
                }
                return
            }
            if (packet.command == COMMAND_AUTH) {
                throw IOException("adbd requested authentication during shell session")
            }
            throw IOException("unexpected ADB exec command: " + commandName(packet.command))
        }
    }

    @Throws(IOException::class)
    private fun readPacket(input: InputStream): Packet {
        val header = readFully(input, 24)
        val command = readLittleEndianInt(header, 0)
        val arg0 = readLittleEndianInt(header, 4)
        val arg1 = readLittleEndianInt(header, 8)
        val dataLength = readLittleEndianInt(header, 12)
        val checksum = readLittleEndianInt(header, 16)
        val magic = readLittleEndianInt(header, 20)
        if (command.inv() != magic) {
            throw IOException("invalid ADB packet magic")
        }
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE) {
            throw IOException("invalid ADB packet length: $dataLength")
        }
        val payload = readFully(input, dataLength)
        if (checksum(payload) != checksum) {
            throw IOException("invalid ADB packet checksum")
        }
        return Packet(command, arg0, arg1, payload)
    }

    @Throws(IOException::class)
    private fun sendPacket(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        checkCancelled()
        val header = ByteArray(24)
        writeLittleEndianInt(header, 0, command)
        writeLittleEndianInt(header, 4, arg0)
        writeLittleEndianInt(header, 8, arg1)
        writeLittleEndianInt(header, 12, payload.size)
        writeLittleEndianInt(header, 16, checksum(payload))
        writeLittleEndianInt(header, 20, command.inv())
        output.write(header)
        output.write(payload)
        output.flush()
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            checkCancelled()
            readPulse.beforeRead()
            if (nanoTime.asLong >= readDeadline) {
                throw IOException("ADB 等待超时；如有授权弹窗请允许后重新连接。命令完成未确认。")
            }
            val count = try {
                input.read(result, offset, length - offset)
            } catch (error: SocketTimeoutException) {
                continue
            }
            if (count < 0) {
                throw EOFException("ADB socket closed before packet completed")
            }
            if (count == 0) {
                continue
            }
            offset += count
        }
        return result
    }

    @Throws(IOException::class)
    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("ADB operation cancelled; previously submitted commands cannot be undone")
        }
    }

    private fun renewReadDeadline() {
        readDeadline = nanoTime.asLong + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    }

    private fun interface ReadPulse {
        @Throws(IOException::class)
        fun beforeRead()
    }

    private inner class ShellControl(
        private val output: OutputStream,
        val stop: AtomicBoolean?,
        beforeStop: Runnable?,
        private val handler: ShellOutputHandler,
    ) {
        private val beforeStop: Runnable = Objects.requireNonNull(beforeStop)!!
        var remoteId = 0
        private var sent = false
        private var deadline = 0L

        @Throws(IOException::class)
        fun beforeRead() {
            if (remoteId != 0 && !sent && (stop == null || !stop.get())) {
                handler.onIdle()
            }
            val consumerFailed = (handler as? ShellV2Output)?.consumerFailed() == true
            val exited = (handler as? ShellV2Output)?.exitConfirmed() == true
            if (((stop != null && stop.get()) || consumerFailed) && !exited && remoteId != 0 && !sent) {
                beforeStop.run()
                // shell-v2 channel 0=stdin, LE32 length=5, then a fixed owned-process control line.
                sendPacket(output, COMMAND_WRTE, 1, remoteId, STOP_ON_STDIN)
                sent = true
                deadline = nanoTime.asLong + ProjectionLimits.STOP_TIMEOUT_NANOS
                renewReadDeadline()
                log.accept("CAPTURE_STOP_STDIN_SENT; waiting for owned shell-v2 EXIT, no global kill")
            }
            if (sent && nanoTime.asLong >= deadline) {
                throw IOException("CAPTURE_STOP_EXIT_UNCONFIRMED")
            }
        }
    }

    /**
     * Receives a chunk from an ADB shell session.
     *
     * @author Codex
     * @date 2026/10/09
     */
    fun interface ShellOutputHandler {
        /**
         * Handles one raw shell output chunk.
         *
         * @param data output bytes
         * @param length valid byte count
         * @throws IOException when the consumer cannot accept data
         */
        @Throws(IOException::class)
        fun onOutput(data: ByteArray?, length: Int)

        /** Maintain the owned video session without a second reader or fabricated video. */
        @Throws(IOException::class)
        fun onIdle() {
        }
    }

    private class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private companion object {
        const val ADB_VERSION = 0x01000000
        const val MAX_PAYLOAD = 4096
        const val MAX_PACKET_SIZE = MAX_PAYLOAD
        const val AUTH_TIMEOUT_MILLIS = 60000

        const val COMMAND_AUTH = 0x48545541
        const val COMMAND_CNXN = 0x4e584e43
        const val COMMAND_OPEN = 0x4e45504f
        const val COMMAND_OKAY = 0x59414b4f
        const val COMMAND_WRTE = 0x45545257
        const val COMMAND_CLSE = 0x45534c43

        val STOP_ON_STDIN = byteArrayOf(0, 5, 0, 0, 0) + "STOP\n".toByteArray(Charsets.US_ASCII)
        val SHELL_FEATURE_SEPARATORS = Regex("[;\u0000]")

        fun supportsShellV2(payload: ByteArray): Boolean {
            val banner = String(payload, Charsets.UTF_8)
            for (property in banner.replace("device::", "").split(SHELL_FEATURE_SEPARATORS)) {
                if (!property.startsWith("features=")) {
                    continue
                }
                for (feature in property.substring("features=".length).split(",")) {
                    if ("shell_v2" == feature) {
                        return true
                    }
                }
            }
            return false
        }

        fun checksum(bytes: ByteArray): Int {
            var result = 0
            for (value in bytes) {
                result = (result + (value.toInt() and 0xff)) and 0x7fffffff
            }
            return result
        }

        fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)

        fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }

        fun commandName(command: Int): String {
            val bytes = byteArrayOf(command.toByte(), (command ushr 8).toByte(), (command ushr 16).toByte(), (command ushr 24).toByte())
            return String(bytes, Charsets.US_ASCII)
        }
    }
}
