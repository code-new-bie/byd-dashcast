package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Streaming, bounded parser for live registered receivers; never mistakes broadcast history for a live receiver.
 * @author Codex
 * @date 2026/10/10
 */
class NavigationReceiverProbe : AdbShellClient.ShellOutputHandler {
    private val line = StringBuilder(256)
    private var registeredSection = false
    private var targetReceiver = false
    private var receiverBlock = false
    private var found = false
    private var overflow = false

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid receiver dump chunk")
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

    /** @return verified live package/action match; EOF and arbitrary chunk boundaries supported */
    fun isRegistered(): Boolean {
        consumeLine()
        return found
    }

    private fun consumeLine() {
        val text = if (overflow) "" else line.toString().trim()
        line.setLength(0)
        overflow = false
        if (text.startsWith("Registered Receivers:")) {
            registeredSection = true
            receiverBlock = false
        } else if (text.startsWith("Receiver Resolver Table:") || text.startsWith("Historical broadcasts") ||
            text.startsWith("Sticky broadcasts") || text.startsWith("Active broadcasts")) {
            registeredSection = false
            receiverBlock = false
        } else if (registeredSection && text.startsWith("* ReceiverList{")) {
            receiverBlock = true
            targetReceiver = text.contains(":" + NavigationUsbProtocol.PACKAGE + "/") ||
                text.contains(" " + NavigationUsbProtocol.PACKAGE + "/")
        } else if (registeredSection && receiverBlock && text.startsWith("app=")) {
            targetReceiver = text.contains(":" + NavigationUsbProtocol.PACKAGE + "/")
        } else if (registeredSection && receiverBlock && targetReceiver &&
            text == "Action: \"" + NavigationUsbProtocol.ACTION + "\"") {
            found = true
        }
    }

    private companion object {
        const val MAX_LINE_CHARS = 8192
    }
}
