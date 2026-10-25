package com.byd.dashcast.phase1;

import android.media.MediaCodec;

/** Fixed owned display source. Neither backend may capture the current primary foreground. */
interface RemoteCaptureSession extends AutoCloseable {
    void update(MediaCodec codec) throws Exception;
    boolean acceptFrame(long ptsMicros, boolean key) throws Exception;
    default boolean renderAllowed() { return true; }
    default long renderSourceEpochNanos() { return 0L; }
}
