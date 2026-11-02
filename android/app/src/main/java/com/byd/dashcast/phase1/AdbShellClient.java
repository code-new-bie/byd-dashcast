package com.byd.dashcast.phase1;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounded local ADB client with standard user-approved RSA authentication.
 *
 * @author Codex
 * @date 2026/10/09
 */
public final class AdbShellClient {

    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int MAX_PACKET_SIZE = MAX_PAYLOAD;
    private static final int AUTH_TIMEOUT_MILLIS = 60000;

    private static final int COMMAND_AUTH = 0x48545541;
    private static final int COMMAND_CNXN = 0x4e584e43;
    private static final int COMMAND_OPEN = 0x4e45504f;
    private static final int COMMAND_OKAY = 0x59414b4f;
    private static final int COMMAND_WRTE = 0x45545257;
    private static final int COMMAND_CLSE = 0x45534c43;

    private final String host;
    private final int port;
    private final int timeoutMillis;
    private final AdbAuthenticator authenticator;
    private final Consumer<String> log;
    private final AtomicBoolean cancelled;
    private final LongSupplier nanoTime;
    private long readDeadline;
    private ReadPulse readPulse = () -> {};

    /**
     * Creates a local ADB client.
     *
     * @param host adbd host
     * @param port adbd port
     * @param timeoutMillis socket connect/read timeout
     */
    public AdbShellClient(String host, int port, int timeoutMillis) {
        this(host, port, timeoutMillis, null, message -> {}, new AtomicBoolean());
    }

    /** Local transport using only an app-owned identity and normal device confirmation. */
    public AdbShellClient(String host, int port, int timeoutMillis, AdbAuthenticator authenticator,
            Consumer<String> log, AtomicBoolean cancelled) {
        this(host, port, timeoutMillis, authenticator, log, cancelled, System::nanoTime);
    }

    AdbShellClient(String host, int port, int timeoutMillis, AdbAuthenticator authenticator,
            Consumer<String> log, AtomicBoolean cancelled, LongSupplier nanoTime) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("ADB host must not be empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("ADB port is invalid");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("ADB timeout must be positive");
        }
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        this.authenticator = authenticator;
        this.log = java.util.Objects.requireNonNull(log);
        this.cancelled = java.util.Objects.requireNonNull(cancelled);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    /**
     * Executes one command through the ADB exec service and streams raw stdout bytes.
     *
     * @param command shell command
     * @param outputHandler output callback
     * @throws IOException when the ADB session fails
     */
    public void executeShell(String command, ShellOutputHandler outputHandler)
            throws IOException {
        executeNetwork(command, outputHandler, false);
    }

    /** Streams stdout only and requires a successful shell-v2 EXIT; stderr never enters binary video. */
    public void executeShellV2(String command, ShellOutputHandler outputHandler) throws IOException {
        executeShellV2(command, outputHandler, message -> {});
    }

    /** Bounded stderr diagnostics for the explicitly requested capture, never mixed into AVC. */
    void executeShellV2(String command, ShellOutputHandler outputHandler, Consumer<String> diagnostics) throws IOException {
        executeShellV2Controlled(command, outputHandler, diagnostics, null);
    }

    /** Graceful stop sends STOP to this shell's stdin only, then requires EXIT within a finite drain budget. */
    void executeShellV2Controlled(String command, ShellOutputHandler outputHandler, Consumer<String> diagnostics,
            AtomicBoolean stopRequested) throws IOException {
        executeShellV2Controlled(command, outputHandler, diagnostics, stopRequested, () -> {});
    }

    void executeShellV2Controlled(String command, ShellOutputHandler outputHandler, Consumer<String> diagnostics,
            AtomicBoolean stopRequested, Runnable beforeStop) throws IOException {
        executeShellV2Controlled(command, outputHandler, diagnostics, stopRequested, beforeStop, value -> {});
    }

    void executeShellV2Controlled(String command, ShellOutputHandler outputHandler, Consumer<String> diagnostics,
            AtomicBoolean stopRequested, Runnable beforeStop, Consumer<Boolean> exitConfirmation) throws IOException {
        ShellV2Output output = new ShellV2Output(outputHandler, diagnostics, stopRequested != null);
        try {
            executeNetwork(command, output, true, stopRequested, beforeStop);
            output.requireSuccess();
            log.accept("ADB_SHELL_V2_EXIT=0 stderr_bytes=" + output.stderrBytes());
        } catch (IOException error) {
            throw output.preserveConsumerFailure(error);
        } finally {
            // Preserve already received capture evidence even when the frame consumer rejects.
            output.reportDiagnostics();
            log.accept(output.completionStatus());
            exitConfirmation.accept(output.exitConfirmed());
        }
    }

