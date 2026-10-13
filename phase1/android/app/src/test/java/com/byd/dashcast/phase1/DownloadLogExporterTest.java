package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Isolated ZIP, publication and exact-entry failure cleanup tests; no Android/provider required.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class DownloadLogExporterTest {
    private static final String FILE_NAME = "dashcast-logs-1791630000000-a1b2c3d4.zip";

    @Test
    public void writesUtf8SnapshotsAndPublishesAfterClose() throws Exception {
        FakeStore store = new FakeStore();
        String report = "仪表权限被拒绝\ncommon=false set=false\n";
        String usbLog = "USB devices=0\n没有 NCM\n";
        DownloadLogExporter.export(store, FILE_NAME, report, usbLog);
        Map<String, String> files = readArchive(store.entry.bytes.toByteArray());
        assertEquals(2, files.size());
        assertEquals(report, files.get("report.txt"));
        assertEquals(usbLog, files.get("usb_ncm.txt"));
        assertEquals(Arrays.asList("create", "open", "close", "publish"), store.events);
        assertEquals(FILE_NAME, store.fileName);
        assertFalse(store.entry.discarded);
    }

    @Test
    public void capsLargeSnapshotsAndKeepsNewestDiagnosticText() throws Exception {
        FakeStore store = new FakeStore();
        String report = "REPORT_OLD" + "r".repeat(DownloadLogExporter.MAX_REPORT_CHARS) + "REPORT_NEW";
        String usbLog = "USB_OLD" + "u".repeat(DownloadLogExporter.MAX_USB_LOG_CHARS) + "USB_NEW";
        DownloadLogExporter.export(store, FILE_NAME, report, usbLog);
        Map<String, String> files = readArchive(store.entry.bytes.toByteArray());
        assertFalse(files.get("report.txt").contains("REPORT_OLD"));
        assertTrue(files.get("report.txt").endsWith("REPORT_NEW"));
        assertTrue(files.get("report.txt").contains("Earlier log text omitted"));
        assertFalse(files.get("usb_ncm.txt").contains("USB_OLD"));
        assertTrue(files.get("usb_ncm.txt").endsWith("USB_NEW"));
        assertTrue(files.get("usb_ncm.txt").length() <= DownloadLogExporter.MAX_USB_LOG_CHARS + 100);
    }

    @Test
    public void rejectsPathComponentsBeforeCreatingAnyFile() {
        FakeStore store = new FakeStore();
        assertThrows(IllegalArgumentException.class,
                () -> DownloadLogExporter.export(store, "../" + FILE_NAME, "report", "usb"));
        assertTrue(store.events.isEmpty());
    }

    @Test
    public void exportsFixedSanitizedRelayAndUsbStateEntries() throws Exception {
        FakeStore store = new FakeStore();
        DownloadLogExporter.export(store, FILE_NAME, "report", "usb", "relay evidence", "Android flags");
        Map<String, String> files = readArchive(store.entry.bytes.toByteArray());
        assertEquals(Arrays.asList("report.txt", "usb_ncm.txt", "navigation_relay.txt", "android_usb_state.txt"),
                new java.util.ArrayList<>(files.keySet()));
        assertEquals("relay evidence", files.get("navigation_relay.txt"));
        assertEquals("Android flags", files.get("android_usb_state.txt"));
        assertTrue(store.entry.published);
    }

    @Test
    public void boundsAdditionalEvidenceAndRejectsMissingSnapshots() throws Exception {
        FakeStore store = new FakeStore();
        assertThrows(NullPointerException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb", null, "flags"));
        assertTrue(store.events.isEmpty());
        DownloadLogExporter.export(store, FILE_NAME, "report", "usb",
                "OLD" + "e".repeat(DownloadLogExporter.MAX_RELAY_CHARS) + "LATEST", "flags");
        String result = readArchive(store.entry.bytes.toByteArray()).get("navigation_relay.txt");
        assertFalse(result.contains("OLD"));
        assertTrue(result.endsWith("LATEST"));
    }

    @Test
    public void rejectsMissingSnapshotBeforeCreatingAnyFile() {
        FakeStore store = new FakeStore();
        assertThrows(NullPointerException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, null, "usb"));
        assertTrue(store.events.isEmpty());
    }

    @Test
    public void creationFailureDoesNotDiscardAnyOtherEntry() {
        FakeStore store = new FakeStore();
        store.createError = new IOException("provider unavailable");
        assertSame(store.createError, assertThrows(IOException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb")));
        assertEquals(Arrays.asList("create"), store.events);
        assertFalse(store.entry.discarded);
    }

    @Test
    public void nullCreatedEntryIsNotReportedAsSuccess() {
        FakeStore store = new FakeStore();
        store.nullEntry = true;
        assertThrows(IOException.class, () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb"));
        assertEquals(Arrays.asList("create"), store.events);
    }

    @Test
    public void openingFailureDiscardsOnlyCreatedEntry() {
        FakeStore store = new FakeStore();
        store.entry.openError = new IOException("open failed");
        assertSame(store.entry.openError, assertThrows(IOException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb")));
        assertEquals(Arrays.asList("create", "open", "discard"), store.events);
        assertTrue(store.entry.discarded);
    }

    @Test
    public void nullStreamDiscardsCreatedEntryWithoutPublishing() {
        FakeStore store = new FakeStore();
        store.entry.nullStream = true;
        assertThrows(IOException.class, () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb"));
        assertEquals(Arrays.asList("create", "open", "discard"), store.events);
    }

    @Test
    public void writingFailureClosesAndDiscardsWithoutPublishing() {
        FakeStore store = new FakeStore();
        store.entry.writeError = true;
        assertThrows(IOException.class, () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb"));
        assertEquals(Arrays.asList("create", "open", "close", "discard"), store.events);
        assertFalse(store.entry.published);
    }

    @Test
    public void closingFailureDiscardsWithoutPublishing() {
        FakeStore store = new FakeStore();
        store.entry.closeError = true;
        assertThrows(IOException.class, () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb"));
        assertEquals(Arrays.asList("create", "open", "close", "discard"), store.events);
        assertFalse(store.entry.published);
    }

    @Test
    public void publicationFailureDiscardsCompleteButUnpublishedFile() {
        FakeStore store = new FakeStore();
        store.entry.publishError = new IOException("publish failed");
        assertSame(store.entry.publishError, assertThrows(IOException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb")));
        assertEquals(Arrays.asList("create", "open", "close", "publish", "discard"), store.events);
        assertTrue(store.entry.discarded);
    }

    @Test
    public void cleanupFailureRetainsOriginalAndCleanupEvidence() {
        FakeStore store = new FakeStore();
        store.entry.openError = new IOException("open failed");
        store.entry.discardError = new IOException("cleanup denied");
        IOException result = assertThrows(IOException.class,
                () -> DownloadLogExporter.export(store, FILE_NAME, "report", "usb"));
        assertTrue(result.getMessage().contains("cleanup unconfirmed"));
        assertSame(store.entry.openError, result.getCause());
        assertEquals(1, result.getSuppressed().length);
        assertSame(store.entry.discardError, result.getSuppressed()[0]);
        assertFalse(store.entry.published);
    }

    @Test
    public void namesArePathFreeAndDistinctEvenAtSameTimestamp() {
        String first = DownloadLogExporter.fileName(1791630000000L);
        String second = DownloadLogExporter.fileName(1791630000000L);
        assertTrue(first.matches("dashcast-logs-1791630000000-[0-9a-f]{8}\\.zip"));
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> DownloadLogExporter.fileName(-1));
    }

    private static Map<String, String> readArchive(byte[] data) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>(2);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                contents.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return contents;
    }

    private static final class FakeStore implements DownloadLogExporter.Store {
        private final List<String> events = new ArrayList<>(5);
        private final FakeEntry entry = new FakeEntry(events);
        private String fileName;
        private IOException createError;
        private boolean nullEntry;

        @Override
        public DownloadLogExporter.Entry create(String name) throws IOException {
            events.add("create");
            fileName = name;
            if (createError != null) {
                throw createError;
            }
            return nullEntry ? null : entry;
        }
    }

    private static final class FakeEntry implements DownloadLogExporter.Entry {
        private final List<String> events;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private IOException openError;
        private IOException publishError;
        private IOException discardError;
        private boolean nullStream;
        private boolean writeError;
        private boolean closeError;
        private boolean published;
        private boolean discarded;

        private FakeEntry(List<String> events) {
            this.events = events;
        }

        @Override
        public OutputStream open() throws IOException {
            events.add("open");
            if (openError != null) {
                throw openError;
            }
            if (nullStream) {
                return null;
            }
            return new OutputStream() {
                private boolean closed;

                @Override
                public void write(int value) throws IOException {
                    if (writeError) {
                        throw new IOException("disk full");
                    }
                    bytes.write(value);
                }

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    events.add("close");
                    if (closeError) {
                        throw new IOException("close failed");
                    }
                }
            };
        }

        @Override
        public void publish() throws IOException {
            events.add("publish");
            if (publishError != null) {
                throw publishError;
            }
            published = true;
        }

        @Override
        public void discard() throws IOException {
            events.add("discard");
            if (discardError != null) {
                throw discardError;
            }
            discarded = true;
        }
    }
}
