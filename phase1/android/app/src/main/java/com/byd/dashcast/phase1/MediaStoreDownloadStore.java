package com.byd.dashcast.phase1;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Android 10+ app-owned Downloads entries; no broad storage permission or path selection.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class MediaStoreDownloadStore implements DownloadLogExporter.Store {
    private final ContentResolver resolver;

    /** @param resolver application resolver; no existing user files are queried or changed */
    MediaStoreDownloadStore(ContentResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "Content resolver required");
    }

    /**
     * @param fileName validated new export name
     * @return newly inserted pending entry
     * @throws IOException if the system Downloads provider cannot create the entry
     */
    @Override
    public DownloadLogExporter.Entry create(String fileName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri entry = resolver.insert(collection, values);
        if (entry == null) {
            throw new IOException("System Downloads provider unavailable");
        }
        return new PendingDownload(resolver, entry);
    }

    /** Newly inserted app-owned row; deletion is restricted to this exact URI. */
    private static final class PendingDownload implements DownloadLogExporter.Entry {
        private final ContentResolver resolver;
        private final Uri uri;

        private PendingDownload(ContentResolver resolver, Uri uri) {
            this.resolver = resolver;
            this.uri = uri;
        }

        /** @return new file's stream; @throws IOException if the provider cannot open it */
        @Override
        public OutputStream open() throws IOException {
            return resolver.openOutputStream(uri, "w");
        }

        /** @throws IOException if the provider does not confirm exactly one completed row */
        @Override
        public void publish() throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new IOException("Downloads file publication not confirmed");
            }
        }

        /** @throws IOException if removal of this newly created incomplete entry is unconfirmed */
        @Override
        public void discard() throws IOException {
            if (resolver.delete(uri, null, null) != 1) {
                throw new IOException("Incomplete Downloads entry removal not confirmed");
            }
        }
    }
}