    private void executeNetwork(String command, ShellOutputHandler outputHandler, boolean shellV2)
            throws IOException {
        executeNetwork(command, outputHandler, shellV2, null);
    }

    private void executeNetwork(String command, ShellOutputHandler outputHandler, boolean shellV2,
            AtomicBoolean stopRequested) throws IOException {
        executeNetwork(command, outputHandler, shellV2, stopRequested, () -> {});
    }

    private void executeNetwork(String command, ShellOutputHandler outputHandler, boolean shellV2,
            AtomicBoolean stopRequested, Runnable beforeStop) throws IOException {
        if (command == null || command.isEmpty() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ADB shell command must not be empty");
        }
        if (outputHandler == null) {
            throw new IllegalArgumentException("ADB output handler must not be null");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(Math.min(timeoutMillis, 1000));
            executeProtocol(socket.getInputStream(), socket.getOutputStream(), command, outputHandler,
                    shellV2, stopRequested, beforeStop);
        }
    }

    // Same production state machine is exercised by in-memory peers in unit tests.
    void executeProtocol(InputStream input, OutputStream output, String command, ShellOutputHandler handler)
            throws IOException {
        executeProtocol(input, output, command, handler, false);
    }

    void executeShellV2Protocol(InputStream input, OutputStream output, String command, ShellOutputHandler handler)
            throws IOException {
        ShellV2Output decoded = new ShellV2Output(handler);
        executeProtocol(input, output, command, decoded, true);
        decoded.requireSuccess();
    }

    private void executeProtocol(InputStream input, OutputStream output, String command,
            ShellOutputHandler handler, boolean shellV2) throws IOException {
        executeProtocol(input, output, command, handler, shellV2, null);
    }

    void executeControlledProtocol(InputStream input, OutputStream output, String command,
            ShellOutputHandler handler, AtomicBoolean stopRequested) throws IOException {
        executeControlledProtocol(input, output, command, handler, stopRequested, () -> {});
    }

    void executeControlledProtocol(InputStream input, OutputStream output, String command,
            ShellOutputHandler handler, AtomicBoolean stopRequested, Runnable beforeStop) throws IOException {
        ShellV2Output decoded = new ShellV2Output(handler, message -> {}, true);
        try {
            executeProtocol(input, output, command, decoded, true, stopRequested, beforeStop);
            decoded.requireSuccess();
        } catch (IOException error) {
            throw decoded.preserveConsumerFailure(error);
        }
    }

    private void executeProtocol(InputStream input, OutputStream output, String command,
            ShellOutputHandler handler, boolean shellV2, AtomicBoolean stopRequested) throws IOException {
        executeProtocol(input, output, command, handler, shellV2, stopRequested, () -> {});
    }

    private void executeProtocol(InputStream input, OutputStream output, String command,
            ShellOutputHandler handler, boolean shellV2, AtomicBoolean stopRequested, Runnable beforeStop)
            throws IOException {
        checkCancelled();
        renewReadDeadline();
        sendPacket(output, COMMAND_CNXN, ADB_VERSION, MAX_PAYLOAD,
                "host::\0".getBytes(StandardCharsets.UTF_8));
        Packet connected = authenticate(input, output);
        if (shellV2 && !supportsShellV2(connected.payload)) {
            throw new IOException("Authorized adbd did not advertise shell_v2; capture not started");
        }
        byte[] service = ((shellV2 ? "shell,v2,raw:" : "exec:") + command + "\0")
                .getBytes(StandardCharsets.UTF_8);
        if (service.length > Math.min(MAX_PAYLOAD, connected.arg1)) {
            throw new IOException("ADB command exceeds the negotiated payload limit");
        }
        checkCancelled();
        renewReadDeadline();
        sendPacket(output, COMMAND_OPEN, 1, 0, service);
        ShellControl control = new ShellControl(output, stopRequested, beforeStop, handler);
        readPulse = control::beforeRead;
        try {
            readShellPackets(input, output, handler, 1, control);
        } finally {
            readPulse = () -> {};
        }
    }

