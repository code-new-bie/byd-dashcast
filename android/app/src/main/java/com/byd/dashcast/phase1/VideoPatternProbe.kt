package com.byd.dashcast.phase1

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Paced moving test pattern, hardware AVC only, with output format and measured PTS/frame counts.
 * This does not capture the head-unit screen and does not certify the instrument decoder.
 *
 * @author Codex
 * @date 2026/10/10
 */
class VideoPatternProbe @JvmOverloads constructor(
    cancelled: AtomicBoolean?,
    observer: Observer?,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
) {
    /** Receives progress and encoded complete access units. */
    interface Observer {
        /** @param text bounded diagnostic description */
        fun log(text: String)

        /**
         * @param data Annex-B frame; @param ptsMicros codec PTS; @param keyFrame random access;
         * @throws IOException on storage/network failure
         */
        @Throws(IOException::class)
        fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean)

        /** Same-worker maintenance during bounded encoder/socket waits. */
        @Throws(IOException::class)
        fun tick() {
        }
    }

    private val cancelled: AtomicBoolean = Objects.requireNonNull(cancelled)!!
    private val observer: Observer = Objects.requireNonNull(observer)!!
    private var configuration = ByteArray(0)
    private var outputFrames = 0
    private var firstPts = -1L
    private var lastPts = -1L
    private var lastOutputNanos = 0L
    private var outputEos = false
    private var stage = "select"
    private var used = false

    init {
        // Fixed inspected geometries only: legacy local probe or DCV1 small-window video.
        if (!(width == WIDTH && height == HEIGHT) &&
            !(width == NoAuthVideoProtocol.WIDTH && height == NoAuthVideoProtocol.HEIGHT)) {
            throw IllegalArgumentException("Uninspected encoder geometry")
        }
    }

    /** @param seconds 1 to 60; @throws IOException on codec/output failure */
    @Throws(IOException::class)
    fun run(seconds: Int) {
        if (seconds < 1 || seconds > 60) {
            throw IllegalArgumentException("Duration must be 1..60 seconds")
        }
        if (used) {
            throw IOException("Encoder probe cannot be reused")
        }
        used = true
        var codec: MediaCodec? = null
        var started = false
        var failure: IOException? = null
        try {
            val info = selectHardwareEncoder()
            observer.log("Selected codec=" + info.name + " hardware=" + info.isHardwareAccelerated +
                " input=recordable-Surface/EGL baseline-level4 (no CPU YUV queue)")
            phase("create")
            val created = MediaCodec.createByCodecName(info.name)
            codec = created
            phase("configure")
            created.configure(createFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            phase("create_input_surface")
            SurfacePatternRenderer(created.createInputSurface(), width, height).use { renderer ->
                phase("start")
                created.start()
                started = true
                val start = System.nanoTime()
                encode(created, renderer, seconds)
                logOutput(start)
            }
        } catch (error: IOException) {
            failure = error
            observer.log("ENCODER_FAILED phase=" + stage + " reason=" + error.message)
            throw error
        } catch (error: RuntimeException) {
            if (error is MediaCodec.CodecException) {
                observer.log("ENCODER_CODEC_ERROR phase=" + stage + " code=0x" + Integer.toHexString(error.errorCode) +
                    " diagnostic=" + error.diagnosticInfo +
                    " recoverable=" + error.isRecoverable + " transient=" + error.isTransient)
            }
            val wrapped = IOException("Encoder failed phase=" + stage + " type=" + error.javaClass.simpleName, error)
            failure = wrapped
            throw wrapped
        } finally {
            cleanup(codec, started, failure)
        }
    }

    private fun phase(value: String) {
        stage = value
        observer.log("ENCODER_PHASE $value")
    }

    @Throws(IOException::class)
    private fun cleanup(codec: MediaCodec?, started: Boolean, failure: IOException?) {
        if (codec == null) {
            return
        }
        var cleanupFailure: IOException? = null
        try {
            if (started) {
                codec.stop()
            }
        } catch (error: RuntimeException) {
            cleanupFailure = IOException("Encoder stop failed", error)
        } finally {
            try {
                codec.release()
            } catch (error: RuntimeException) {
                if (cleanupFailure == null) {
                    cleanupFailure = IOException("Encoder release failed", error)
                } else {
                    cleanupFailure.addSuppressed(error)
                }
            }
        }
        if (cleanupFailure != null) {
            observer.log("ENCODER_CLEANUP_FAILED; output does not certify a successful run")
            if (failure != null) {
                failure.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }

    @Throws(IOException::class)
    private fun encode(codec: MediaCodec, renderer: SurfacePatternRenderer, seconds: Int) {
        val queued = SurfaceEncoderPump.run(seconds, FRAME_RATE, cancelled, object : SurfaceEncoderPump.Backend {
            override fun nanoTime(): Long = System.nanoTime()

            @Throws(IOException::class)
            override fun drain(): Boolean {
                stage = "drain"
                this@VideoPatternProbe.drain(codec)
                return outputEos
            }

            override fun outputFrames(): Int = outputFrames

            @Throws(IOException::class)
            override fun render(frame: Int, ptsMicros: Long) {
                stage = "render_swap"
                renderer.render(frame, ptsMicros)
            }

            override fun signalEndOfInput() {
                stage = "signal_eos"
                codec.signalEndOfInputStream()
            }

            override fun pause(nanos: Long) {
                LockSupport.parkNanos(nanos)
            }
        })
        observer.log("Input frames=" + queued + " cancellation=" + cancelled.get())
    }

    private fun logOutput(start: Long) {
        val ptsRate = if (outputFrames > 1 && lastPts > firstPts) (outputFrames - 1) * 1_000_000.0 / (lastPts - firstPts) else 0.0
        val elapsed = if (lastOutputNanos > start) (lastOutputNanos - start) / 1_000_000_000.0 else 0.0
        observer.log(String.format(Locale.US,
            "ENCODER OUTPUT frames=%d firstPTS=%d lastPTS=%d ptsFPS=%.2f wallSeconds=%.3f wallFPS=%.2f EOS=%s",
            outputFrames, firstPts, lastPts, ptsRate, elapsed, if (elapsed > 0) outputFrames / elapsed else 0.0, outputEos))
        observer.log("ENCODER_STAGE ONLY: receiver submission and actual display require separate evidence.")
    }

    @Throws(IOException::class)
    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                observer.log("Output format=$format")
                if (format.getInteger(MediaFormat.KEY_WIDTH) != width || format.getInteger(MediaFormat.KEY_HEIGHT) != height) {
                    throw IOException("Encoder output size mismatch")
                }
                configuration = readConfiguration(format)
                continue
            }
            if (index < 0) {
                return
            }
            try {
                if (info.size > 0) {
                    val bytes = codec.getOutputBuffer(index) ?: throw IOException("Encoder returned null output buffer")
                    bytes.position(info.offset)
                    bytes.limit(info.offset + info.size)
                    consume(bytes, info)
                }
                outputEos = outputEos or (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    @Throws(IOException::class)
    private fun consume(bytes: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            configuration = AvcByteStream.normalizeConfiguration(bytes)
            return
        }
        var annexB = AvcByteStream.normalize(bytes)
        if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0) {
            throw IOException("Partial codec access unit not supported")
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key) {
            if (configuration.isEmpty()) {
                throw IOException("No AVC SPS/PPS available for random access")
            }
            annexB = configuration + annexB
        }
        if (lastPts >= 0 && info.presentationTimeUs <= lastPts) {
            throw IOException("Non-monotonic PTS; expected zero B-frames")
        }
        observer.frame(annexB, info.presentationTimeUs, key)
        if (firstPts < 0) {
            firstPts = info.presentationTimeUs
        }
        lastPts = info.presentationTimeUs
        outputFrames++
        lastOutputNanos = System.nanoTime()
    }

    private fun createFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (width == WIDTH) 6_000_000 else 1_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        // 1920x720/30 exceeds AVC level 3.2's macroblock limit. Baseline forbids B-frames;
        // do not send the additional max-bframes vendor option that screenrecord does not need.
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        return format
    }

    @Throws(IOException::class)
    private fun selectHardwareEncoder(): MediaCodecInfo {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || !info.isHardwareAccelerated) {
                continue
            }
            for (type in info.supportedTypes) {
                if (!MIME.equals(type, ignoreCase = true)) {
                    continue
                }
                val caps = info.getCapabilitiesForType(type)
                if (caps.isFormatSupported(createFormat())) {
                    return info
                }
            }
        }
        throw IOException("No hardware AVC encoder supporting " + width + "x" + height + "/30 baseline level4 Surface")
    }

    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 720
        const val FRAME_RATE = 30
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        private fun readConfiguration(format: MediaFormat): ByteArray {
            val result = ByteArrayOutputStream(128)
            for (key in arrayOf("csd-0", "csd-1")) {
                if (!format.containsKey(key)) {
                    continue
                }
                val buffer = format.getByteBuffer(key)
                if (buffer != null && buffer.hasRemaining()) {
                    val bytes = AvcByteStream.normalizeConfiguration(buffer)
                    result.write(bytes, 0, bytes.size)
                }
            }
            return result.toByteArray()
        }
    }
}
