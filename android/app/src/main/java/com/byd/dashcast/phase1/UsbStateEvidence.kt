package com.byd.dashcast.phase1

import java.io.IOException
import java.util.regex.Pattern

/**
 * Streaming allowlist of Android USB state flags, without serial numbers or package history.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal class UsbStateEvidence : AdbShellClient.ShellOutputHandler {
    private val line = StringBuilder(128)
    private val selected = StringBuilder(512)
    private var overflow = false
    private var selectedLines = 0
    private var commandCompletedSuccessfully: Boolean? = null

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid USB dump chunk")
        }
        for (index in 0 until length) {
            val value = data[index].toInt() and 0xff
            if (value == '\n'.code) {
                consumeLine()
            } else if (line.length < MAX_LINE_CHARS) {
                line.append(value.toChar())
            } else {
                overflow = true
            }
        }
    }

    fun snapshot(): String {
        consumeLine()
        return "Android USB state flags only; these describe the head-unit, NOT QNX USB role.\n" +
            "dumpsys_command_success=" + (commandCompletedSuccessfully?.toString() ?: "UNKNOWN") + "\n" +
            (if (selectedLines == 0) "No supported state flags observed; UNKNOWN.\n" else selected.toString())
    }

    private fun consumeLine() {
        if (!overflow) {
            if (line.toString().startsWith("DASHCAST_EXIT_CODE=")) {
                commandCompletedSuccessfully = "DASHCAST_EXIT_CODE=0" == line.toString().trim()
            }
            val match = STATE.matcher(line)
            if (selectedLines < MAX_SELECTED_LINES && match.matches()) {
                selected.append(match.group(1)).append('=').append(match.group(2)!!.trim()).append('\n')
                selectedLines++
            }
        }
        line.setLength(0)
        overflow = false
    }

    companion object {
        const val COMMAND = "dumpsys usb"
        private const val MAX_LINE_CHARS = 2048
        private const val MAX_SELECTED_LINES = 48
        private val STATE = Pattern.compile(
            "^\\s*((?:m)?(?:CurrentFunctions(?:Applied)?|Connected|Configured|HostConnected|AdbEnabled)" +
                "|current_functions(?:_applied)?|connected|configured|host_connected|adb_enabled)" +
                "\\s*[:=]\\s*([a-zA-Z0-9_, ]{1,100})\\s*$")
    }
}
