package com.byd.dashcast.phase1;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Binary video never contains shell-v2 STDERR/EXIT framing, even across arbitrary ADB chunks. */
public final class ShellV2OutputTest {
    @Test
    public void nonzeroExitIsConfirmedTerminationButStillCommandFailure() throws Exception {
        ShellV2Output decoder = new ShellV2Output((bytes, count) -> {});
        byte[] wire = frame(3, new byte[] {1});
        decoder.onOutput(wire, wire.length);
        assertTrue(decoder.exitConfirmed());
        assertThrows(IOException.class, decoder::requireSuccess);
    }

    @Test
    public void keepsCleanupEvidenceAtTheTailOfLongDiagnostics() throws Exception {
        List<String> logs = new ArrayList<>();
        ShellV2Output decoder = new ShellV2Output((bytes, count) -> {}, logs::add);
        byte[] stderr = new byte[9000];
        java.util.Arrays.fill(stderr, (byte)'x');
        byte[] marker = "\nSHELL_CAPTURE_RESOURCES_RELEASED\n".getBytes(StandardCharsets.UTF_8);
        byte[] wire = join(frame(2, stderr), frame(2, marker), frame(3, new byte[] {0}));
        decoder.onOutput(wire, wire.length);
        decoder.requireSuccess();
        assertTrue(logs.get(0).contains("SHELL_CAPTURE_RESOURCES_RELEASED"));
        assertTrue(logs.get(0).length() < 4300);
    }

    @Test
    public void preservesBinaryStdoutAndDiscardsStderrAcrossSingleByteChunks() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> raw.write(bytes, 0, length));
        byte[] stdout = {0, 0, 0, 1, 0x65, (byte)0xff, 0, 0, 3, 1};
        byte[] wire = join(frame(1, stdout), frame(2, new byte[] {99, 98, 97}), frame(3, new byte[] {0}));
        for (byte value : wire) {
            decoder.onOutput(new byte[] {value}, 1);
        }
        decoder.requireSuccess();
        assertArrayEquals(stdout, raw.toByteArray());
        assertEquals(3, decoder.stderrBytes());
    }

    @Test
    public void eofAndNonzeroExitAreNotSuccessfulCapture() throws Exception {
        ShellV2Output missing = new ShellV2Output((bytes, length) -> {});
        missing.onOutput(frame(1, new byte[] {1}), 6);
        assertThrows(IOException.class, missing::requireSuccess);
        ShellV2Output failure = new ShellV2Output((bytes, length) -> {});
        failure.onOutput(frame(3, new byte[] {7}), 6);
        assertThrows(IOException.class, failure::requireSuccess);
    }

    @Test
    public void truncatedHeaderOrPayloadCannotBecomeZeroExit() throws Exception {
        for (byte[] wire : new byte[][] {{1, 4}, header(1, 4), join(header(1, 4), new byte[] {0, 0})}) {
            ShellV2Output decoder = new ShellV2Output((bytes, length) -> {});
            decoder.onOutput(wire, wire.length);
            assertThrows(IOException.class, decoder::requireSuccess);
        }
    }

    @Test
    public void invalidChannelOversizeUnsignedLengthAndWrongExitLengthAreRejected() {
        for (byte[] wire : new byte[][] {header(0, 1), header(5, 1), header(1, 1_048_577),
                header(2, -1), header(3, 0), header(3, 4)}) {
            ShellV2Output decoder = new ShellV2Output((bytes, length) -> {});
            assertThrows(IOException.class, () -> decoder.onOutput(wire, wire.length));
            assertThrows(IOException.class, decoder::requireSuccess);
        }
    }

    @Test
    public void duplicateExitAndDataAfterExitInvalidateTheRun() throws Exception {
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> {});
        byte[] exit = frame(3, new byte[] {0});
        decoder.onOutput(exit, exit.length);
        decoder.requireSuccess();
        assertThrows(IOException.class, () -> decoder.onOutput(exit, exit.length));
        assertThrows(IOException.class, decoder::requireSuccess);
    }

    @Test
    public void zeroLengthOutputFramesAndMultipleFramesPerChunkAreValid() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> raw.write(bytes, 0, length));
        byte[] wire = join(header(1, 0), header(2, 0), frame(1, new byte[] {4}), frame(3, new byte[] {0}));
        decoder.onOutput(wire, wire.length);
        decoder.requireSuccess();
        assertArrayEquals(new byte[] {4}, raw.toByteArray());
    }

    @Test
    public void invalidChunksAndObserverFailureAreNotSilentlyIgnored() {
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> { throw new IOException("sink failed"); });
        assertThrows(IllegalArgumentException.class, () -> decoder.onOutput(null, 0));
        assertThrows(IllegalArgumentException.class, () -> decoder.onOutput(new byte[1], 2));
        byte[] wire = frame(1, new byte[] {9});
        assertThrows(IOException.class, () -> decoder.onOutput(wire, wire.length));
        assertThrows(IOException.class, decoder::requireSuccess);
    }

    @Test
    public void requestedCaptureDiagnosticsAreBoundedAndNeverEnterVideo() throws Exception {
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        java.util.List<String> logs = new java.util.ArrayList<>();
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> video.write(bytes, 0, length), logs::add);
        byte[] text = new byte[5000]; java.util.Arrays.fill(text, (byte) 'x');
        byte[] wire = join(frame(2, text), frame(1, new byte[] {0, 0, 1}), frame(3, new byte[] {0}));
        decoder.onOutput(wire, wire.length); decoder.requireSuccess();
        assertArrayEquals(new byte[] {0, 0, 1}, video.toByteArray());
        assertEquals(5000, decoder.stderrBytes());
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("[stderr middle truncated]"));
        assertTrue(logs.get(0).length() < 4300);
    }

    @Test
    public void sinkFailureKeepsPreviouslyReceivedStderrAndReportsItOnlyOnce() throws Exception {
        java.util.List<String> logs = new java.util.ArrayList<>();
        ShellV2Output decoder = new ShellV2Output((bytes, length) -> {
            throw new IOException("instrument rejected frame");
        }, logs::add);
        byte[] wire = join(frame(2, new byte[] {'o', 'k'}), frame(1, new byte[] {1}));
        assertThrows(IOException.class, () -> decoder.onOutput(wire, wire.length));
        decoder.reportDiagnostics();
        assertThrows(IOException.class, decoder::requireSuccess);
        decoder.reportDiagnostics();
        assertEquals(java.util.Collections.singletonList("ADB_CAPTURE_STDERR ok"), logs);
    }

    private static byte[] frame(int channel, byte[] payload) {
        return join(header(channel, payload.length), payload);
    }

    private static byte[] header(int channel, int length) {
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)channel).putInt(length).array();
    }

    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value, 0, value.length);
        }
        return output.toByteArray();
    }
}
