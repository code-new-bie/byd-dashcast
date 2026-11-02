package com.byd.dashcast.phase1.ncm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop integration adapter for descriptor/parameter bytes produced by unchanged QNX Thumb code. */
public final class NcmOfflineHandshakeCheck {
    private NcmOfflineHandshakeCheck() {
    }

    public static void main(String[] args) throws Exception {
        byte[] configuration = Files.readAllBytes(Path.of(args[0]));
        byte[] parameters = Files.readAllBytes(Path.of(args[1]));
        List<String> trace = new ArrayList<>();
        NcmInitialization.Io io = new NcmInitialization.Io() {
            @Override
            public void alternate(int setting) {
                trace.add("ALT" + setting);
            }

            @Override
            public int control(int type, int request, int value, int index, byte[] buffer, int timeoutMs)
                    throws IOException {
                if (index != 0 || timeoutMs != 1000 || value != 0) {
                    throw new IOException("Wrong interface or timeout/value");
                }
                if (request == 0x80 && type == 0xa1 && buffer.length == 28) {
                    System.arraycopy(parameters, 0, buffer, 0, parameters.length);
                    trace.add("GET80");
                    return 28;
                }
                if (request == 0x86 && type == 0x21 && buffer.length == 4
                        && Arrays.equals(new byte[] {0, 64, 0, 0}, buffer)) {
                    trace.add("SET86_4B");
                    return 4;
                }
                throw new IOException("Original QNX driver stalls this class request");
            }
        };
        NcmInitialization.initialize(io, NcmDescriptor.parse(configuration), new AtomicBoolean());
        if (!trace.equals(Arrays.asList("ALT0", "GET80", "SET86_4B", "ALT1"))) {
            throw new AssertionError(trace);
        }
        System.out.println("PASS_ALT0_GET80_SET86_4B_ALT1");
    }
}
