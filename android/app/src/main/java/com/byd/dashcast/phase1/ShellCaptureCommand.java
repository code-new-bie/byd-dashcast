package com.byd.dashcast.phase1;

/** Fixed shell entry point with safely quoted, installed APK path; no arbitrary command arguments.
 * @author Codex
 * @date 2026/10/12
 */
final class ShellCaptureCommand {
    private ShellCaptureCommand() {
    }

    static String create(String apk, int seconds) {
        return create(apk, seconds, false);
    }

    static String create(String apk, int seconds, ProjectionTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Projection target required");
        }
        String targetArguments = target.shellArguments();
        return create(apk, seconds, false) + (targetArguments.isEmpty() ? "" : " " + targetArguments);
    }

    static String continuous(String apk, int seconds) {
        ContinuousSessionPolicy.requireCaptureSeconds(seconds);
        return create(apk, seconds, true);
    }

    static String continuous(String apk, int seconds, CaptureOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Explicit capture selection required");
        }
        return continuous(apk, seconds) + " " + options.shellArguments();
    }

    static String continuous(String apk, int seconds, CaptureOptions options, ProjectionTarget target) {
        if (options == null || target == null) {
            throw new IllegalArgumentException("Capture options and projection target are required");
        }
        String targetArguments = target.shellArguments();
        return continuous(apk, seconds) + (targetArguments.isEmpty() ? "" : " " + targetArguments)
                + " " + options.shellArguments();
    }

    private static String create(String apk, int seconds, boolean continuous) {
        boolean validDuration = continuous ? seconds == 0 || seconds > 0 && seconds <= ProjectionLimits.MAX_SECONDS
                : seconds > 0 && seconds <= 10;
        if (!validDuration
                || apk == null || !apk.startsWith("/data/app/")
                || !apk.endsWith(".apk") || apk.length() > 1024 || apk.indexOf('\0') >= 0
                || apk.indexOf('\n') >= 0 || apk.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Expected installed APK and explicit live/timed capture profile");
        }
        return "CLASSPATH='" + apk.replace("'", "'\\''")
                + "' /system/bin/app_process /system/bin com.byd.dashcast.phase1.ShellScreenCapture " + seconds
                + (continuous ? " live" : "");
    }
}
