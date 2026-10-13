package com.byd.dashcast.phase1.ncm;

import java.util.ArrayDeque;

/**
 * Bounded, synchronized diagnostic log for the vendored NCM core, usable in JVM tests.
 * @author Codex
 * @date 2026/10/10
 */
public final class LogHub {
    private static final int CAPACITY = 800;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(CAPACITY);

    private LogHub() {
    }

    public static void d(String tag, String message) {
        // Packet-level debug is disabled: no frame payloads or token values are logged.
    }

    public static void i(String tag, String message) {
        write("I", tag, message);
    }

    public static void w(String tag, String message) {
        write("W", tag, message);
    }

    public static void e(String tag, String message) {
        write("E", tag, message);
    }

    public static void banner(String title) {
        write("I", "phase", title);
    }

    public static void throwable(String tag, String message, Throwable error) {
        write("E", tag, message + " | " + error.getClass().getSimpleName()
                + ": " + error.getMessage());
    }

    public static synchronized String snapshot() {
        StringBuilder result = new StringBuilder();
        for (String line : LINES) {
            result.append(line).append('\n');
        }
        return result.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    private static synchronized void write(String level, String tag, String message) {
        if (LINES.size() >= CAPACITY) {
            LINES.removeFirst();
        }
        LINES.addLast(System.currentTimeMillis() + " " + level + "/" + tag + " " + message);
    }
}
