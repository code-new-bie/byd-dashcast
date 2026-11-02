package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * A read-only command returning EOF or no matching lines is not evidence of successful execution.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class ReadOnlyProbeResult {
    private ReadOnlyProbeResult() {
    }

    static String requireOutput(String text, boolean truncated) throws IOException {
        if (truncated) {
            throw new IOException("Read-only probe output truncated; result UNKNOWN");
        }
        String output = AdbCommandOutput.requireSuccess(text);
        if (output.trim().isEmpty()) {
            throw new IOException("Read-only probe returned no evidence; result UNKNOWN");
        }
        if (output.trim().startsWith("Can't find service:")
                || output.trim().startsWith("Cannot find service:")) {
            throw new IOException("Requested dumpsys service unavailable; result UNKNOWN");
        }
        return output;
    }
}
