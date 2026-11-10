package com.byd.dashcast.phase1

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.util.Objects

/**
 * Android 10+ app-owned Downloads entries; no broad storage permission or path selection.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal class MediaStoreDownloadStore(resolver: ContentResolver?) : DownloadLogExporter.Store {
    /** Application resolver; no existing user files are queried or changed. */
    private val resolver: ContentResolver = Objects.requireNonNull(resolver, "Content resolver required")!!

    /**
     * @param fileName validated new export name
     * @return newly inserted pending entry
     * @throws IOException if the system Downloads provider cannot create the entry
     */
    @Throws(IOException::class)
    override fun create(fileName: String): DownloadLogExporter.Entry {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val entry = resolver.insert(collection, values) ?: throw IOException("System Downloads provider unavailable")
        return PendingDownload(resolver, entry)
    }

    /** Newly inserted app-owned row; deletion is restricted to this exact URI. */
    private class PendingDownload(private val resolver: ContentResolver, private val uri: Uri) : DownloadLogExporter.Entry {
        /** @return new file's stream; @throws IOException if the provider cannot open it */
        @Throws(IOException::class)
        override fun open(): OutputStream? = resolver.openOutputStream(uri, "w")

        /** @throws IOException if the provider does not confirm exactly one completed row */
        @Throws(IOException::class)
        override fun publish() {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Downloads file publication not confirmed")
            }
        }

        /** @throws IOException if removal of this newly created incomplete entry is unconfirmed */
        @Throws(IOException::class)
        override fun discard() {
            if (resolver.delete(uri, null, null) != 1) {
                throw IOException("Incomplete Downloads entry removal not confirmed")
            }
        }
    }
}
