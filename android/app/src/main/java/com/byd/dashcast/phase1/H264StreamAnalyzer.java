package com.byd.dashcast.phase1;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Streams Annex-B H.264 bytes to disk while counting key NAL units.
 *
 * @author Codex
 * @date 2026/10/09
 */
public final class H264StreamAnalyzer implements AdbShellClient.ShellOutputHandler {

    private final OutputStream outputStream;
    private long totalBytes;
    private int startCodeCount;
    private int spsCount;
    private int ppsCount;
    private int idrCount;
    private int sliceCount;
    private int zeroCount;
    private boolean waitingForNalHeader;

    /**
     * Creates an analyzer writing to the supplied stream.
     *
     * @param outputStream output stream
     */
    public H264StreamAnalyzer(OutputStream outputStream) {
        if (outputStream == null) {
            throw new IllegalArgumentException("H.264 output stream must not be null");
        }
        this.outputStream = outputStream;
    }

    /**
     * Accepts and analyzes one output chunk.
     *
     * @param data H.264 bytes
     * @param length valid byte count
     * @throws IOException when writing fails
     */
    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("invalid H.264 output chunk");
        }
        outputStream.write(data, 0, length);
        totalBytes += length;
        for (int index = 0; index < length; index++) {
            consumeByte(data[index] & 0xff);
        }
    }

    /**
     * Flushes the captured stream.
     *
     * @throws IOException when flushing fails
     */
    public void flush() throws IOException {
        outputStream.flush();
    }

    /**
     * Returns a point-in-time statistics snapshot.
     *
     * @return H.264 statistics
     */
    public Statistics snapshot() {
        return new Statistics(totalBytes, startCodeCount, spsCount, ppsCount,
                idrCount, sliceCount);
    }

    private void consumeByte(int value) {
        if (waitingForNalHeader) {
            countNal(value & 0x1f);
            waitingForNalHeader = false;
            zeroCount = 0;
            return;
        }
        if (value == 0) {
            zeroCount = Math.min(zeroCount + 1, 4);
            return;
        }
        if (value == 1 && zeroCount >= 2) {
            startCodeCount++;
            waitingForNalHeader = true;
        }
        zeroCount = 0;
    }

    private void countNal(int nalType) {
        if (nalType == 7) {
            spsCount++;
        } else if (nalType == 8) {
            ppsCount++;
        } else if (nalType == 5) {
            idrCount++;
        } else if (nalType == 1) {
            sliceCount++;
        }
    }

    /**
     * Immutable H.264 statistics.
     *
     * @author Codex
     * @date 2026/10/09
     */
    public static final class Statistics {

        private final long totalBytes;
        private final int startCodeCount;
        private final int spsCount;
        private final int ppsCount;
        private final int idrCount;
        private final int sliceCount;

        private Statistics(long totalBytes, int startCodeCount, int spsCount,
                int ppsCount, int idrCount, int sliceCount) {
            this.totalBytes = totalBytes;
            this.startCodeCount = startCodeCount;
            this.spsCount = spsCount;
            this.ppsCount = ppsCount;
            this.idrCount = idrCount;
            this.sliceCount = sliceCount;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getStartCodeCount() {
            return startCodeCount;
        }

        public int getSpsCount() {
            return spsCount;
        }

        public int getPpsCount() {
            return ppsCount;
        }

        public int getIdrCount() {
            return idrCount;
        }

        public int getSliceCount() {
            return sliceCount;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "bytes=%d startCodes=%d sps=%d pps=%d idr=%d slices=%d",
                    totalBytes, startCodeCount, spsCount, ppsCount, idrCount, sliceCount);
        }
    }
}
