package com.byd.dashcast.phase1;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes raw ADB shell-v2 stdout/stderr/exit frames without mixing stderr or exit bytes into video.
 * Optional diagnostics keep at most 4096 stderr bytes, separate from binary stdout.
 * A clean transport close alone is not command success.
 *
 * @author Codex
 * @date 2026/10/11
 */
final class ShellV2Output implements AdbShellClient.ShellOutputHandler {
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int DIAGNOSTIC_BYTES = 4096;
    private static final int DIAGNOSTIC_TAIL_BYTES = DIAGNOSTIC_BYTES / 2;
    private final AdbShellClient.ShellOutputHandler stdout;
    private final Consumer<String> diagnostics;
    private final boolean drainConsumerFailure;
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private byte[] stderrTail = new byte[0];
    private final byte[] header = new byte[5];
    private int headerBytes;
    private int channel;
    private int remaining;
    private int stderrBytes;
    private Integer exitCode;
    private boolean failed;
    private boolean diagnosticsReported;
    private IOException consumerFailure;

    ShellV2Output(AdbShellClient.ShellOutputHandler stdout) {
        this(stdout, message -> {});
    }

    ShellV2Output(AdbShellClient.ShellOutputHandler stdout, Consumer<String> diagnostics) {
        this(stdout, diagnostics, false);
    }

    ShellV2Output(AdbShellClient.ShellOutputHandler stdout, Consumer<String> diagnostics,
            boolean drainConsumerFailure) {
        this.stdout = Objects.requireNonNull(stdout);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.drainConsumerFailure = drainConsumerFailure;
    }

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid shell-v2 output chunk");
        }
        try {
            int offset = 0;
            while (offset < length) {
                if (failed || exitCode != null) {
                    throw new IOException("Unexpected data after shell-v2 failure/EXIT");
                }
                if (headerBytes < header.length) {
                    header[headerBytes++] = data[offset++];
                    if (headerBytes == header.length) {
                        readHeader();
                    }
                    continue;
                }
                int count = Math.min(remaining, length - offset);
                if (channel == 1 && consumerFailure == null) {
                    byte[] chunk = new byte[count];
                    System.arraycopy(data, offset, chunk, 0, count);
                    consumeStdout(chunk);
                } else if (channel == 2) {
                    stderrBytes = (int)Math.min((long)stderrBytes + count, Integer.MAX_VALUE);
                    int keep = Math.min(count, DIAGNOSTIC_BYTES - stderr.size());
                    stderr.write(data, offset, keep);
                    keepDiagnosticTail(data, offset, count);
                } else if (channel == 3) {
                    exitCode = data[offset] & 255;
                }
                offset += count;
                remaining -= count;
                if (remaining == 0) {
                    headerBytes = 0;
                }
            }
        } catch (IOException error) {
            failed = true;
            throw error;
        }
    }

    private void consumeStdout(byte[] chunk) throws IOException {
        try {
            stdout.onOutput(chunk, chunk.length);
        } catch (IOException | RuntimeException error) {
            if (!drainConsumerFailure) {
                throw error;
            }
            consumerFailure = error instanceof IOException ? (IOException)error
                    : new IOException("Capture consumer failed", error);
            // The ADB control loop sends owned stdin STOP before its next read, then drains
            // only for the existing finite stop budget. No further video reaches the sink.
        }
    }

    @Override
    public void onIdle() throws IOException {
        if (exitCode != null || consumerFailure != null || failed) {
            return;
        }
        try {
            stdout.onIdle();
        } catch (IOException | RuntimeException error) {
            if (!drainConsumerFailure) {
                throw error;
            }
            consumerFailure = error instanceof IOException ? (IOException)error
                    : new IOException("Capture idle consumer failed", error);
        }
    }

    private void readHeader() throws IOException {
        channel = header[0] & 255;
        long length = 0;
        for (int index = 4; index > 0; index--) {
            length = length << 8 | header[index] & 255;
        }
        if (channel < 1 || channel > 3 || length > MAX_FRAME_BYTES || channel == 3 && length != 1) {
            throw new IOException("Invalid shell-v2 channel/frame length");
        }
        remaining = (int)length;
        if (remaining == 0) {
            headerBytes = 0;
        }
    }

    void requireSuccess() throws IOException {
        reportDiagnostics();
        if (consumerFailure != null) {
            throw consumerFailure;
        }
        if (failed || headerBytes != 0 || remaining != 0 || exitCode == null || exitCode != 0) {
            throw new IOException("ADB capture not successfully completed: shell-v2 EXIT="
                    + (exitCode == null ? "UNKNOWN" : exitCode) + " stderr_bytes=" + stderrBytes);
        }
    }

    void reportDiagnostics() {
        if (diagnosticsReported) {
            return;
        }
        diagnosticsReported = true;
        if (stderr.size() > 0) {
            byte[] first = stderr.toByteArray();
            String text = stderrBytes <= DIAGNOSTIC_BYTES ? new String(first, StandardCharsets.UTF_8)
                    : new String(first, 0, DIAGNOSTIC_TAIL_BYTES, StandardCharsets.UTF_8)
                            + "\n[stderr middle truncated]\n" + new String(stderrTail, StandardCharsets.UTF_8);
            diagnostics.accept("ADB_CAPTURE_STDERR " + text);
        }
    }

    private void keepDiagnosticTail(byte[] data, int offset, int count) {
        int incoming = Math.min(count, DIAGNOSTIC_TAIL_BYTES);
        int prior = Math.min(stderrTail.length, DIAGNOSTIC_TAIL_BYTES - incoming);
        byte[] tail = new byte[prior + incoming];
        System.arraycopy(stderrTail, stderrTail.length - prior, tail, 0, prior);
        System.arraycopy(data, offset + count - incoming, tail, prior, incoming);
        stderrTail = tail;
    }

    /** A nonzero status is a failed command, but a complete EXIT still proves that the child ended. */
    boolean exitConfirmed() {
        return !failed && headerBytes == 0 && remaining == 0 && exitCode != null;
    }

    boolean consumerFailed() {
        return consumerFailure != null;
    }

    IOException preserveConsumerFailure(IOException transportFailure) {
        if (consumerFailure == null || consumerFailure == transportFailure) {
            return transportFailure;
        }
        consumerFailure.addSuppressed(transportFailure);
        return consumerFailure;
    }

    String completionStatus() {
        return "ADB_CAPTURE_COMPLETION exit=" + (exitCode == null ? "UNKNOWN" : exitCode)
                + " exit_confirmed=" + exitConfirmed() + " consumer_failed=" + consumerFailed();
    }

    int stderrBytes() {
        return stderrBytes;
    }
}
