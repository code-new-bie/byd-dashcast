package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, read-only Screen resource inspection over the existing ordered DCV1 stream.
 * No shell command, HMI write, Screen setter or fullscreen capability is exposed.
 * @author Codex
 * @date 2026/10/19
 */
final class InstrumentDisplayProbe {
    static final int OPERATION = 9;
    static final int META_BYTES = 40;
    static final int PAGE_BYTES = 4096;
    static final int MAX_PAGES = 4;
    static final int MAX_FILES = 32;
    static final int MAX_TOTAL_BYTES = 128 * 1024;
    static final int MAX_REQUESTS = 128;
    private static final long BUDGET_NANOS = 10_000_000_000L;
    private static final int SCHEMA = 1;
    private static final int FINGERPRINT = 0x46315031;
    private static final Pattern WINDOW = Pattern.compile("win-(\\d+)[^\\r\\n]*?pid=(\\d+)\\(");
    private static final Pattern STREAM = Pattern.compile("\\bstr-(\\d+)\\b");
    private final AtomicBoolean cancelled;
    private final NoAuthVideoClient.Logger logger;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Resource> pending = new ArrayDeque<>(MAX_FILES);
    private final Set<String> seen = new HashSet<>(MAX_FILES * 2);
    private final ByteArrayOutputStream currentText = new ByteArrayOutputStream(PAGE_BYTES);
    private Resource current;
    private long startedNanos;
    private int page;
    private int requests;
    private int totalBytes;
    private int collected;
    private int unavailable;
    private int partial;
    private boolean negotiated;
    private boolean finished;
    private boolean resourceLimit;

    InstrumentDisplayProbe(AtomicBoolean cancelled, NoAuthVideoClient.Logger logger) {
        this(cancelled, logger, System::nanoTime);
    }

    InstrumentDisplayProbe(AtomicBoolean cancelled, NoAuthVideoClient.Logger logger, LongSupplier nanoTime) {
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Check the extension BEFORE HELLO/START; old receivers fail without creating video. */
    void negotiate(NoAuthVideoTransport transport) throws IOException {
        if (negotiated || finished) {
            throw new IOException("F1_PROBE_CANNOT_BE_REUSED");
        }
        startedNanos = nanoTime.getAsLong();
        Reply reply = exchange(transport, new Resource(0, 0, 0), 0);
        if (reply.status != 0 || reply.flags != 1 || reply.data.length != 0) {
            throw new IOException("F1_CAPABILITY_UNAVAILABLE status=" + reply.status);
        }
        negotiated = true;
        for (int display = 1; display <= 8; display++) {
            enqueue(new Resource(1, 0, display));
        }
        logger.log("F1_CAPABILITY F1P1 readonly=true fullscreen=false HMI_controller=false EGL_alpha=UNKNOWN");
    }

    /** One bounded page per acknowledged video frame; the caller owns/serializes the stream. */
    void step(NoAuthVideoTransport transport) throws IOException {
        checkActive();
        if (finished) {
            return;
        }
        if (requests >= MAX_REQUESTS || totalBytes >= MAX_TOTAL_BYTES
                || nanoTime.getAsLong() - startedNanos >= BUDGET_NANOS) {
            finish("BUDGET_LIMIT");
            return;
        }
        if (current == null) {
            current = pending.pollFirst();
            page = 0;
            currentText.reset();
            if (current == null) {
                finish(resourceLimit ? "RESOURCE_LIMIT" : "COLLECTION_ENDED");
                return;
            }
        }
        Reply reply = exchange(transport, current, page);
        if (reply.status != 0) {
            if (reply.status != 2) {
                throw new IOException("F1_RESOURCE_FAILED " + current + " stage=" + reply.status);
            }
            unavailable++;
            logger.log("F1_RESOURCE_UNAVAILABLE " + current + " page=" + page
                    + "; absent/denied is not proof that a window does not exist");
            current = null;
            return;
        }
        if (reply.data.length > MAX_TOTAL_BYTES - totalBytes) {
            finish("BYTE_LIMIT");
            return;
        }
        currentText.write(reply.data, 0, reply.data.length);
        totalBytes += reply.data.length;
        if (reply.flags == 0) {
            page++;
            return;
        }
        boolean truncated = reply.flags == 2;
        String text = decode(currentText.toByteArray(), truncated);
        collected++;
        if (truncated) {
            partial++;
        }
        logger.log("F1_RESOURCE_BEGIN " + current + " pages=" + (page + 1) + " bytes="
                + currentText.size() + " truncated=" + truncated + " atomic_snapshot=false\n"
                + text + "\nF1_RESOURCE_END " + current);
        discover(text, current);
        current = null;
    }

    void run(NoAuthVideoTransport transport) throws IOException {
        while (!finished) {
            step(transport);
        }
    }

    void end() {
        if (negotiated && !finished) {
            finish("VIDEO_ENDED_BEFORE_COLLECTION");
        }
    }

    private Reply exchange(NoAuthVideoTransport transport, Resource resource, int requestedPage) throws IOException {
        checkCancellation();
        requests++;
        transport.write(request(resource.kind, resource.owner, resource.id, requestedPage));
        byte[] header = transport.readExact(NoAuthVideoProtocol.HEADER_BYTES);
        int size = replySize(header);
        byte[] metadata = transport.readExact(META_BYTES);
        if (metadata == null || metadata.length != META_BYTES) {
            throw new IOException("F1_BAD_METADATA_LENGTH");
        }
        ByteBuffer values = ByteBuffer.wrap(metadata).order(ByteOrder.BIG_ENDIAN);
        if (values.getInt() != SCHEMA || values.getInt() != FINGERPRINT) {
            throw new IOException("F1_WRONG_RECEIVER_SCHEMA");
        }
        int status = values.getInt();
        int kind = values.getInt();
        int owner = values.getInt();
        int id = values.getInt();
        int replyPage = values.getInt();
        int bytes = values.getInt();
        int flags = values.getInt();
        if (values.getInt() != 0 || status < 0 || status > 6 || flags < 0 || flags > 2
                || bytes < 0 || bytes > PAGE_BYTES || size != META_BYTES + bytes
                || kind != resource.kind || owner != resource.owner || id != resource.id
                || replyPage != requestedPage || status != 0 && (bytes != 0 || flags != 0)) {
            throw new IOException("F1_REPLY_CONTRACT_MISMATCH");
        }
        if (status == 0 && (flags == 0 && (bytes != PAGE_BYTES || requestedPage == MAX_PAGES - 1)
                || flags == 2 && (bytes != PAGE_BYTES || requestedPage != MAX_PAGES - 1))) {
            throw new IOException("F1_BAD_PAGINATION");
        }
        byte[] data = new byte[bytes];
        for (int offset = 0; offset < bytes;) {
            checkCancellation();
            int count = Math.min(NoAuthVideoProtocol.OFFER_BYTES, bytes - offset);
            byte[] piece = transport.readExact(count);
            if (piece.length != count) {
                throw new IOException("F1_SHORT_RESOURCE_READ");
            }
            System.arraycopy(piece, 0, data, offset, count);
            offset += count;
        }
        return new Reply(status, flags, data);
    }

    static byte[] request(int kind, int owner, int id, int page) throws IOException {
        boolean valid = kind == 0 && owner == 0 && id == 0 && page == 0
                || kind == 1 && owner == 0 && id >= 1 && id <= 8
                || (kind == 2 || kind == 3) && owner >= 0 && id >= 0 && id <= 65535;
        if (!valid || page < 0 || page >= MAX_PAGES) {
            throw new IOException("F1_INVALID_RESOURCE");
        }
        ByteBuffer packet = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN);
        packet.put(new byte[] {'D', 'C', 'V', '1'}).putInt(OPERATION).putInt(20);
        packet.putInt(0).putInt(0).putInt(0);
        packet.putInt(SCHEMA).putInt(kind).putInt(owner).putInt(id).putInt(page);
        return packet.array();
    }

