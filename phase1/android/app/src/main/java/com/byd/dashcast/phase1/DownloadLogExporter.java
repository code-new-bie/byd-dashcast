package com.byd.dashcast.phase1;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publishes a bounded diagnostic snapshot only after its ZIP is completely closed.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class DownloadLogExporter {
    static final int MAX_REPORT_CHARS = 512 * 1024;
    static final int MAX_USB_LOG_CHARS = 256 * 1024;
    static final int MAX_RELAY_CHARS = 64 * 1024;
    private static final int NAME_SUFFIX_LENGTH = 8;
    private static final String TRUNCATION_NOTICE = "[Earlier log text omitted; newest snapshot follows.]\n";
    private static final Pattern FILE_NAME_PATTERN =
            Pattern.compile("dashcast-logs-[0-9]{1,19}-[0-9a-f]{8}\\.zip");

    private DownloadLogExporter() {
    }

    /** A destination that creates only new, initially unpublished files. */
    interface Store {
        /**
         * @param fileName validated unique ZIP name
         * @return the newly created entry, never an existing user file
         * @throws IOException when creation fails
         */
        Entry create(String fileName) throws IOException;
    }

    /** One entry owned by this export attempt; failure cleanup cannot target other files. */
    interface Entry {
        /** @return write stream; @throws IOException when opening fails */
        OutputStream open() throws IOException;

        /** Makes the complete file visible; @throws IOException if publication is unconfirmed. */
        void publish() throws IOException;

        /** Removes this attempt's entry only; @throws IOException if cleanup is unconfirmed. */
        void discard() throws IOException;
    }

    /** @param nowMillis nonnegative export time; @return collision-resistant, path-free file name */
    static String fileName(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("Export timestamp must be nonnegative");
        }
        return "dashcast-logs-" + nowMillis + "-"
                + UUID.randomUUID().toString().substring(0, NAME_SUFFIX_LENGTH) + ".zip";
    }

    /**
     * @param store new-file-only destination
     * @param fileName generated ZIP name, with no directory components
     * @param report captured APK diagnostic log; no video or new system probes are collected
     * @param usbLog captured USB/NCM log
     * @throws IOException if writing, closing, publishing or failure cleanup is not confirmed
     */
    static void export(Store store, String fileName, String report, String usbLog) throws IOException {
        exportInternal(store, fileName, report, usbLog, null, null);
    }

    /** Exports sanitized read-only evidence as fixed ZIP entries, never raw system logcat. */
    static void export(Store store, String fileName, String report, String usbLog,
            String relayEvidence, String androidUsbEvidence) throws IOException {
        Objects.requireNonNull(relayEvidence, "Relay snapshot required");
        Objects.requireNonNull(androidUsbEvidence, "Android USB snapshot required");
        exportInternal(store, fileName, report, usbLog, relayEvidence, androidUsbEvidence);
    }

    private static void exportInternal(Store store, String fileName, String report, String usbLog,
            String relayEvidence, String androidUsbEvidence) throws IOException {
        Objects.requireNonNull(store, "Export store required");
        Objects.requireNonNull(report, "Report snapshot required");
        Objects.requireNonNull(usbLog, "USB snapshot required");
        if (fileName == null || !FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Invalid diagnostic ZIP name");
        }
        Entry entry = store.create(fileName);
        if (entry == null) {
            throw new IOException("Downloads provider did not create a file");
        }
        try {
            // The raw stream also owns a close guard: ZIP finalization can fail before closing it.
            try (OutputStream destination = entry.open()) {
                if (destination == null) {
                    throw new IOException("Downloads write stream unavailable");
                }
                try (ZipOutputStream archive = new ZipOutputStream(destination)) {
                    writeText(archive, "report.txt", report, MAX_REPORT_CHARS);
                    writeText(archive, "usb_ncm.txt", usbLog, MAX_USB_LOG_CHARS);
                    if (relayEvidence != null) {
                        writeText(archive, "navigation_relay.txt", relayEvidence, MAX_RELAY_CHARS);
                        writeText(archive, "android_usb_state.txt", androidUsbEvidence, MAX_RELAY_CHARS);
                    }
                }
            }
            entry.publish();
        } catch (IOException | RuntimeException error) {
            try {
                entry.discard();
            } catch (IOException | RuntimeException cleanupError) {
                IOException unconfirmed = new IOException(
                        "Export failed; incomplete Downloads entry cleanup unconfirmed: " + error.getMessage(), error);
                unconfirmed.addSuppressed(cleanupError);
                throw unconfirmed;
            }
            throw error;
        }
    }

    private static void writeText(ZipOutputStream archive, String name, String text, int maximum) throws IOException {
        String bounded = text.length() > maximum
                ? TRUNCATION_NOTICE + text.substring(text.length() - maximum) : text;
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bounded.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }
}
