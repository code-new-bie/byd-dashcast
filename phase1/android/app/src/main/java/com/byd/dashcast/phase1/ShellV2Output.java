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
    private final AdbShellClient.ShellOutputHandler stdout;
    private final Consumer<String> diagnostics;
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final byte[] header = new byte[5];
    private int headerBytes;
    private int channel;
    private int remaining;
    private int stderrBytes;
    private Integer exitCode;
    private boolean failed;
    private boolean diagnosticsReported;

    ShellV2Output(AdbShellClient.ShellOutputHandler stdout) {
        this(stdout, message -> {});
    }

    ShellV2Output(AdbShellClient.ShellOutputHandler stdout, Consumer<String> diagnostics) {
        this.stdout = Objects.requireNonNull(stdout);
        this.diagnostics = Objects.requireNonNull(diagnostics);
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
                if (channel == 1) {
                    byte[] chunk = new byte[count];
                    System.arraycopy(data, offset, chunk, 0, count);
                    stdout.onOutput(chunk, count);
                } else if (channel == 2) {
                    stderrBytes = (int)Math.min((long)stderrBytes + count, Integer.MAX_VALUE);
                    int keep = Math.min(count, 4096 - stderr.size());
                    stderr.write(data, offset, keep);
                } else {
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
            diagnostics.accept("ADB_CAPTURE_STDERR " + new String(stderr.toByteArray(), StandardCharsets.UTF_8)
                    + (stderrBytes > stderr.size() ? " [truncated]" : ""));
        }
    }

    int stderrBytes() {
        return stderrBytes;
    }
}