    private static boolean supportsShellV2(byte[] payload) {
        String banner = new String(payload, StandardCharsets.UTF_8);
        for (String property : banner.replace("device::", "").split("[;\u0000]")) {
            if (!property.startsWith("features=")) {
                continue;
            }
            for (String feature : property.substring("features=".length()).split(",")) {
                if ("shell_v2".equals(feature)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Packet authenticate(InputStream input, OutputStream output) throws IOException {
        boolean signed = false;
        boolean published = false;
        while (true) {
            Packet packet = readPacket(input);
            if (packet.command == COMMAND_CNXN) {
                if (packet.arg0 < ADB_VERSION || packet.arg1 < 1024) {
                    throw new IOException("Unsupported ADB protocol version/payload");
                }
                log.accept("ADB_CONNECTED shell_v2=" + supportsShellV2(packet.payload)
                        + "; shell identity still needs a read-only check");
                return packet;
            }
            if (packet.command != COMMAND_AUTH || packet.arg0 != 1 || packet.arg1 != 0
                    || packet.payload.length != 20) {
                throw new IOException("Unexpected ADB handshake/challenge: " + commandName(packet.command));
            }
            if (authenticator == null) {
                throw new IOException("ADB requires an application-owned authorized identity");
            }
            if (!signed) {
                readDeadline = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(AUTH_TIMEOUT_MILLIS);
                byte[] signature = authenticator.sign(packet.payload);
                if (signature.length != 256) {
                    throw new IOException("Invalid ADB authentication signature length");
                }
                sendPacket(output, COMMAND_AUTH, 2, 0, signature);
                signed = true;
                log.accept("ADB_AUTH_SIGNATURE sent using this APK's key");
            } else if (!published) {
                log.accept("ADB_AUTH_CONFIRM: 请在车机的 USB 调试授权弹窗点允许；最多等待 60 秒。");
                byte[] key = authenticator.publicKey();
                if (key.length == 0 || key.length > MAX_PAYLOAD || key[key.length - 1] != 0) {
                    throw new IOException("Invalid ADB public-key payload");
                }
                sendPacket(output, COMMAND_AUTH, 3, 0, key);
                published = true;
            } else {
                throw new IOException("ADB 未信任本 APK 密钥；未执行命令。请允许调试授权后重新连接。");
            }
        }
    }

    private void readShellPackets(InputStream input, OutputStream output,
            ShellOutputHandler outputHandler, int localId, ShellControl control) throws IOException {
        int remoteId = 0;
        boolean opened = false;
        while (true) {
            Packet packet = readPacket(input);
            if (packet.arg1 != localId) {
                throw new IOException("ADB packet addressed an unexpected stream");
            }
            if (packet.command == COMMAND_OKAY) {
                if (packet.arg0 == 0 || packet.payload.length != 0 || (opened && packet.arg0 != remoteId)) {
                    throw new IOException("Invalid ADB stream acknowledgement");
                }
                remoteId = packet.arg0;
                opened = true;
                control.remoteId = remoteId;
                continue;
            }
            if (packet.command == COMMAND_WRTE) {
                if (!opened || packet.arg0 != remoteId) {
                    throw new IOException("ADB sent data before OPEN or on the wrong stream");
                }
                if (packet.payload.length > 0) {
                    outputHandler.onOutput(packet.payload, packet.payload.length);
                }
                sendPacket(output, COMMAND_OKAY, localId, remoteId, new byte[0]);
                // A long stream is bounded by capture/session limits, not by an absolute 20s ADB deadline.
                // Only valid consumed shell data renews the idle timeout; empty ACK traffic does not.
                if (packet.payload.length > 0 && control.stop != null) {
                    renewReadDeadline();
                }
                continue;
            }
            if (packet.command == COMMAND_CLSE) {
                if (!opened) {
                    throw new IOException("ADB exec service rejected OPEN; no command completion");
                }
                if ((packet.arg0 != 0 && packet.arg0 != remoteId) || packet.payload.length != 0) {
                    throw new IOException("Invalid ADB stream close");
                }
                return;
            }
            if (packet.command == COMMAND_AUTH) {
                throw new IOException("adbd requested authentication during shell session");
            }
            throw new IOException("unexpected ADB exec command: "
                    + commandName(packet.command));
        }
    }

    private Packet readPacket(InputStream input) throws IOException {
        byte[] header = readFully(input, 24);
        int command = readLittleEndianInt(header, 0);
        int arg0 = readLittleEndianInt(header, 4);
        int arg1 = readLittleEndianInt(header, 8);
        int dataLength = readLittleEndianInt(header, 12);
        int checksum = readLittleEndianInt(header, 16);
        int magic = readLittleEndianInt(header, 20);
        if ((command ^ 0xffffffff) != magic) {
            throw new IOException("invalid ADB packet magic");
        }
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE) {
            throw new IOException("invalid ADB packet length: " + dataLength);
        }
        byte[] payload = readFully(input, dataLength);
        if (checksum(payload) != checksum) {
            throw new IOException("invalid ADB packet checksum");
        }
        return new Packet(command, arg0, arg1, payload);
    }

    private void sendPacket(OutputStream output, int command, int arg0, int arg1,
            byte[] payload) throws IOException {
        checkCancelled();
        byte[] header = new byte[24];
        writeLittleEndianInt(header, 0, command);
        writeLittleEndianInt(header, 4, arg0);
        writeLittleEndianInt(header, 8, arg1);
        writeLittleEndianInt(header, 12, payload.length);
        writeLittleEndianInt(header, 16, checksum(payload));
        writeLittleEndianInt(header, 20, command ^ 0xffffffff);
        output.write(header);
        output.write(payload);
        output.flush();
    }

    private byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            checkCancelled();
            readPulse.beforeRead();
            if (nanoTime.getAsLong() >= readDeadline) {
                throw new IOException("ADB 等待超时；如有授权弹窗请允许后重新连接。命令完成未确认。");
            }
            final int count;
            try {
                count = input.read(result, offset, length - offset);
            } catch (SocketTimeoutException error) {
                continue;
            }
            if (count < 0) {
                throw new EOFException("ADB socket closed before packet completed");
            }
            if (count == 0) {
                continue;
            }
            offset += count;
        }
        return result;
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("ADB operation cancelled; previously submitted commands cannot be undone");
        }
    }

