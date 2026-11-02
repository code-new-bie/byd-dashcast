package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Streaming, bounded parser for live registered receivers; never mistakes broadcast history for a live receiver.
 * @author Codex
 * @date 2026/10/10
 */
public final class NavigationReceiverProbe implements AdbShellClient.ShellOutputHandler {
    private static final int MAX_LINE_CHARS = 8192;
    private final StringBuilder line = new StringBuilder(256);
    private boolean registeredSection;
    private boolean targetReceiver;
    private boolean receiverBlock;
    private boolean found;
    private boolean overflow;

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid receiver dump chunk");
        }
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xff;
            if (value == '\n') {
                consumeLine();
            } else if (line.length() < MAX_LINE_CHARS) {
                line.append((char)value);
            } else {
                overflow = true;
            }
        }
    }

    /** @return verified live package/action match; EOF and arbitrary chunk boundaries supported */
    public boolean isRegistered() {
        consumeLine();
        return found;
    }

    private void consumeLine() {
        String text = overflow ? "" : line.toString().trim();
        line.setLength(0);
        overflow = false;
        if (text.startsWith("Registered Receivers:")) {
            registeredSection = true;
            receiverBlock = false;
        } else if (text.startsWith("Receiver Resolver Table:") || text.startsWith("Historical broadcasts")
                || text.startsWith("Sticky broadcasts") || text.startsWith("Active broadcasts")) {
            registeredSection = false;
            receiverBlock = false;
        } else if (registeredSection && text.startsWith("* ReceiverList{")) {
            receiverBlock = true;
            targetReceiver = text.contains(":" + NavigationUsbProtocol.PACKAGE + "/")
                    || text.contains(" " + NavigationUsbProtocol.PACKAGE + "/");
        } else if (registeredSection && receiverBlock && text.startsWith("app=")) {
            targetReceiver = text.contains(":" + NavigationUsbProtocol.PACKAGE + "/");
        } else if (registeredSection && receiverBlock && targetReceiver
                && text.equals("Action: \"" + NavigationUsbProtocol.ACTION + "\"")) {
            found = true;
        }
    }
}
