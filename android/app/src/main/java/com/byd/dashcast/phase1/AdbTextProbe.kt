package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Bounded text collector for read-only ADB capability probes.
 *
 * @author Codex
 * @date 2026/10/09
 */
class AdbTextProbe(private val maximumBytes: Int) : AdbShellClient.ShellOutputHandler {
    private val output = ByteArrayOutputStream()

    /** Total number of bytes received from ADB. */
    var totalBytes = 0L
        private set

    /** True when the memory bound was reached. */
    var isTruncated = false
        private set

    /** The retained UTF-8 text. */
    val text: String
        get() = String(output.toByteArray(), Charsets.UTF_8)

    init {
        if (maximumBytes <= 0) {
            throw IllegalArgumentException("maximumBytes must be positive")
        }
    }

    /**
     * Collects one output chunk without exceeding the configured memory bound.
     *
     * @param data output bytes
     * @param length valid byte count
     * @throws IOException when the callback cannot accept data
     */
    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("invalid ADB text output chunk")
        }
        totalBytes += length
        val remaining = maximumBytes - output.size()
        if (remaining <= 0) {
            isTruncated = true
            return
        }
        val retainedLength = minOf(length, remaining)
        output.write(data, 0, retainedLength)
        if (retainedLength < length) {
            isTruncated = true
        }
    }
}