    private void renewReadDeadline() {
        readDeadline = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private interface ReadPulse {
        void beforeRead() throws IOException;
    }

    private final class ShellControl {
        private final OutputStream output;
        private final AtomicBoolean stop;
        private final Runnable beforeStop;
        private final ShellOutputHandler handler;
        private int remoteId;
        private boolean sent;
        private long deadline;

        ShellControl(OutputStream output, AtomicBoolean stop, Runnable beforeStop, ShellOutputHandler handler) {
            this.output = output;
            this.stop = stop;
            this.beforeStop = java.util.Objects.requireNonNull(beforeStop);
            this.handler = handler;
        }

        void beforeRead() throws IOException {
            if (remoteId != 0 && !sent && (stop == null || !stop.get())) {
                handler.onIdle();
            }
            boolean consumerFailed = handler instanceof ShellV2Output && ((ShellV2Output)handler).consumerFailed();
            boolean exited = handler instanceof ShellV2Output && ((ShellV2Output)handler).exitConfirmed();
            if ((stop != null && stop.get() || consumerFailed) && !exited && remoteId != 0 && !sent) {
                beforeStop.run();
                // shell-v2 channel 0=stdin, LE32 length=5, then a fixed owned-process control line.
                sendPacket(output, COMMAND_WRTE, 1, remoteId,
                        new byte[] {0, 5, 0, 0, 0, 'S', 'T', 'O', 'P', '\n'});
                sent = true;
                deadline = nanoTime.getAsLong() + ProjectionLimits.STOP_TIMEOUT_NANOS;
                renewReadDeadline();
                log.accept("CAPTURE_STOP_STDIN_SENT; waiting for owned shell-v2 EXIT, no global kill");
            }
            if (sent && nanoTime.getAsLong() >= deadline) {
                throw new IOException("CAPTURE_STOP_EXIT_UNCONFIRMED");
            }
        }
    }

    private static int checksum(byte[] bytes) {
        int result = 0;
        for (byte value : bytes) {
            result = (result + (value & 0xff)) & 0x7fffffff;
        }
        return result;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static String commandName(int command) {
        byte[] bytes = new byte[] {
                (byte) command,
                (byte) (command >>> 8),
                (byte) (command >>> 16),
                (byte) (command >>> 24)
        };
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * Receives a chunk from an ADB shell session.
     *
     * @author Codex
     * @date 2026/10/09
     */
    public interface ShellOutputHandler {

        /**
         * Handles one raw shell output chunk.
         *
         * @param data output bytes
         * @param length valid byte count
         * @throws IOException when the consumer cannot accept data
         */
        void onOutput(byte[] data, int length) throws IOException;

        /** Maintain the owned video session without a second reader or fabricated video. */
        default void onIdle() throws IOException {
        }
    }

    private static final class Packet {

        private final int command;
        private final int arg0;
        private final int arg1;
        private final byte[] payload;

        private Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }
}
