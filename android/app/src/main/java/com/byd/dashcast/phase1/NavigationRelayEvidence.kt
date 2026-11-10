package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Objects
import java.util.regex.Pattern

/**
 * Bounded, privacy-filtered evidence from the OEM log, not an instrument acknowledgement.
 * Keeps only fixed event names: other road names and arbitrary log messages are discarded.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal class NavigationRelayEvidence(command: InstrumentUsbCommand?, startMillis: Long) :
    AdbShellClient.ShellOutputHandler {
    private val command: InstrumentUsbCommand = Objects.requireNonNull(command, "Fixed request required")!!
    private val startMillis: Long
    private val line = ByteArrayOutputStream(256)
    private val events = StringBuilder(512)
    private var overflow = false
    private var inputObserved = false
    private var setAttemptObserved = false
    private var permissionErrorObserved = false
    private var eventCount = 0
    private var discardedOversizeLines = 0
    private var commandCompletedSuccessfully: Boolean? = null

    init {
        if (startMillis < 0 || startMillis > Long.MAX_VALUE - WINDOW_MILLIS) {
            throw IllegalArgumentException("Invalid request timestamp")
        }
        this.startMillis = startMillis
    }

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid log output chunk")
        }
        for (index in 0 until length) {
            val value = data[index].toInt() and 0xff
            if (value == '\n'.code) {
                consumeLine()
            } else if (line.size() < MAX_LINE_BYTES) {
                line.write(value)
            } else {
                overflow = true
            }
        }
    }

    /** @return a bounded, sanitized snapshot; no raw log or navigation text is retained */
    fun snapshot(): String {
        consumeLine()
        return "request=" + command.commandText() + "\nwindow_start_epoch_ms=" + startMillis +
            "\nfresh_oem_next_road_input_observed=" + inputObserved +
            "\nfresh_oem_set_attempt_observed=" + setAttemptObserved +
            "\npermission_error_in_window=" + permissionErrorObserved +
            "\ndiscarded_oversize_lines=" + discardedOversizeLines +
            "\nlogcat_command_success=" + (commandCompletedSuccessfully?.toString() ?: "UNKNOWN") +
            "\nNot observed = UNKNOWN (logging may be suppressed or overwritten)." +
            "\nOEM set attempt is NOT a CAN delivery, PPS receipt or USB role acknowledgement." +
            "\nPermission errors are time-correlated only, not proven to originate from this request.\n" +
            events
    }

    fun summary(): String {
        consumeLine()
        return "NAV_PROCESSING_EVIDENCE input=" + inputObserved + " set_attempt=" + setAttemptObserved +
            " permission_error_in_window=" + permissionErrorObserved +
            "; no CAN/QNX/role acknowledgement; absent logs mean UNKNOWN"
    }

    fun hasProcessingEvidence(): Boolean {
        consumeLine()
        return inputObserved || setAttemptObserved || permissionErrorObserved
    }

    private fun consumeLine() {
        if (overflow) {
            discardedOversizeLines++
        } else if (line.size() > 0) {
            parseLine(String(line.toByteArray(), Charsets.UTF_8))
        }
        line.reset()
        overflow = false
    }

    private fun parseLine(text: String) {
        if (text.startsWith("DASHCAST_EXIT_CODE=")) {
            commandCompletedSuccessfully = "DASHCAST_EXIT_CODE=0" == text.trim()
            return
        }
        val match = LOG_LINE.matcher(text)
        if (!match.matches()) {
            return
        }
        val timestamp = try {
            java.lang.Long.parseLong(match.group(1)!!) * 1000 + Integer.parseInt(match.group(2)!!.substring(0, 3))
        } catch (error: NumberFormatException) {
            return
        }
        if (timestamp < startMillis || timestamp > startMillis + WINDOW_MILLIS) {
            return
        }
        val tag = match.group(6)!!.trim()
        val message = match.group(7)!!.trim()
        var event: String? = null
        if ("AmapService" == tag) {
            if (message == "下一道路名: " + command.commandText() || message == "下一道路名:" + command.commandText()) {
                inputObserved = true
                event = "OEM_NEXT_ROAD_INPUT"
            } else if (message == "发送独立仪表下个道路名称:" + command.commandText() ||
                message == "发送独立仪表下个道路名称: " + command.commandText()) {
                setAttemptObserved = true
                event = "OEM_NEXT_ROAD_SET_ATTEMPT"
            }
        }
        val allowedTag = "AmapService" == tag || "BYDAutoService" == tag || "BYDAutoDeviceManager" == tag
        if (allowedTag && "WEF".contains(match.group(5)!!) &&
            (message.contains("setBuffer") || message.contains("BYDAuto")) &&
            (message.contains("permission deny") || message.contains("SecurityException"))) {
            permissionErrorObserved = true
            event = "OEM_PERMISSION_ERROR_IN_WINDOW"
        }
        if (event != null && eventCount < MAX_EVENTS) {
            events.append(timestamp).append(' ').append(event).append(" pid=").append(match.group(3)).append('\n')
            eventCount++
        }
    }

    companion object {
        const val LOG_COMMAND = "logcat -b main -b system -d -v threadtime -v epoch -t 256 " +
            "AmapService:D BYDAutoService:W BYDAutoDeviceManager:W '*:S'"
        private const val MAX_LINE_BYTES = 4096
        private const val MAX_EVENTS = 32
        private const val WINDOW_MILLIS = 60_000L
        private val LOG_LINE = Pattern.compile(
            "^\\s*([0-9]{1,12})\\.([0-9]{3,9})\\s+([0-9]+)\\s+([0-9]+)\\s+([VDIWEF])\\s+([^:]+):\\s*(.*)$")
    }
}
