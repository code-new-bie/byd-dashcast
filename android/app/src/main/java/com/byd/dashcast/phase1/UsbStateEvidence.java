package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming allowlist of Android USB state flags, without serial numbers or package history.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class UsbStateEvidence implements AdbShellClient.ShellOutputHandler {
    static final String COMMAND = "dumpsys usb";
    private static final int MAX_LINE_CHARS = 2048;
    private static final int MAX_SELECTED_LINES = 48;
    private static final Pattern STATE = Pattern.compile(
            "^\\s*((?:m)?(?:CurrentFunctions(?:Applied)?|Connected|Configured|HostConnected|AdbEnabled)"
                    + "|current_functions(?:_applied)?|connected|configured|host_connected|adb_enabled)"
                    + "\\s*[:=]\\s*([a-zA-Z0-9_, ]{1,100})\\s*$");
    private final StringBuilder line = new StringBuilder(128);
    private final StringBuilder selected = new StringBuilder(512);
    private boolean overflow;
    private int selectedLines;
    private Boolean commandCompletedSuccessfully;

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid USB dump chunk");
        }
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xff;
            if (value == '\n') {
                consumeLine();
            } else if (line.length() < MAX_LINE_CHARS) {
                line.append((char) value);
            } else {
                overflow = true;
            }
        }
    }

    String snapshot() {
        consumeLine();
        return "Android USB state flags only; these describe the head-unit, NOT QNX USB role.\n"
                + "dumpsys_command_success=" + (commandCompletedSuccessfully == null ? "UNKNOWN" : commandCompletedSuccessfully) + "\n"
                + (selectedLines == 0 ? "No supported state flags observed; UNKNOWN.\n" : selected.toString());
    }

    private void consumeLine() {
        if (!overflow) {
            if (line.toString().startsWith("DASHCAST_EXIT_CODE=")) {
                commandCompletedSuccessfully = "DASHCAST_EXIT_CODE=0".equals(line.toString().trim());
            }
            Matcher match = STATE.matcher(line);
            if (selectedLines < MAX_SELECTED_LINES && match.matches()) {
                selected.append(match.group(1)).append('=').append(match.group(2).trim()).append('\n');
                selectedLines++;
            }
        }
        line.setLength(0);
        overflow = false;
    }
}
