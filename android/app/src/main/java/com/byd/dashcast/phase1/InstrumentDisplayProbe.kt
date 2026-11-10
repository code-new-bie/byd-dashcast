package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier
import java.util.regex.Pattern

/**
 * Bounded, read-only Screen resource inspection over the existing ordered DCV1 stream.
 * No shell command, HMI write, Screen setter or fullscreen capability is exposed.
 * @author Codex
 * @date 2026/10/19
 */
internal class InstrumentDisplayProbe @JvmOverloads constructor(
    cancelled: AtomicBoolean?,
    logger: NoAuthVideoClient.Logger?,
    nanoTime: LongSupplier? = LongSupplier { System.nanoTime() },
) {
    private val cancelled: AtomicBoolean = Objects.requireNonNull(cancelled, "cancelled")!!
    private val logger: NoAuthVideoClient.Logger = Objects.requireNonNull(logger, "logger")!!
    private val nanoTime: LongSupplier = Objects.requireNonNull(nanoTime, "nanoTime")!!
    private val pending = ArrayDeque<Resource>(MAX_FILES)
    private val seen = HashSet<String>(MAX_FILES * 2)
    private val currentText = ByteArrayOutputStream(PAGE_BYTES)
    private var current: Resource? = null
    private var startedNanos = 0L
    private var page = 0
    private var requests = 0
    private var totalBytes = 0
    private var collected = 0
    private var unavailable = 0
    private var partial = 0
    private var negotiated = false
    private var finished = false
    private var resourceLimit = false

    /** Check the extension BEFORE HELLO/START; old receivers fail without creating video. */
    @Throws(IOException::class)
    fun negotiate(transport: NoAuthVideoTransport) {
        if (negotiated || finished) {
            throw IOException("F1_PROBE_CANNOT_BE_REUSED")
        }
        startedNanos = nanoTime.asLong
        val reply = exchange(transport, Resource(0, 0, 0), 0)
        if (reply.status != 0 || reply.flags != 1 || reply.data.isNotEmpty()) {
            throw IOException("F1_CAPABILITY_UNAVAILABLE status=" + reply.status)
        }
        negotiated = true
        for (display in 1..8) {
            enqueue(Resource(1, 0, display))
        }
        logger.log("F1_CAPABILITY F1P1 readonly=true fullscreen=false HMI_controller=false EGL_alpha=UNKNOWN")
    }

    /** One bounded page per acknowledged video frame; the caller owns/serializes the stream. */
    @Throws(IOException::class)
    fun step(transport: NoAuthVideoTransport) {
        checkActive()
        if (finished) {
            return
        }
        if (requests >= MAX_REQUESTS || totalBytes >= MAX_TOTAL_BYTES || nanoTime.asLong - startedNanos >= BUDGET_NANOS) {
            finish("BUDGET_LIMIT")
            return
        }
        var resource = current
        if (resource == null) {
            resource = pending.pollFirst()
            current = resource
            page = 0
            currentText.reset()
            if (resource == null) {
                finish(if (resourceLimit) "RESOURCE_LIMIT" else "COLLECTION_ENDED")
                return
            }
        }
        val reply = exchange(transport, resource, page)
        if (reply.status != 0) {
            if (reply.status != 2) {
                throw IOException("F1_RESOURCE_FAILED " + resource + " stage=" + reply.status)
            }
            unavailable++
            logger.log("F1_RESOURCE_UNAVAILABLE " + resource + " page=" + page +
                "; absent/denied is not proof that a window does not exist")
            current = null
            return
        }
        if (reply.data.size > MAX_TOTAL_BYTES - totalBytes) {
            finish("BYTE_LIMIT")
            return
        }
        currentText.write(reply.data, 0, reply.data.size)
        totalBytes += reply.data.size
        if (reply.flags == 0) {
            page++
            return
        }
        val truncated = reply.flags == 2
        val text = decode(currentText.toByteArray(), truncated)
        collected++
        if (truncated) {
            partial++
        }
        logger.log("F1_RESOURCE_BEGIN " + resource + " pages=" + (page + 1) + " bytes=" + currentText.size() +
            " truncated=" + truncated + " atomic_snapshot=false\n" + text + "\nF1_RESOURCE_END " + resource)
        discover(text, resource)
        current = null
    }

    @Throws(IOException::class)
    fun run(transport: NoAuthVideoTransport) {
        while (!finished) {
            step(transport)
        }
    }

    fun end() {
        if (negotiated && !finished) {
            finish("VIDEO_ENDED_BEFORE_COLLECTION")
        }
    }

    @Throws(IOException::class)
    private fun exchange(transport: NoAuthVideoTransport, resource: Resource, requestedPage: Int): Reply {
        checkCancellation()
        requests++
        transport.write(request(resource.kind, resource.owner, resource.id, requestedPage))
        val header = transport.readExact(NoAuthVideoProtocol.HEADER_BYTES)
        val size = replySize(header)
        val metadata = transport.readExact(META_BYTES)
        if (metadata.size != META_BYTES) {
            throw IOException("F1_BAD_METADATA_LENGTH")
        }
        val values = ByteBuffer.wrap(metadata).order(ByteOrder.BIG_ENDIAN)
        if (values.getInt() != SCHEMA || values.getInt() != FINGERPRINT) {
            throw IOException("F1_WRONG_RECEIVER_SCHEMA")
        }
        val status = values.getInt()
        val kind = values.getInt()
        val owner = values.getInt()
        val id = values.getInt()
        val replyPage = values.getInt()
        val bytes = values.getInt()
        val flags = values.getInt()
        if (values.getInt() != 0 || status < 0 || status > 6 || flags < 0 || flags > 2 ||
            bytes < 0 || bytes > PAGE_BYTES || size != META_BYTES + bytes ||
            kind != resource.kind || owner != resource.owner || id != resource.id ||
            replyPage != requestedPage || (status != 0 && (bytes != 0 || flags != 0))) {
            throw IOException("F1_REPLY_CONTRACT_MISMATCH")
        }
        if (status == 0 && ((flags == 0 && (bytes != PAGE_BYTES || requestedPage == MAX_PAGES - 1)) ||
                (flags == 2 && (bytes != PAGE_BYTES || requestedPage != MAX_PAGES - 1)))) {
            throw IOException("F1_BAD_PAGINATION")
        }
        val data = ByteArray(bytes)
        var offset = 0
        while (offset < bytes) {
            checkCancellation()
            val count = minOf(NoAuthVideoProtocol.OFFER_BYTES, bytes - offset)
            val piece = transport.readExact(count)
            if (piece.size != count) {
                throw IOException("F1_SHORT_RESOURCE_READ")
            }
            System.arraycopy(piece, 0, data, offset, count)
            offset += count
        }
        return Reply(status, flags, data)
    }

    @Throws(IOException::class)
    private fun discover(text: String, resource: Resource) {
        if (resource.kind == 1) {
            val windows = WINDOW.matcher(text)
            while (windows.find()) {
                enqueue(Resource(2, parseId(windows.group(2)!!), parseId(windows.group(1)!!)))
            }
        } else if (resource.kind == 2) {
            val streams = STREAM.matcher(text)
            while (streams.find()) {
                enqueue(Resource(3, resource.owner, parseId(streams.group(1)!!)))
            }
        }
    }

    private fun enqueue(resource: Resource) {
        if ((resource.kind == 2 || resource.kind == 3) && (resource.owner < 0 || resource.id < 0 || resource.id > 65535)) {
            resourceLimit = true
            return
        }
        val key = resource.toString()
        if (seen.contains(key)) {
            return
        }
        if (seen.size >= MAX_FILES) {
            resourceLimit = true
            return
        }
        seen.add(key)
        pending.addLast(resource)
    }

    @Throws(IOException::class)
    private fun checkActive() {
        checkCancellation()
        if (!negotiated) {
            throw IOException("F1_NOT_NEGOTIATED")
        }
    }

    @Throws(IOException::class)
    private fun checkCancellation() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("F1_PROBE_CANCELLED")
        }
    }

    private fun finish(reason: String) {
        finished = true
        logger.log("F1_READBACK_END reason=" + reason + " files=" + collected + " unavailable=" + unavailable +
            " partial=" + partial + " pending=" + pending.size + " current_pending=" + (current != null) +
            " requests=" + requests + " bytes=" + totalBytes +
            "; NOT fullscreen/HMI/EGL/alpha/plane/safety validation; fullscreen_enabled=false")
    }

    private class Resource(val kind: Int, val owner: Int, val id: Int) {
        override fun toString(): String = "kind=$kind owner_pid=$owner object=$id"
    }

    private class Reply(val status: Int, val flags: Int, val data: ByteArray)

    companion object {
        const val OPERATION = 9
        const val META_BYTES = 40
        const val PAGE_BYTES = 4096
        const val MAX_PAGES = 4
        const val MAX_FILES = 32
        const val MAX_TOTAL_BYTES = 128 * 1024
        const val MAX_REQUESTS = 128
        private const val BUDGET_NANOS = 10_000_000_000L
        private const val SCHEMA = 1
        private const val FINGERPRINT = 0x46315031
        private val WINDOW = Pattern.compile("win-(\\d+)[^\\r\\n]*?pid=(\\d+)\\(")
        private val STREAM = Pattern.compile("\\bstr-(\\d+)\\b")

        @JvmStatic
        @Throws(IOException::class)
        fun request(kind: Int, owner: Int, id: Int, page: Int): ByteArray {
            val valid = (kind == 0 && owner == 0 && id == 0 && page == 0) ||
                (kind == 1 && owner == 0 && id >= 1 && id <= 8) ||
                ((kind == 2 || kind == 3) && owner >= 0 && id >= 0 && id <= 65535)
            if (!valid || page < 0 || page >= MAX_PAGES) {
                throw IOException("F1_INVALID_RESOURCE")
            }
            val packet = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
            packet.put("DCV1".toByteArray(Charsets.US_ASCII)).putInt(OPERATION).putInt(20)
            packet.putInt(0).putInt(0).putInt(0)
            packet.putInt(SCHEMA).putInt(kind).putInt(owner).putInt(id).putInt(page)
            return packet.array()
        }

        @Throws(IOException::class)
        private fun replySize(header: ByteArray?): Int {
            if (header == null || header.size != NoAuthVideoProtocol.HEADER_BYTES) {
                throw IOException("F1_BAD_HEADER_LENGTH")
            }
            val values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (values.getInt() != 0x44435631 || values.getInt() != (OPERATION or 0x80)) {
                throw IOException("F1_RECEIVER_NOT_SUPPORTED")
            }
            val bytes = values.getInt()
            if (bytes < META_BYTES || bytes > META_BYTES + PAGE_BYTES ||
                values.getInt() != 0 || values.getInt() != 0 || values.getInt() != 0) {
                throw IOException("F1_BAD_ENVELOPE")
            }
            return bytes
        }

        @Throws(IOException::class)
        private fun parseId(text: String): Int {
            if (text.length > 10) {
                throw IOException("F1_RESOURCE_ID_OVERFLOW")
            }
            try {
                return Integer.parseInt(text)
            } catch (error: NumberFormatException) {
                throw IOException("F1_RESOURCE_ID_OVERFLOW", error)
            }
        }

        @Throws(IOException::class)
        private fun decode(bytes: ByteArray, truncated: Boolean): String {
            try {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(if (truncated) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                for (value in text) {
                    if ((value.code < 32 && value != '\r' && value != '\n' && value != '\t') || value.code == 127) {
                        throw IOException("F1_NON_TEXT_RESOURCE")
                    }
                }
                return text
            } catch (error: CharacterCodingException) {
                throw IOException("F1_NON_UTF8_RESOURCE", error)
            }
        }
    }
}
