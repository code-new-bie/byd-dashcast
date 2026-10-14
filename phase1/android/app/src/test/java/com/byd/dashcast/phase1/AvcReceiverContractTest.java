package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AvcReceiverContractTest {
    private static final class Bits {
        final StringBuilder text = new StringBuilder();
        Bits raw(int value, int size) {
            for (int index = size - 1; index >= 0; index--) { text.append((value >>> index) & 1); }
            return this;
        }
        Bits ue(int value) {
            int size = 32 - Integer.numberOfLeadingZeros(value + 1);
            raw(0, size - 1); return raw(value + 1, size);
        }
        byte[] nal(int type) {
            raw(1, 1);
            while (text.length() % 8 != 0) { raw(0, 1); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0); out.write(0); out.write(1); out.write(0x60 | type);
            int zeros = 0;
            for (int i = 0; i < text.length(); i += 8) {
                int value = Integer.parseInt(text.substring(i, i + 8), 2);
                if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0; }
                out.write(value); zeros = value == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }
    }

    private byte[] frame(int profile, int width, int height, int spsId, int ppsId, boolean progressive) {
        byte[] sps = new Bits().raw(profile, 8).raw(0xc0, 8).raw(40, 8).ue(spsId)
                .ue(0).ue(2).ue(1).raw(0, 1).ue(width / 16 - 1).ue(height / 16 - 1)
                .raw(progressive ? 1 : 0, 1).raw(1, 1).raw(0, 1).nal(7);
        byte[] pps = new Bits().ue(ppsId).ue(spsId).nal(8);
        byte[] idr = new Bits().ue(0).ue(2).ue(ppsId).nal(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : new byte[][] {sps, pps, idr}) { out.write(nal, 0, nal.length); }
        return out.toByteArray();
    }

    @Test
    public void baselineProgressiveGeometryAndFirstIdrPassWithHeaderDiagnostics() throws Exception {
        AvcReceiverContract contract = new AvcReceiverContract();
        ArrayList<String> log = new ArrayList<>();
        contract.accept(frame(66, 1920, 720, 0, 0, true), log::add);
        contract.accept(new Bits().ue(0).ue(0).ue(0).nal(1), log::add);
        assertTrue(log.stream().anyMatch(text -> text.contains("profile=66")));
        assertTrue(log.stream().anyMatch(text -> text.contains("1920x720")));
    }

    @Test
    public void mainHighWrongSizeIdsOrInterlaceFailBeforeAnyMediaOperation() {
        byte[][] invalid = {frame(77, 1920, 720, 0, 0, true), frame(100, 1920, 720, 0, 0, true),
                frame(66, 1280, 720, 0, 0, true), frame(66, 640, 240, 1, 0, true),
                frame(66, 1920, 720, 0, 1, true), frame(66, 1920, 720, 0, 0, false),
                new Bits().ue(0).ue(2).ue(0).nal(5)};
        for (byte[] data : invalid) {
            assertThrows(IOException.class, () -> new AvcReceiverContract().accept(data, text -> {}));
        }
    }

    @Test
    public void rejectMalformedTruncatedOversizeAndBSlices() throws Exception {
        for (byte[] data : new byte[][] {null, new byte[0], new byte[] {0, 0, 1, 0x67},
                new byte[0x200001], new byte[] {0, 0, 1, (byte) 0xe7, 1}}) {
            assertThrows(IOException.class, () -> new AvcReceiverContract().accept(data, text -> {}));
        }
        AvcReceiverContract contract = new AvcReceiverContract();
        contract.accept(frame(66, 1920, 720, 0, 0, true), text -> {});
        assertThrows(IOException.class, () -> contract.accept(new Bits().ue(0).ue(1).ue(0).nal(1), text -> {}));
    }
}