    private static int replySize(byte[] header) throws IOException {
        if (header == null || header.length != NoAuthVideoProtocol.HEADER_BYTES) {
            throw new IOException("F1_BAD_HEADER_LENGTH");
        }
        ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (values.getInt() != 0x44435631 || values.getInt() != (OPERATION | 0x80)) {
            throw new IOException("F1_RECEIVER_NOT_SUPPORTED");
        }
        int bytes = values.getInt();
        if (bytes < META_BYTES || bytes > META_BYTES + PAGE_BYTES
                || values.getInt() != 0 || values.getInt() != 0 || values.getInt() != 0) {
            throw new IOException("F1_BAD_ENVELOPE");
        }
        return bytes;
    }

    private void discover(String text, Resource resource) throws IOException {
        if (resource.kind == 1) {
            Matcher windows = WINDOW.matcher(text);
            while (windows.find()) {
                enqueue(new Resource(2, parseId(windows.group(2)), parseId(windows.group(1))));
            }
        } else if (resource.kind == 2) {
            Matcher streams = STREAM.matcher(text);
            while (streams.find()) {
                enqueue(new Resource(3, resource.owner, parseId(streams.group(1))));
            }
        }
    }

    private static int parseId(String text) throws IOException {
        if (text.length() > 10) {
            throw new IOException("F1_RESOURCE_ID_OVERFLOW");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new IOException("F1_RESOURCE_ID_OVERFLOW", error);
        }
    }

    private void enqueue(Resource resource) {
        if ((resource.kind == 2 || resource.kind == 3)
                && (resource.owner < 0 || resource.id < 0 || resource.id > 65535)) {
            resourceLimit = true;
            return;
        }
        String key = resource.toString();
        if (seen.contains(key)) {
            return;
        }
        if (seen.size() >= MAX_FILES) {
            resourceLimit = true;
            return;
        }
        seen.add(key);
        pending.addLast(resource);
    }

    private static String decode(byte[] bytes, boolean truncated) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(truncated ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value < 32 && value != '\r' && value != '\n' && value != '\t' || value == 127) {
                    throw new IOException("F1_NON_TEXT_RESOURCE");
                }
            }
            return text;
        } catch (CharacterCodingException error) {
            throw new IOException("F1_NON_UTF8_RESOURCE", error);
        }
    }

    private void checkActive() throws IOException {
        checkCancellation();
        if (!negotiated) {
            throw new IOException("F1_NOT_NEGOTIATED");
        }
    }

    private void checkCancellation() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("F1_PROBE_CANCELLED");
        }
    }

    private void finish(String reason) {
        finished = true;
        logger.log("F1_READBACK_END reason=" + reason + " files=" + collected + " unavailable=" + unavailable
                + " partial=" + partial + " pending=" + pending.size() + " current_pending=" + (current != null)
                + " requests=" + requests + " bytes=" + totalBytes
                + "; NOT fullscreen/HMI/EGL/alpha/plane/safety validation; fullscreen_enabled=false");
    }

    private static final class Resource {
        private final int kind;
        private final int owner;
        private final int id;

        private Resource(int kind, int owner, int id) {
            this.kind = kind;
            this.owner = owner;
            this.id = id;
        }

        @Override
        public String toString() {
            return "kind=" + kind + " owner_pid=" + owner + " object=" + id;
        }
    }

    private static final class Reply {
        private final int status;
        private final int flags;
        private final byte[] data;

        private Reply(int status, int flags, byte[] data) {
            this.status = status;
            this.flags = flags;
            this.data = data;
        }
    }
}
