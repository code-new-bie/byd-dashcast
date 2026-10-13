package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Bounded text collector for read-only ADB capability probes.
 *
 * @author Codex
 * @date 2026/10/09
 */
public final class AdbTextProbe implements AdbShellClient.ShellOutputHandler {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final int maximumBytes;
    private long totalBytes;
    private boolean truncated;

    /**
     * Creates a bounded collector.
     *
     * @param maximumBytes maximum bytes retained in memory
     */
    public AdbTextProbe(int maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    /**
     * Collects one output chunk without exceeding the configured memory bound.
     *
     * @param data output bytes
     * @param length valid byte count
     * @throws IOException when the callback cannot accept data
     */
    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("invalid ADB text output chunk");
        }
        totalBytes += length;
        int remaining = maximumBytes - output.size();
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        int retainedLength = Math.min(length, remaining);
        output.write(data, 0, retainedLength);
        if (retainedLength < length) {
            truncated = true;
        }
    }

    /**
     * Returns the retained UTF-8 text.
     *
     * @return retained text
     */
    public String getText() {
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the total number of bytes received from ADB.
     *
     * @return received byte count
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Indicates whether the retained text was truncated.
     *
     * @return true when the memory bound was reached
     */
    public boolean isTruncated() {
        return truncated;
    }
}
