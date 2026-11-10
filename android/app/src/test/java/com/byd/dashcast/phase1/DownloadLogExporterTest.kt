package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Isolated ZIP, publication and exact-entry failure cleanup tests; no Android/provider required.
 *
 * @author Codex
 * @date 2026/10/10
 */
class DownloadLogExporterTest {
    @Test
    fun writesUtf8SnapshotsAndPublishesAfterClose() {
        val store = FakeStore()
        val report = "仪表权限被拒绝\ncommon=false set=false\n"
        val usbLog = "USB devices=0\n没有 NCM\n"
        DownloadLogExporter.export(store, FILE_NAME, report, usbLog)
        val files = readArchive(store.entry.bytes.toByteArray())
        assertEquals(2, files.size)
        assertEquals(report, files["report.txt"])
        assertEquals(usbLog, files["usb_ncm.txt"])
        assertEquals(listOf("create", "open", "close", "publish"), store.events)
        assertEquals(FILE_NAME, store.fileName)
        assertFalse(store.entry.discarded)
    }

    @Test
    fun capsLargeSnapshotsAndKeepsNewestDiagnosticText() {
        val store = FakeStore()
        val report = "REPORT_OLD" + "r".repeat(DownloadLogExporter.MAX_REPORT_CHARS) + "REPORT_NEW"
        val usbLog = "USB_OLD" + "u".repeat(DownloadLogExporter.MAX_USB_LOG_CHARS) + "USB_NEW"
        DownloadLogExporter.export(store, FILE_NAME, report, usbLog)
        val files = readArchive(store.entry.bytes.toByteArray())
        assertFalse(files.getValue("report.txt").contains("REPORT_OLD"))
        assertTrue(files.getValue("report.txt").endsWith("REPORT_NEW"))
        assertTrue(files.getValue("report.txt").contains("Earlier log text omitted"))
        assertFalse(files.getValue("usb_ncm.txt").contains("USB_OLD"))
        assertTrue(files.getValue("usb_ncm.txt").endsWith("USB_NEW"))
        assertTrue(files.getValue("usb_ncm.txt").length <= DownloadLogExporter.MAX_USB_LOG_CHARS + 100)
    }

    @Test
    fun rejectsPathComponentsBeforeCreatingAnyFile() {
        val store = FakeStore()
        assertThrows(IllegalArgumentException::class.java) {
            DownloadLogExporter.export(store, "../" + FILE_NAME, "report", "usb")
        }
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun exportsFixedSanitizedRelayAndUsbStateEntries() {
        val store = FakeStore()
        DownloadLogExporter.export(store, FILE_NAME, "report", "usb", "relay evidence", "Android flags")
        val files = readArchive(store.entry.bytes.toByteArray())
        assertEquals(listOf("report.txt", "usb_ncm.txt", "navigation_relay.txt", "android_usb_state.txt"),
            ArrayList(files.keys))
        assertEquals("relay evidence", files["navigation_relay.txt"])
        assertEquals("Android flags", files["android_usb_state.txt"])
        assertTrue(store.entry.published)
    }

    @Test
    fun exportsTheInstrumentLogsAsTheirOwnFiles() {
        val store = FakeStore()
        DownloadLogExporter.export(store, FILE_NAME, "report", "usb", "relay", "flags", "instrument usb", "instrument cast")
        val files = readArchive(store.entry.bytes.toByteArray())
        assertEquals(listOf("report.txt", "usb_ncm.txt", "navigation_relay.txt", "android_usb_state.txt", "usb.log", "cast.log"),
            ArrayList(files.keys))
        assertEquals("instrument usb", files["usb.log"])
        assertEquals("instrument cast", files["cast.log"])
    }

    @Test
    fun boundsAdditionalEvidenceAndRejectsMissingSnapshots() {
        val store = FakeStore()
        assertThrows(NullPointerException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, "report", "usb", null, "flags")
        }
        assertTrue(store.events.isEmpty())
        DownloadLogExporter.export(store, FILE_NAME, "report", "usb",
            "OLD" + "e".repeat(DownloadLogExporter.MAX_RELAY_CHARS) + "LATEST", "flags")
        val result = readArchive(store.entry.bytes.toByteArray()).getValue("navigation_relay.txt")
        assertFalse(result.contains("OLD"))
        assertTrue(result.endsWith("LATEST"))
    }

