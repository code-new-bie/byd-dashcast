package com.byd.dashcast.phase1

import java.io.IOException
import java.io.OutputStream
import java.util.Objects
import java.util.UUID
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Publishes a bounded diagnostic snapshot only after its ZIP is completely closed.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal object DownloadLogExporter {
    const val MAX_REPORT_CHARS = 512 * 1024
    const val MAX_USB_LOG_CHARS = 256 * 1024
    const val MAX_RELAY_CHARS = 64 * 1024
    private const val NAME_SUFFIX_LENGTH = 8
    private const val TRUNCATION_NOTICE = "[Earlier log text omitted; newest snapshot follows.]\n"
    private val FILE_NAME_PATTERN = Pattern.compile("dashcast-logs-[0-9]{1,19}-[0-9a-f]{8}\\.zip")

    /** A destination that creates only new, initially unpublished files. */
    interface Store {
        /**
         * @param fileName validated unique ZIP name
         * @return the newly created entry, never an existing user file
         * @throws IOException when creation fails
         */
        @Throws(IOException::class)
        fun create(fileName: String): Entry?
    }

    /** One entry owned by this export attempt; failure cleanup cannot target other files. */
    interface Entry {
        /** @return write stream; @throws IOException when opening fails */
        @Throws(IOException::class)
        fun open(): OutputStream?

        /** Makes the complete file visible; @throws IOException if publication is unconfirmed. */
        @Throws(IOException::class)
        fun publish()

        /** Removes this attempt's entry only; @throws IOException if cleanup is unconfirmed. */
        @Throws(IOException::class)
        fun discard()
    }

    /** @param nowMillis nonnegative export time; @return collision-resistant, path-free file name */
    @JvmStatic
    fun fileName(nowMillis: Long): String {
        if (nowMillis < 0) {
            throw IllegalArgumentException("Export timestamp must be nonnegative")
        }
        return "dashcast-logs-" + nowMillis + "-" + UUID.randomUUID().toString().substring(0, NAME_SUFFIX_LENGTH) + ".zip"
    }

    /**
     * @param store new-file-only destination
     * @param fileName generated ZIP name, with no directory components
     * @param report captured APK diagnostic log; no video or new system probes are collected
     * @param usbLog captured USB/NCM log
     * @throws IOException if writing, closing, publishing or failure cleanup is not confirmed
     */
    @JvmStatic
    @Throws(IOException::class)
    fun export(store: Store?, fileName: String?, report: String?, usbLog: String?) {
        exportInternal(store, fileName, report, usbLog, null, null, null, null)
    }

    /** Exports sanitized read-only evidence as fixed ZIP entries, never raw system logcat. */
    @JvmStatic
    @Throws(IOException::class)
    fun export(store: Store?, fileName: String?, report: String?, usbLog: String?,
        relayEvidence: String?, androidUsbEvidence: String?) {
        Objects.requireNonNull(relayEvidence, "Relay snapshot required")
        Objects.requireNonNull(androidUsbEvidence, "Android USB snapshot required")
        exportInternal(store, fileName, report, usbLog, relayEvidence, androidUsbEvidence, null, null)
    }

    /** Also carries the instrument's own logs as usb.log and cast.log. */
    @JvmStatic
    @Throws(IOException::class)
    fun export(store: Store?, fileName: String?, report: String?, usbLog: String?,
        relayEvidence: String?, androidUsbEvidence: String?, instrumentUsbLog: String?, instrumentCastLog: String?) {
        Objects.requireNonNull(relayEvidence, "Relay snapshot required")
        Objects.requireNonNull(androidUsbEvidence, "Android USB snapshot required")
        Objects.requireNonNull(instrumentUsbLog, "Instrument USB log required")
        Objects.requireNonNull(instrumentCastLog, "Instrument cast log required")
        exportInternal(store, fileName, report, usbLog, relayEvidence, androidUsbEvidence, instrumentUsbLog, instrumentCastLog)
    }

    @Throws(IOException::class)
    private fun exportInternal(store: Store?, fileName: String?, report: String?, usbLog: String?,
        relayEvidence: String?, androidUsbEvidence: String?, instrumentUsbLog: String?, instrumentCastLog: String?) {
        Objects.requireNonNull(store, "Export store required")
        Objects.requireNonNull(report, "Report snapshot required")
        Objects.requireNonNull(usbLog, "USB snapshot required")
        if (fileName == null || !FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw IllegalArgumentException("Invalid diagnostic ZIP name")
        }
        val entry = store!!.create(fileName) ?: throw IOException("Downloads provider did not create a file")
        try {
            // The raw stream also owns a close guard: ZIP finalization can fail before closing it.
            entry.open().use { destination ->
                if (destination == null) {
                    throw IOException("Downloads write stream unavailable")
                }
                ZipOutputStream(destination).use { archive ->
                    writeText(archive, "report.txt", report!!, MAX_REPORT_CHARS)
                    writeText(archive, "usb_ncm.txt", usbLog!!, MAX_USB_LOG_CHARS)
                    if (relayEvidence != null) {
                        writeText(archive, "navigation_relay.txt", relayEvidence, MAX_RELAY_CHARS)
                        writeText(archive, "android_usb_state.txt", androidUsbEvidence!!, MAX_RELAY_CHARS)
                    }
                    if (instrumentUsbLog != null) {
                        writeText(archive, "usb.log", instrumentUsbLog, MAX_RELAY_CHARS)
                        writeText(archive, "cast.log", instrumentCastLog!!, MAX_RELAY_CHARS)
                    }
                }
            }
            entry.publish()
        } catch (error: IOException) {
            discardAfter(entry, error)
            throw error
        } catch (error: RuntimeException) {
            discardAfter(entry, error)
            throw error
        }
    }

    /** Removes the incomplete entry; a failed removal is reported together with the original failure. */
    @Throws(IOException::class)
    private fun discardAfter(entry: Entry, error: Exception) {
        try {
            entry.discard()
        } catch (cleanupError: IOException) {
            throw unconfirmed(error, cleanupError)
        } catch (cleanupError: RuntimeException) {
            throw unconfirmed(error, cleanupError)
        }
    }

    private fun unconfirmed(error: Exception, cleanupError: Exception): IOException {
        val unconfirmed = IOException("Export failed; incomplete Downloads entry cleanup unconfirmed: " + error.message, error)
        unconfirmed.addSuppressed(cleanupError)
        return unconfirmed
    }

    @Throws(IOException::class)
    private fun writeText(archive: ZipOutputStream, name: String, text: String, maximum: Int) {
        val bounded = if (text.length > maximum) TRUNCATION_NOTICE + text.substring(text.length - maximum) else text
        archive.putNextEntry(ZipEntry(name))
        archive.write(bounded.toByteArray(Charsets.UTF_8))
        archive.closeEntry()
    }
}
