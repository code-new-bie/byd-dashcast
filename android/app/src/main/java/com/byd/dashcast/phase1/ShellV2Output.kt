package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Objects
import java.util.function.Consumer

/**
 * Decodes raw ADB shell-v2 stdout/stderr/exit frames without mixing stderr or exit bytes into video.
 * Optional diagnostics keep at most 4096 stderr bytes, separate from binary stdout.
 * A clean transport close alone is not command success.
 *
 * @author Codex
 * @date 2026/10/11
 */
internal class ShellV2Output @JvmOverloads constructor(
    stdout: AdbShellClient.ShellOutputHandler?,
    diagnostics: Consumer<String>? = Consumer {},
    private val drainConsumerFailure: Boolean = false,
) : AdbShellClient.ShellOutputHandler {
    private val stdout: AdbShellClient.ShellOutputHandler = Objects.requireNonNull(stdout)!!
    private val diagnostics: Consumer<String> = Objects.requireNonNull(diagnostics)!!
    private val stderr = ByteArrayOutputStream()
    private var stderrTail = ByteArray(0)
    private val header = ByteArray(5)
    private var headerBytes = 0
    private var channel = 0
    private var remaining = 0
    private var stderrBytes = 0
    private var exitCode: Int? = null
    private var failed = false
    private var diagnosticsReported = false
    private var consumerFailure: IOException? = null

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid shell-v2 output chunk")
        }
        try {
            var offset = 0
            while (offset < length) {
                if (failed || exitCode != null) {
                    throw IOException("Unexpected data after shell-v2 failure/EXIT")
                }
                if (headerBytes < header.size) {
                    header[headerBytes++] = data[offset++]
                    if (headerBytes == header.size) {
                        readHeader()
                    }
                    continue
                }
                val count = minOf(remaining, length - offset)
                if (channel == 1 && consumerFailure == null) {
                    consumeStdout(data.copyOfRange(offset, offset + count))
                } else if (channel == 2) {
                    stderrBytes = minOf(stderrBytes.toLong() + count, Int.MAX_VALUE.toLong()).toInt()
                    val keep = minOf(count, DIAGNOSTIC_BYTES - stderr.size())
                    stderr.write(data, offset, keep)
                    keepDiagnosticTail(data, offset, count)
                } else if (channel == 3) {
                    exitCode = data[offset].toInt() and 255
                }
                offset += count
                remaining -= count
                if (remaining == 0) {
                    headerBytes = 0
                }
            }
        } catch (error: IOException) {
            failed = true
            throw error
        }
    }

    @Throws(IOException::class)
    private fun consumeStdout(chunk: ByteArray) {
        try {
            stdout.onOutput(chunk, chunk.size)
        } catch (error: IOException) {
            consumerFailed(error, "Capture consumer failed")
        } catch (error: RuntimeException) {
            consumerFailed(error, "Capture consumer failed")
        }
        // On a recorded failure the ADB control loop sends owned stdin STOP before its next read, then drains
        // only for the existing finite stop budget. No further video reaches the sink.
    }

    @Throws(IOException::class)
    override fun onIdle() {
        if (exitCode != null || consumerFailure != null || failed) {
            return
        }
        try {
            stdout.onIdle()
        } catch (error: IOException) {
            consumerFailed(error, "Capture idle consumer failed")
        } catch (error: RuntimeException) {
            consumerFailed(error, "Capture idle consumer failed")
        }
    }

    /** Rethrows unless failures are drained; otherwise records the first one for the caller. */
    @Throws(IOException::class)
    private fun consumerFailed(error: Exception, wrapping: String) {
        if (!drainConsumerFailure) {
            throw error
        }
        consumerFailure = error as? IOException ?: IOException(wrapping, error)
    }

    @Throws(IOException::class)
    private fun readHeader() {
        channel = header[0].toInt() and 255
        var length = 0L
        for (index in 4 downTo 1) {
            length = (length shl 8) or (header[index].toLong() and 255)
        }
        if (channel < 1 || channel > 3 || length > MAX_FRAME_BYTES || (channel == 3 && length != 1L)) {
            throw IOException("Invalid shell-v2 channel/frame length")
        }
        remaining = length.toInt()
        if (remaining == 0) {
            headerBytes = 0
        }
    }

    @Throws(IOException::class)
    fun requireSuccess() {
        reportDiagnostics()
        consumerFailure?.let { throw it }
        val exit = exitCode
        if (failed || headerBytes != 0 || remaining != 0 || exit == null || exit != 0) {
            throw IOException("ADB capture not successfully completed: shell-v2 EXIT=" +
                (exit?.toString() ?: "UNKNOWN") + " stderr_bytes=" + stderrBytes)
        }
    }

    fun reportDiagnostics() {
        if (diagnosticsReported) {
            return
        }
        diagnosticsReported = true
        if (stderr.size() > 0) {
            val first = stderr.toByteArray()
            val text = if (stderrBytes <= DIAGNOSTIC_BYTES) {
                String(first, Charsets.UTF_8)
            } else {
                String(first, 0, DIAGNOSTIC_TAIL_BYTES, Charsets.UTF_8) +
                    "\n[stderr middle truncated]\n" + String(stderrTail, Charsets.UTF_8)
            }
            diagnostics.accept("ADB_CAPTURE_STDERR $text")
        }
    }

    private fun keepDiagnosticTail(data: ByteArray, offset: Int, count: Int) {
        val incoming = minOf(count, DIAGNOSTIC_TAIL_BYTES)
        val prior = minOf(stderrTail.size, DIAGNOSTIC_TAIL_BYTES - incoming)
        val tail = ByteArray(prior + incoming)
        System.arraycopy(stderrTail, stderrTail.size - prior, tail, 0, prior)
        System.arraycopy(data, offset + count - incoming, tail, prior, incoming)
        stderrTail = tail
    }

    /** A nonzero status is a failed command, but a complete EXIT still proves that the child ended. */
    fun exitConfirmed(): Boolean = !failed && headerBytes == 0 && remaining == 0 && exitCode != null

    fun consumerFailed(): Boolean = consumerFailure != null

    fun preserveConsumerFailure(transportFailure: IOException): IOException {
        val failure = consumerFailure
        if (failure == null || failure === transportFailure) {
            return transportFailure
        }
        failure.addSuppressed(transportFailure)
        return failure
    }

    fun completionStatus(): String = "ADB_CAPTURE_COMPLETION exit=" + (exitCode?.toString() ?: "UNKNOWN") +
        " exit_confirmed=" + exitConfirmed() + " consumer_failed=" + consumerFailed()

    fun stderrBytes(): Int = stderrBytes

    private companion object {
        const val MAX_FRAME_BYTES = 1024 * 1024
        const val DIAGNOSTIC_BYTES = 4096
        const val DIAGNOSTIC_TAIL_BYTES = DIAGNOSTIC_BYTES / 2
    }
}
