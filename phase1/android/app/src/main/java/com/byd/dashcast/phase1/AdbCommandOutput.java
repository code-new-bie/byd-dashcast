package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Preserves shell exit status on the raw exec service, which has no shell-v2 exit frames.
 * @author Codex
 * @date 2026/10/10
 */
public final class AdbCommandOutput {
    private static final String MARKER = "DASHCAST_EXIT_CODE=";

    private AdbCommandOutput() {}

    /** @param command internal fixed command; @return status-bearing command with stderr captured */
    public static String wrap(String command) {
        if (command == null || command.isEmpty() || command.indexOf('\0') >= 0 || command.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A fixed single-line command is required");
        }
        return "(" + command + ") 2>&1; dashcast_status=$?; printf '\n" + MARKER
                + "%d\n' \"$dashcast_status\"";
    }

    /** @param text complete bounded output; @return stdout only; @throws IOException on nonzero or unknown exit */
    public static String requireSuccess(String text) throws IOException {
        if (text == null) {
            throw new IOException("ADB command output missing");
        }
        int marker = text.lastIndexOf("\n" + MARKER);
        if (marker < 0 || !"0".equals(text.substring(marker + MARKER.length() + 1).trim())) {
            throw new IOException("ADB 命令未成功完成；不自动重发。output=" + tail(text));
        }
        String output = text.substring(0, marker);
        if (output.contains("Error:") || output.contains("Permission Denial")
                || output.contains("java.lang.SecurityException")) {
            throw new IOException("ADB 原厂服务调用报告错误；不自动重发。output=" + tail(output));
        }
        return output;
    }

    private static String tail(String text) {
        return text.substring(Math.max(0, text.length() - 1024));
    }
}
