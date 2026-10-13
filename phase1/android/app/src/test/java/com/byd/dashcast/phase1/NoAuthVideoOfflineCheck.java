package com.byd.dashcast.phase1;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Actual Java streaming AVC framing -> DCV1; independent Python + ARM checks consume this. */
public final class NoAuthVideoOfflineCheck {
    private NoAuthVideoOfflineCheck() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("input.h264 output.dcv1");
        }
        byte[] source = Files.readAllBytes(Path.of(arguments[0]));
        try (OutputStream output = Files.newOutputStream(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW)) {
            output.write(NoAuthVideoProtocol.hello());
            output.write(NoAuthVideoProtocol.start());
            int[] sequence = {0};
            AvcReceiverContract contract = new AvcReceiverContract();
            AnnexBAccessUnitReader reader = new AnnexBAccessUnitReader((frame, key) -> {
                contract.accept(frame, message -> {});
                int index = ++sequence[0];
                output.write(NoAuthVideoProtocol.frame(frame, index, (index - 1) * 1000L / 30));
            });
            int[] sizes = {1, 2, 3, 7, 4096, 8191};
            int cursor = 0;
            int chunkIndex = 0;
            while (cursor < source.length) {
                int end = Math.min(source.length, cursor + sizes[chunkIndex++ % sizes.length]);
                byte[] chunk = Arrays.copyOfRange(source, cursor, end);
                reader.onOutput(chunk, chunk.length);
                cursor = end;
            }
            reader.finish();
            if (reader.frameCount() != 30 || sequence[0] != 30) {
                throw new IllegalStateException("Expected all 30 independent fixture pictures");
            }
            output.write(NoAuthVideoProtocol.heartbeat(1));
            output.write(NoAuthVideoProtocol.stop());
        }
    }
}
