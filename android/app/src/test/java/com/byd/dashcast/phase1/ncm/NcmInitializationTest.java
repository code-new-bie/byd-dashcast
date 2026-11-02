package com.byd.dashcast.phase1.ncm;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Exact reference-2 descriptor/request regressions; no Android/USB runtime is involved. */
public final class NcmInitializationTest {
    // Byte-for-byte HS configuration at VA 0xa12c in reference-2 devu-usbncm DLL.
    private static final String REFERENCE_CONFIG =
            "0902560002010440000904000001020d000605240010010524060001"
                    + "0d240f0800000000ea0500000006241a00010007058103100004"
                    + "09040100000a00010709040101020a0001070705820200020007050302000200";

    @Test
    public void exactReferenceDescriptorHasNoOptionalControlCapabilities() throws Exception {
        NcmDescriptor descriptor = NcmDescriptor.parse(referenceConfig());
        assertEquals(0, descriptor.controlInterface);
        assertEquals(1, descriptor.dataInterface);
        assertEquals(0, descriptor.capabilities);
        assertEquals(0x0100, descriptor.version);
        assertEquals(1514, descriptor.maxSegmentBytes);
    }

    @Test
    public void qnxMinimalRequestsSucceedAndDataActivatesLast() throws Exception {
        FakeIo io = new FakeIo();
        NcmInitialization.Parameters negotiated = initialize(io, 0);
        assertEquals(Arrays.asList("ALT0", "a1:80:index0:28", "21:86:index0:4", "ALT1"), io.calls);
        assertEquals(16384, negotiated.inBytes);
        assertEquals(32768, negotiated.outBytes);
        assertEquals(4, negotiated.outAlignment);
        assertEquals(4, negotiated.outDivisor);
        assertEquals(0, negotiated.outRemainder);
        assertEquals(8, negotiated.maxDatagrams);
        assertArrayEquals(new byte[] {0, 64, 0, 0}, io.inputSize);
    }

    @Test
    public void oldUnconditionalFormatRequestIsRejectedByExactQnxContract() throws Exception {
        FakeIo io = new FakeIo();
        assertEquals(-1, io.control(0x21, 0x84, 0, 0, null, 1000));
        assertEquals(-1, io.control(0x21, 0x8a, 0, 0, null, 1000));
        assertEquals(-1, io.control(0x21, 0x43, 15, 0, null, 1000));
        assertEquals(-1, io.control(0xa1, 0x80, 0, 1, new byte[28], 1000));
        assertFalse(io.active);
    }

    @Test
    public void capabilitiesAreUsedRatherThanManufacturerOrVidPidGuessing() throws Exception {
        FakeIo io = new FakeIo();
        io.optional = true;
        io.parameters[2] = 3;
        initialize(io, 0x31);
        assertEquals(Arrays.asList("ALT0", "a1:80:index0:28", "21:84:index0:0", "21:8a:index0:0",
                "21:86:index0:8", "21:43:index0:0", "ALT1"), io.calls);
        assertArrayEquals(new byte[] {0, 64, 0, 0, 8, 0, 0, 0}, io.inputSize);
    }

    @Test
    public void inputSizeRejectionDoesNotProbeOtherIndexesOrActivateData() throws Exception {
        FakeIo io = new FakeIo();
        io.reject = 0x86;
        IOException error = assertThrows(IOException.class, () -> initialize(io, 0));
        assertTrue(error.getMessage().contains("SET_NTB_INPUT_SIZE"));
        assertEquals(Arrays.asList("ALT0", "a1:80:index0:28", "21:86:index0:4"), io.calls);
        assertFalse(io.active);
    }

    @Test
    public void shortParameterReplyFailsBeforeAnySetRequest() {
        FakeIo io = new FakeIo();
        io.parameterLength = 27;
        assertThrows(IOException.class, () -> initialize(io, 0));
        assertEquals(Arrays.asList("ALT0", "a1:80:index0:28"), io.calls);
    }

    @Test
    public void invalidFramingBoundsFailBeforeInputSizeAndBulkActivation() {
        for (int offset : new int[] {0, 2, 4, 16, 20, 24}) {
            FakeIo io = new FakeIo();
            io.parameters[offset] = 0;
            io.parameters[offset + 1] = 0;
            assertThrows(IOException.class, () -> initialize(io, 0));
            assertEquals(Arrays.asList("ALT0", "a1:80:index0:28"), io.calls);
        }
        FakeIo io = new FakeIo();
        io.parameters[22] = 4;
        assertThrows(IOException.class, () -> initialize(io, 0));
    }

    @Test
    public void smallValidLimitsAreRespectedAndZeroDatagramCountIsNotRejected() throws Exception {
        FakeIo io = new FakeIo();
        put32(io.parameters, 4, 4096);
        put32(io.parameters, 16, 8192);
        NcmInitialization.Parameters parameters = initialize(io, 0);
        assertEquals(4096, parameters.inBytes);
        assertEquals(8192, parameters.outBytes);
        assertEquals(8, parameters.maxDatagrams);
        assertArrayEquals(new byte[] {0, 16, 0, 0}, io.inputSize);
    }

    @Test
    public void cancelledInitializationSendsNoControlOrAlternateWrites() {
        FakeIo io = new FakeIo();
        assertThrows(IOException.class, () -> NcmInitialization.initialize(io,
                NcmDescriptor.parse(referenceConfig()), new AtomicBoolean(true)));
        assertTrue(io.calls.isEmpty());
    }

