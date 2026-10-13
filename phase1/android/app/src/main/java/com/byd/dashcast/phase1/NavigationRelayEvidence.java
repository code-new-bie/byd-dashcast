package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, privacy-filtered evidence from the OEM log, not an instrument acknowledgement.
 * Keeps only fixed event names: other road names and arbitrary log messages are discarded.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class NavigationRelayEvidence implements AdbShellClient.ShellOutputHandler {
    static final String LOG_COMMAND = "logcat -b main -b system -d -v threadtime -v epoch -t 256 "
            + "AmapService:D BYDAutoService:W BYDAutoDeviceManager:W '*:S'";
    private static final int MAX_LINE_BYTES = 4096;
    private static final int MAX_EVENTS = 32;
    private static final long WINDOW_MILLIS = 60_000;
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*([0-9]{1,12})\\.([0-9]{3,9})\\s+([0-9]+)\\s+([0-9]+)\\s+([VDIWEF])\\s+([^:]+):\\s*(.*)$");
    private final InstrumentUsbCommand command;
    private final long startMillis;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);
    private final StringBuilder events = new StringBuilder(512);
    private boolean overflow;
    private boolean inputObserved;
    private boolean setAttemptObserved;
    private boolean permissionErrorObserved;
    private int eventCount;
    private int discardedOversizeLines;
    private Boolean commandCompletedSuccessfully;

    NavigationRelayEvidence(InstrumentUsbCommand command, long startMillis) {
        this.command = Objects.requireNonNull(command, "Fixed request required");
        if (startMillis < 0 || startMillis > Long.MAX_VALUE - WINDOW_MILLIS) {
            throw new IllegalArgumentException("Invalid request timestamp");
        }
        this.startMillis = startMillis;
    }

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid log output chunk");
        }
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xff;
            if (value == '\n') {
                consumeLine();
            } else if (line.size() < MAX_LINE_BYTES) {
                line.write(value);
            } else {
                overflow = true;
            }
        }
    }

    /** @return a bounded, sanitized snapshot; no raw log or navigation text is retained */
    String snapshot() {
        consumeLine();
        return "request=" + command.commandText() + "\nwindow_start_epoch_ms=" + startMillis
                + "\nfresh_oem_next_road_input_observed=" + inputObserved
                + "\nfresh_oem_set_attempt_observed=" + setAttemptObserved
                + "\npermission_error_in_window=" + permissionErrorObserved
                + "\ndiscarded_oversize_lines=" + discardedOversizeLines
                + "\nlogcat_command_success=" + (commandCompletedSuccessfully == null ? "UNKNOWN" : commandCompletedSuccessfully)
                + "\nNot observed = UNKNOWN (logging may be suppressed or overwritten)."
                + "\nOEM set attempt is NOT a CAN delivery, PPS receipt or USB role acknowledgement."
                + "\nPermission errors are time-correlated only, not proven to originate from this request.\n"
                + events;
    }

    String summary() {
        consumeLine();
        return "NAV_PROCESSING_EVIDENCE input=" + inputObserved + " set_attempt=" + setAttemptObserved
                + " permission_error_in_window=" + permissionErrorObserved
                + "; no CAN/QNX/role acknowledgement; absent logs mean UNKNOWN";
    }

    boolean hasProcessingEvidence() {
        consumeLine();
        return inputObserved || setAttemptObserved || permissionErrorObserved;
    }

    private void consumeLine() {
        if (overflow) {
            discardedOversizeLines++;
        } else if (line.size() > 0) {
            parseLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
        }
        line.reset();
        overflow = false;
    }

    private void parseLine(String text) {
        if (text.startsWith("DASHCAST_EXIT_CODE=")) {
            commandCompletedSuccessfully = "DASHCAST_EXIT_CODE=0".equals(text.trim());
            return;
        }
        Matcher match = LOG_LINE.matcher(text);
        if (!match.matches()) {
            return;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(match.group(1)) * 1000
                    + Integer.parseInt(match.group(2).substring(0, 3));
        } catch (NumberFormatException error) {
            return;
        }
        if (timestamp < startMillis || timestamp > startMillis + WINDOW_MILLIS) {
            return;
        }
        String tag = match.group(6).trim();
        String message = match.group(7).trim();
        String event = null;
        if ("AmapService".equals(tag)) {
            if (message.equals("下一道路名: " + command.commandText())
                    || message.equals("下一道路名:" + command.commandText())) {
                inputObserved = true;
                event = "OEM_NEXT_ROAD_INPUT";
            } else if (message.equals("发送独立仪表下个道路名称:" + command.commandText())
                    || message.equals("发送独立仪表下个道路名称: " + command.commandText())) {
                setAttemptObserved = true;
                event = "OEM_NEXT_ROAD_SET_ATTEMPT";
            }
        }
        boolean allowedTag = "AmapService".equals(tag) || "BYDAutoService".equals(tag)
                || "BYDAutoDeviceManager".equals(tag);
        if (allowedTag && "WEF".contains(match.group(5))
                && (message.contains("setBuffer") || message.contains("BYDAuto"))
                && (message.contains("permission deny") || message.contains("SecurityException"))) {
            permissionErrorObserved = true;
            event = "OEM_PERMISSION_ERROR_IN_WINDOW";
        }
        if (event != null && eventCount < MAX_EVENTS) {
            events.append(timestamp).append(' ').append(event).append(" pid=")
                    .append(match.group(3)).append('\n');
            eventCount++;
        }
    }
}
