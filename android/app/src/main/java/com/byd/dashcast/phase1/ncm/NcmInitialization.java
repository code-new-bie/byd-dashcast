package com.byd.dashcast.phase1.ncm;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capability-driven CDC NCM negotiation, while the data interface is inactive.
 * Never probes a data-interface index or sends unadvertised optional requests.
 * @author Codex
 * @date 2026/10/11
 */
public final class NcmInitialization {
    private static final int CONTROL_TIMEOUT_MS = 1000;
    private static final int NTB_PARAMETER_BYTES = 28;
    private static final int MIN_NTB_BYTES = 2048;
    private static final int MAX_NTB16_BYTES = 65535;
    private static final int PACKET_FILTER_CAPABILITY = 1;
    private static final int CRC_CAPABILITY = 0x10;
    private static final int INPUT_DATAGRAM_CAPABILITY = 0x20;

    /** USB adapter; platform operations are injected for independent JVM tests. */
    public interface Io {
        /** Selects the already-claimed data interface's alternate setting. */
        void alternate(int setting) throws IOException;

        /** Performs one bounded, interface-scoped class request. */
        int control(int type, int request, int value, int index, byte[] buffer, int timeoutMs)
                throws IOException;
    }

    /** Negotiated bounds for the NTB16/no-CRC framing implementation. */
    public static final class Parameters {
        public final int inBytes;
        public final int outBytes;
        public final int outAlignment;
        public final int outDivisor;
        public final int outRemainder;
        public final int maxDatagrams;
        private final int formats;

        private Parameters(byte[] data) throws IOException {
            formats = u16(data, 2);
            long input = u32(data, 4);
            long output = u32(data, 16);
            outDivisor = u16(data, 20);
            outRemainder = u16(data, 22);
            outAlignment = u16(data, 24);
            int datagrams = u16(data, 26);
            if (u16(data, 0) != NTB_PARAMETER_BYTES || (formats != 1 && formats != 3)
                    || input < MIN_NTB_BYTES || output < MIN_NTB_BYTES
                    || outAlignment < 4 || outAlignment > 64 || outDivisor < 1 || outDivisor > 64
                    || outRemainder >= outDivisor) {
                throw new IOException("GET_NTB_PARAMETERS returned unsupported NTB16 bounds");
            }
            inBytes = (int)Math.min(input, 16384);
            outBytes = (int)Math.min(output, 32768);
            maxDatagrams = datagrams == 0 ? 8 : Math.min(datagrams, 32);
        }
    }

    private NcmInitialization() {
    }

    /** Completes all mandatory negotiation before activating bulk endpoints. */
    public static Parameters initialize(Io io, NcmDescriptor descriptor, AtomicBoolean cancelled)
            throws IOException {
        if (io == null || descriptor == null || cancelled == null) {
            throw new IllegalArgumentException("Missing NCM negotiation dependency");
        }
        checkCancellation(cancelled);
        io.alternate(0);
        byte[] data = new byte[NTB_PARAMETER_BYTES];
        request(io, descriptor, cancelled, 0xa1, 0x80, 0, data, "GET_NTB_PARAMETERS");
        Parameters parameters = new Parameters(data);
        LogHub.i("ncm", "CAPABILITIES=0x" + Integer.toHexString(descriptor.capabilities)
                + " formats=" + parameters.formats + " control_if=" + descriptor.controlInterface
                + " data_if=" + descriptor.dataInterface);
        if ((parameters.formats & 2) != 0) {
            request(io, descriptor, cancelled, 0x21, 0x84, 0, null, "SET_NTB_FORMAT");
        } else {
            LogHub.i("ncm", "SKIP_SET_NTB_FORMAT: device advertises NTB16 only");
        }
        if ((descriptor.capabilities & CRC_CAPABILITY) != 0) {
            request(io, descriptor, cancelled, 0x21, 0x8a, 0, null, "SET_CRC_MODE");
        } else {
            LogHub.i("ncm", "SKIP_SET_CRC_MODE: no CRC-mode capability; NCM0 framing");
        }
        boolean extended = (descriptor.capabilities & INPUT_DATAGRAM_CAPABILITY) != 0;
        byte[] inputSize = new byte[extended ? 8 : 4];
        put32(inputSize, Math.min(parameters.inBytes, MAX_NTB16_BYTES));
        if (extended) {
            inputSize[4] = 8;
        }
        request(io, descriptor, cancelled, 0x21, 0x86, 0, inputSize, "SET_NTB_INPUT_SIZE");
        if ((descriptor.capabilities & PACKET_FILTER_CAPABILITY) != 0) {
            request(io, descriptor, cancelled, 0x21, 0x43, 0x0f, null, "SET_ETHERNET_PACKET_FILTER");
        } else {
            LogHub.i("ncm", "SKIP_SET_ETHERNET_PACKET_FILTER: capability absent");
        }
        checkCancellation(cancelled);
        io.alternate(1);
        LogHub.i("ncm", "NCM_INITIALIZED: mandatory negotiation accepted; TCP/video not yet verified");
        return parameters;
    }

    private static void request(Io io, NcmDescriptor descriptor, AtomicBoolean cancelled, int type,
            int request, int value, byte[] buffer, String name) throws IOException {
        checkCancellation(cancelled);
        int expected = buffer == null ? 0 : buffer.length;
        int result = io.control(type, request, value, descriptor.controlInterface, buffer, CONTROL_TIMEOUT_MS);
        LogHub.i("ncm", name + " index=" + descriptor.controlInterface
                + " bytes=" + expected + " -> " + result);
        if (result != expected) {
            throw new IOException(name + " rejected: returned=" + result + " expected=" + expected);
        }
    }

    private static void checkCancellation(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("NCM negotiation cancelled; no next control request sent");
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | (data[offset + 1] & 0xffL) << 8
                | (data[offset + 2] & 0xffL) << 16 | (data[offset + 3] & 0xffL) << 24;
    }

    private static void put32(byte[] data, int value) {
        for (int index = 0; index < 4; index++) {
            data[index] = (byte)(value >>> (8 * index));
        }
    }
}