    @Test
    public void cancellationDuringReadDoesNotPerformTheNextWrite() {
        FakeIo io = new FakeIo();
        AtomicBoolean cancelled = new AtomicBoolean();
        io.cancelOnRead = cancelled;
        assertThrows(IOException.class, () -> NcmInitialization.initialize(io,
                NcmDescriptor.parse(referenceConfig()), cancelled));
        assertEquals(Arrays.asList("ALT0", "a1:80:index0:28"), io.calls);
    }

    @Test
    public void inactiveAlternateFailureStopsBeforeClassRequests() {
        FakeIo io = new FakeIo();
        io.rejectAlternate = 0;
        assertThrows(IOException.class, () -> initialize(io, 0));
        assertEquals(Arrays.asList("ALT0"), io.calls);
    }

    @Test
    public void activeAlternateFailureDoesNotPretendBulkStarted() {
        FakeIo io = new FakeIo();
        io.rejectAlternate = 1;
        assertThrows(IOException.class, () -> initialize(io, 0));
        assertFalse(io.active);
    }

    @Test
    public void incompleteAmbiguousAndStorageDescriptorsAreRejectedBeforeIo() {
        assertThrows(IOException.class, () -> NcmDescriptor.parse(null));
        byte[] raw = referenceConfig();
        assertThrows(IOException.class, () -> NcmDescriptor.parse(Arrays.copyOf(raw, raw.length - 1)));
        for (int[] alteration : new int[][] {{0, 1}, {2, 0}, {26, 2}, {43, 0x1b},
                {45, 2}, {59, 8}, {81, 0x83}}) {
            byte[] changed = raw.clone();
            changed[alteration[0]] = (byte)alteration[1];
            assertThrows(IOException.class, () -> NcmDescriptor.parse(changed));
        }
    }

    @Test
    public void descriptorNamesAndConfigurationPowerAreCorrect() {
        String dump = UsbDesc.dump(referenceConfig());
        assertTrue(dump.contains("subtype=0x06 UNION"));
        assertTrue(dump.contains("subtype=0x0f ETHERNET"));
        assertTrue(dump.contains("subtype=0x1a NCM"));
        assertTrue(dump.contains("0mA"));
    }

    @Test
    public void retriesRetainEarlierLowLevelFailureEvidence() {
        LogHub.clear();
        LogHub.banner("VIDEO_ATTEMPT first");
        LogHub.e("ncm", "FIRST_FAILURE_GET_NTB_PARAMETERS");
        LogHub.banner("VIDEO_ATTEMPT second");
        assertTrue(LogHub.snapshot().contains("FIRST_FAILURE_GET_NTB_PARAMETERS"));
        assertTrue(LogHub.snapshot().contains("VIDEO_ATTEMPT second"));
    }

    private static NcmInitialization.Parameters initialize(FakeIo io, int capabilities) throws Exception {
        byte[] descriptor = referenceConfig();
        descriptor[46] = (byte)capabilities;
        return NcmInitialization.initialize(io, NcmDescriptor.parse(descriptor), new AtomicBoolean());
    }

    static byte[] referenceConfig() {
        byte[] data = new byte[REFERENCE_CONFIG.length() / 2];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte)Integer.parseInt(REFERENCE_CONFIG.substring(2 * index, 2 * index + 2), 16);
        }
        return data;
    }

    private static void put32(byte[] data, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            data[offset + index] = (byte)(value >>> (8 * index));
        }
    }

    private static final class FakeIo implements NcmInitialization.Io {
        private final List<String> calls = new ArrayList<>();
        private final byte[] parameters = new byte[28];
        private int reject = -1;
        private int rejectAlternate = -1;
        private int parameterLength = 28;
        private boolean optional;
        private boolean active;
        private byte[] inputSize;
        private AtomicBoolean cancelOnRead;

        private FakeIo() {
            parameters[0] = 28;
            parameters[2] = 1;
            put32(parameters, 4, 65535);
            parameters[8] = 1;
            parameters[12] = 4;
            put32(parameters, 16, 65535);
            parameters[20] = 4;
            parameters[24] = 4;
        }

        @Override
        public void alternate(int setting) throws IOException {
            calls.add("ALT" + setting);
            if (setting == rejectAlternate) {
                throw new IOException("SET_INTERFACE rejected");
            }
            active = setting == 1;
        }

        @Override
        public int control(int type, int request, int value, int index, byte[] buffer, int timeoutMs) {
            assertEquals(1000, timeoutMs);
            calls.add(Integer.toHexString(type) + ":" + Integer.toHexString(request)
                    + ":index" + index + ":" + (buffer == null ? 0 : buffer.length));
            if (index != 0 || request == reject || active) {
                return -1;
            }
            if (type == 0xa1 && request == 0x80) {
                System.arraycopy(parameters, 0, buffer, 0, parameterLength);
                if (cancelOnRead != null) {
                    cancelOnRead.set(true);
                }
                return parameterLength;
            }
            if (type == 0x21 && request == 0x86) {
                inputSize = buffer.clone();
                return buffer.length;
            }
            if (optional && type == 0x21 && (request == 0x84 || request == 0x8a || request == 0x43)) {
                return 0;
            }
            return -1;
        }
    }
}
