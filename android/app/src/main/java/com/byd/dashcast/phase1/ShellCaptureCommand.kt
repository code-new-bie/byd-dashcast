package com.byd.dashcast.phase1

/**
 * Fixed shell entry point with safely quoted, installed APK path; no arbitrary command arguments.
 * @author Codex
 * @date 2026/10/12
 */
internal object ShellCaptureCommand {
    @JvmStatic
    fun create(apk: String?, seconds: Int): String = create(apk, seconds, false)

    @JvmStatic
    fun create(apk: String?, seconds: Int, target: ProjectionTarget?): String {
        if (target == null) {
            throw IllegalArgumentException("Projection target required")
        }
        val targetArguments = target.shellArguments()
        return create(apk, seconds, false) + (if (targetArguments.isEmpty()) "" else " $targetArguments")
    }

    @JvmStatic
    fun continuous(apk: String?, seconds: Int): String {
        ContinuousSessionPolicy.requireCaptureSeconds(seconds)
        return create(apk, seconds, true)
    }

    @JvmStatic
    fun continuous(apk: String?, seconds: Int, options: CaptureOptions?): String {
        if (options == null) {
            throw IllegalArgumentException("Explicit capture selection required")
        }
        return continuous(apk, seconds) + " " + options.shellArguments()
    }

    @JvmStatic
    fun continuous(apk: String?, seconds: Int, options: CaptureOptions?, target: ProjectionTarget?): String {
        if (options == null || target == null) {
            throw IllegalArgumentException("Capture options and projection target are required")
        }
        val targetArguments = target.shellArguments()
        return continuous(apk, seconds) + (if (targetArguments.isEmpty()) "" else " $targetArguments") +
            " " + options.shellArguments()
    }

    private fun create(apk: String?, seconds: Int, continuous: Boolean): String {
        val validDuration = if (continuous) {
            seconds == 0 || (seconds > 0 && seconds <= ProjectionLimits.MAX_SECONDS)
        } else {
            seconds > 0 && seconds <= 10
        }
        if (!validDuration || apk == null || !apk.startsWith("/data/app/") || !apk.endsWith(".apk") ||
            apk.length > 1024 || apk.indexOf('\u0000') >= 0 || apk.indexOf('\n') >= 0 || apk.indexOf('\r') >= 0) {
            throw IllegalArgumentException("Expected installed APK and explicit live/timed capture profile")
        }
        return "CLASSPATH='" + apk.replace("'", "'\\''") +
            "' /system/bin/app_process /system/bin com.byd.dashcast.phase1.ShellScreenCapture " + seconds +
            (if (continuous) " live" else "")
    }
}
