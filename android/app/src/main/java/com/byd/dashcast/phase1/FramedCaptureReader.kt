package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects

/**
 * Incremental DCS1 decoder. No video recording, aggregate buffer or unbounded queue.
 * @author Codex
 * @date 2026/10/13
 */
internal class FramedCaptureReader @JvmOverloads constructor(
    observer: VideoPatternProbe.Observer?,
    endHandler: Runnable? = Runnable {},
    private val unlimited: Boolean = false,
) : AdbShellClient.ShellOutputHandler {
    private val observer: VideoPatternProbe.Observer = Objects.requireNonNull(observer)!!
    private val endHandler: Runnable = Objects.requireNonNull(endHandler)!!
    private val header = ByteArray(CaptureFrameProtocol.HEADER_BYTES)
    private var headerUsed = 0
    private var frame: ByteArray? = null
    private var frameUsed = 0
    private var pts = 0L
    private var key = false
    private var lastPts = -1L
    private var frames = 0L
    private var bytes = 0L
    private var failed = false
    private var finished = false
    private var ended = false

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid DCS1 output chunk")
        }
        if (finished || failed || (ended && length > 0)) {
            throw IOException("DCS1_CLOSED_OR_BYTE_LIMIT")
        }
        if (!unlimited && length > ProjectionLimits.MAX_CAPTURE_BYTES - bytes) {
            failed = true
            throw IOException("DCS1_BYTE_LIMIT")
        }
        bytes = ContinuousSessionPolicy.add(bytes, length.toLong())
        try {
            var offset = 0
            while (offset < length) {
                if (ended) {
                    throw IOException("DCS1_DATA_AFTER_END")
                }
                val current = frame
                if (current == null) {
                    val count = minOf(length - offset, header.size - headerUsed)
                    System.arraycopy(data, offset, header, headerUsed, count)
                    offset += count
                    headerUsed += count
                    if (headerUsed == header.size) {
                        beginFrame()
                    }
                } else {
                    val count = minOf(length - offset, current.size - frameUsed)
                    System.arraycopy(data, offset, current, frameUsed, count)
                    offset += count
                    frameUsed += count
                    if (frameUsed == current.size) {
                        observer.frame(current, pts, key)
                        lastPts = pts
                        frames = ContinuousSessionPolicy.add(frames, 1)
                        frame = null
                        frameUsed = 0
                        headerUsed = 0
                    }
                }
            }
        } catch (error: IOException) {
            failed = true
            throw error
        }
    }

    @Throws(IOException::class)
    private fun beginFrame() {
        val values = CaptureFrameProtocol.parse(header)
        val size = values.getInt()
        pts = values.getLong()
        val flags = values.getInt()
        key = flags == 1
        if (flags == 3) {
            if (pts != maxOf(0L, lastPts)) {
                throw IOException("DCS1_IDLE_PTS_MISMATCH")
            }
            headerUsed = 0
            observer.tick()
            return
        }
        if (flags == 2) {
            if (pts != maxOf(0L, lastPts)) {
                throw IOException("DCS1_END_PTS_MISMATCH")
            }
            ended = true
            headerUsed = 0
            // End is emitted BEFORE shell display/codec teardown, so native STOP cannot
            // race the instrument's unchanged video-idle timeout during Android cleanup.
            endHandler.run()
            return
        }
        if ((!unlimited && frames >= ProjectionLimits.MAX_CAPTURE_FRAMES) || pts <= lastPts) {
            throw IOException("DCS1_FRAME_COUNT_OR_NON_MONOTONIC_ENCODER_PTS")
        }
        frame = ByteArray(size)
    }

    /** Only after successful shell EXIT. User stop may legitimately precede the first picture. */
    @Throws(IOException::class)
    fun finish(stopped: Boolean) {
        if (failed || !ended || frame != null || headerUsed != 0 || (!stopped && frames < 2)) {
            throw IOException("DCS1_INCOMPLETE_CAPTURE_OR_FEWER_THAN_TWO_PICTURES")
        }
        finished = true
    }

    fun statistics(): String =
        "DCS1 capture_frames=$frames bytes=$bytes timestamps=encoder_PTS; NOT instrument decoded frames"

    @Throws(IOException::class)
    override fun onIdle() {
        if (!failed && !finished && !ended) {
            observer.tick()
        }
    }
}
