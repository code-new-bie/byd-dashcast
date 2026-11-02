package com.byd.dashcast.phase1;

import java.io.Closeable;
import java.io.IOException;

/** Bounded binary NCM stream. Cleanup bypasses only the user's prior cancellation. */
interface NoAuthVideoTransport extends Closeable {
    void write(byte[] data) throws IOException;
    byte[] readExact(int count) throws IOException;
    void beginCleanup();
    @Override
    void close();
}