    @Test
    fun rejectsMissingSnapshotBeforeCreatingAnyFile() {
        val store = FakeStore()
        assertThrows(NullPointerException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, null, "usb")
        }
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun creationFailureDoesNotDiscardAnyOtherEntry() {
        val store = FakeStore()
        store.createError = IOException("provider unavailable")
        assertSame(store.createError, assertThrows(IOException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, "report", "usb")
        })
        assertEquals(listOf("create"), store.events)
        assertFalse(store.entry.discarded)
    }

    @Test
    fun nullCreatedEntryIsNotReportedAsSuccess() {
        val store = FakeStore()
        store.nullEntry = true
        assertThrows(IOException::class.java) { DownloadLogExporter.export(store, FILE_NAME, "report", "usb") }
        assertEquals(listOf("create"), store.events)
    }

    @Test
    fun openingFailureDiscardsOnlyCreatedEntry() {
        val store = FakeStore()
        store.entry.openError = IOException("open failed")
        assertSame(store.entry.openError, assertThrows(IOException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, "report", "usb")
        })
        assertEquals(listOf("create", "open", "discard"), store.events)
        assertTrue(store.entry.discarded)
    }

    @Test
    fun nullStreamDiscardsCreatedEntryWithoutPublishing() {
        val store = FakeStore()
        store.entry.nullStream = true
        assertThrows(IOException::class.java) { DownloadLogExporter.export(store, FILE_NAME, "report", "usb") }
        assertEquals(listOf("create", "open", "discard"), store.events)
    }

    @Test
    fun writingFailureClosesAndDiscardsWithoutPublishing() {
        val store = FakeStore()
        store.entry.writeError = true
        assertThrows(IOException::class.java) { DownloadLogExporter.export(store, FILE_NAME, "report", "usb") }
        assertEquals(listOf("create", "open", "close", "discard"), store.events)
        assertFalse(store.entry.published)
    }

    @Test
    fun closingFailureDiscardsWithoutPublishing() {
        val store = FakeStore()
        store.entry.closeError = true
        assertThrows(IOException::class.java) { DownloadLogExporter.export(store, FILE_NAME, "report", "usb") }
        assertEquals(listOf("create", "open", "close", "discard"), store.events)
        assertFalse(store.entry.published)
    }

    @Test
    fun publicationFailureDiscardsCompleteButUnpublishedFile() {
        val store = FakeStore()
        store.entry.publishError = IOException("publish failed")
        assertSame(store.entry.publishError, assertThrows(IOException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, "report", "usb")
        })
        assertEquals(listOf("create", "open", "close", "publish", "discard"), store.events)
        assertTrue(store.entry.discarded)
    }

    @Test
    fun cleanupFailureRetainsOriginalAndCleanupEvidence() {
        val store = FakeStore()
        store.entry.openError = IOException("open failed")
        store.entry.discardError = IOException("cleanup denied")
        val result = assertThrows(IOException::class.java) {
            DownloadLogExporter.export(store, FILE_NAME, "report", "usb")
        }
        assertTrue(result.message!!.contains("cleanup unconfirmed"))
        assertSame(store.entry.openError, result.cause)
        assertEquals(1, result.suppressed.size)
        assertSame(store.entry.discardError, result.suppressed[0])
        assertFalse(store.entry.published)
    }

    @Test
    fun namesArePathFreeAndDistinctEvenAtSameTimestamp() {
        val first = DownloadLogExporter.fileName(1791630000000L)
        val second = DownloadLogExporter.fileName(1791630000000L)
        assertTrue(first.matches(Regex("dashcast-logs-1791630000000-[0-9a-f]{8}\\.zip")))
        assertNotEquals(first, second)
        assertThrows(IllegalArgumentException::class.java) { DownloadLogExporter.fileName(-1) }
    }

    private class FakeStore : DownloadLogExporter.Store {
        val events = ArrayList<String>(5)
        val entry = FakeEntry(events)
        var fileName: String? = null
        var createError: IOException? = null
        var nullEntry = false

        override fun create(fileName: String): DownloadLogExporter.Entry? {
            events.add("create")
            this.fileName = fileName
            createError?.let { throw it }
            return if (nullEntry) null else entry
        }
    }

    private class FakeEntry(private val events: MutableList<String>) : DownloadLogExporter.Entry {
        val bytes = ByteArrayOutputStream()
        var openError: IOException? = null
        var publishError: IOException? = null
        var discardError: IOException? = null
        var nullStream = false
        var writeError = false
        var closeError = false
        var published = false
        var discarded = false

        override fun open(): OutputStream? {
            events.add("open")
            openError?.let { throw it }
            if (nullStream) {
                return null
            }
            return object : OutputStream() {
                private var closed = false

                override fun write(value: Int) {
                    if (writeError) {
                        throw IOException("disk full")
                    }
                    bytes.write(value)
                }

                override fun close() {
                    if (closed) {
                        return
                    }
                    closed = true
                    events.add("close")
                    if (closeError) {
                        throw IOException("close failed")
                    }
                }
            }
        }

        override fun publish() {
            events.add("publish")
            publishError?.let { throw it }
            published = true
        }

        override fun discard() {
            events.add("discard")
            discardError?.let { throw it }
            discarded = true
        }
    }

    private companion object {
        const val FILE_NAME = "dashcast-logs-1791630000000-a1b2c3d4.zip"

        fun readArchive(data: ByteArray): Map<String, String> {
            val contents = LinkedHashMap<String, String>(2)
            ZipInputStream(ByteArrayInputStream(data)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    contents[entry.name] = String(input.readAllBytes(), Charsets.UTF_8)
                }
            }
            return contents
        }
    }
}
