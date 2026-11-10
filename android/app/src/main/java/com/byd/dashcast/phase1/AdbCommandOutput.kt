package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Preserves shell exit status on the raw exec service, which has no shell-v2 exit frames.
 * @author Codex
 * @date 2026/10/10
 */
object AdbCommandOutput {
    private const val MARKER = "DASHCAST_EXIT_CODE="

    /** @param command internal fixed command; @return status-bearing command with stderr captured */
    @JvmStatic
    fun wrap(command: String?): String {
        if (command.isNullOrEmpty() || command.indexOf('\u0000') >= 0 || command.indexOf('\n') >= 0) {
            throw IllegalArgumentException("A fixed single-line command is required")
        }
        return "(" + command + ") 2>&1; dashcast_status=\$?; printf '\n" + MARKER +
            "%d\n' \"\$dashcast_status\""
    }

    /** @param text complete bounded output; @return stdout only; @throws IOException on nonzero or unknown exit */
    @JvmStatic
    @Throws(IOException::class)
    fun requireSuccess(text: String?): String {
        if (text == null) {
            throw IOException("ADB command output missing")
        }
        val marker = text.lastIndexOf("\n" + MARKER)
        if (marker < 0 || "0" != text.substring(marker + MARKER.length + 1).trim()) {
            throw IOException("ADB 命令未成功完成；不自动重发。output=" + tail(text))
        }
        val output = text.substring(0, marker)
        if (output.contains("Error:") || output.contains("Permission Denial") ||
            output.contains("java.lang.SecurityException")) {
            throw IOException("ADB 原厂服务调用报告错误；不自动重发。output=" + tail(output))
        }
        return output
    }

    private fun tail(text: String): String = text.substring(maxOf(0, text.length - 1024))
}
