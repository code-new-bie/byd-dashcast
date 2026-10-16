package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * In-memory ADB peer: authentication, strict framing/stream identity and no OPEN before trust.
 * @author Codex
 * @date 2026/10/10
 */
public final class AdbShellClientTest {
    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;
    private static final byte[] EMPTY = new byte[0];

    @Test
    public void alreadyTrustedKeySignsOnceAndExecutesOnlyAfterCnxn() throws Exception {
        FakeIdentity key = new FakeIdentity();
        byte[] challenge = new byte[20];
        Arrays.fill(challenge, (byte)0x47);
        Harness harness = new Harness(key);
        harness.run(concat(packet(AUTH, 1, 0, challenge), normalSession()));
        assertArrayEquals(challenge, key.token);
        assertEquals(1, key.signs);
        assertEquals(0, key.publications);
        assertEquals(Arrays.asList(CNXN, AUTH, OPEN, OKAY), harness.commands());
        assertEquals("2000\n", harness.collected.getText());
        assertEquals(2, harness.sentPackets().get(1).arg0);
    }

    @Test
    public void newKeyPublishesAfterRejectedSignatureAndWaitsForDeviceTrust() throws Exception {
        FakeIdentity key = new FakeIdentity();
        Harness harness = new Harness(key);
        harness.run(concat(packet(AUTH, 1, 0, new byte[20]), packet(AUTH, 1, 0, new byte[20]), normalSession()));
        assertEquals(1, key.signs);
        assertEquals(1, key.publications);
        assertEquals(Arrays.asList(CNXN, AUTH, AUTH, OPEN, OKAY), harness.commands());
        WirePacket published = harness.sentPackets().get(2);
        assertEquals(3, published.arg0);
        assertEquals(0, published.arg1);
        assertArrayEquals(key.publicKey(), published.payload);
        assertTrue(harness.log.stream().anyMatch(line -> line.startsWith("ADB_AUTH_CONFIRM")));
    }

    @Test
    public void unapprovedKeyAndEofNeverOpenACommand() {
        Harness harness = new Harness(new FakeIdentity());
        assertThrows(IOException.class, () -> harness.run(concat(packet(AUTH, 1, 0, new byte[20]),
                packet(AUTH, 1, 0, new byte[20]))));
        assertFalse(harness.commands().contains(OPEN));
    }

    @Test
    public void repeatedAuthAfterPublicationFailsWithoutUnlimitedRetries() {
        FakeIdentity key = new FakeIdentity();
        Harness harness = new Harness(key);
        assertThrows(IOException.class, () -> harness.run(concat(packet(AUTH, 1, 0, new byte[20]),
                packet(AUTH, 1, 0, new byte[20]), packet(AUTH, 1, 0, new byte[20]))));
        assertEquals(1, key.signs);
        assertEquals(1, key.publications);
        assertFalse(harness.commands().contains(OPEN));
    }

    @Test
    public void malformedChallengeNeverGetsSigned() {
        FakeIdentity key = new FakeIdentity();
        Harness harness = new Harness(key);
        assertThrows(IOException.class, () -> harness.run(packet(AUTH, 1, 0, new byte[19])));
        assertEquals(0, key.signs);
        assertFalse(harness.commands().contains(OPEN));
    }

    @Test
    public void openRejectedIsAnErrorNotAnEmptySuccessfulCommand() {
        Harness harness = new Harness(null);
        assertThrows(IOException.class, () -> harness.run(concat(connect(), packet(CLSE, 0, 1, EMPTY))));
    }

    @Test
    public void rejectsWrongStreamAndDataBeforeOpenAck() {
        for (byte[] stream : new byte[][] {
                concat(connect(), packet(OKAY, 7, 2, EMPTY)),
                concat(connect(), packet(WRTE, 7, 1, new byte[] {1})),
                concat(connect(), packet(OKAY, 7, 1, EMPTY), packet(WRTE, 8, 1, new byte[] {1})),
                concat(connect(), packet(OKAY, 7, 1, EMPTY), packet(CLSE, 8, 1, EMPTY))}) {
            Harness harness = new Harness(null);
            assertThrows(IOException.class, () -> harness.run(stream));
            assertEquals(0, harness.collected.getTotalBytes());
        }
    }

    @Test
    public void corruptMagicChecksumAndOversizedPayloadFailBeforeOpen() {
        byte[] magic = connect();
        magic[20] ^= 1;
        byte[] checksum = connect();
        checksum[16] ^= 1;
        for (byte[] stream : new byte[][] {magic, checksum, packet(CNXN, 0x01000000, 4096, new byte[4097])}) {
            Harness harness = new Harness(null);
            assertThrows(IOException.class, () -> harness.run(stream));
            assertFalse(harness.commands().contains(OPEN));
        }
    }

    @Test
    public void handlesSingleByteReadsAndNoAuthServer() throws Exception {
        Harness harness = new Harness(new FakeIdentity());
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(normalSession())) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        harness.client.executeProtocol(fragmented, harness.sent, "id -u", harness.collected);
        assertEquals(Arrays.asList(CNXN, OPEN, OKAY), harness.commands());
        assertEquals("2000\n", harness.collected.getText());
        assertEquals("exec:id -u\0", new String(harness.sentPackets().get(1).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void cancellationBeforeHandshakeWritesNothing() {
        Harness harness = new Harness(new FakeIdentity());
        harness.cancelled.set(true);
        assertThrows(IOException.class, () -> harness.run(normalSession()));
        assertTrue(harness.commands().isEmpty());
    }

    @Test
    public void cancellationAfterConnectNeverExecutesPendingCommand() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 2000, null,
                message -> cancelled.set(true), cancelled);
        assertThrows(IOException.class, () -> client.executeProtocol(new ByteArrayInputStream(normalSession()),
                sent, "id -u", (data, length) -> {}));
        assertEquals(24 + "host::\0".length(), sent.size());
    }

    @Test
    public void consumerFailureIsPropagatedWithoutSuccess() {
        Harness harness = new Harness(null);
        IOException error = assertThrows(IOException.class,
                () -> harness.client.executeProtocol(new ByteArrayInputStream(normalSession()), harness.sent,
                        "id -u", (data, length) -> { throw new IOException("sink failed"); }));
        assertEquals("sink failed", error.getMessage());
        assertEquals(Arrays.asList(CNXN, OPEN), harness.commands());
    }

    @Test
    public void oversizedServiceAndIncompatiblePeerNeverGetOpen() {
        Harness harness = new Harness(null);
        assertThrows(IOException.class, () -> harness.client.executeProtocol(new ByteArrayInputStream(connect()),
                harness.sent, "x".repeat(4096), harness.collected));
        assertFalse(harness.commands().contains(OPEN));
        Harness incompatible = new Harness(null);
        assertThrows(IOException.class, () -> incompatible.run(packet(CNXN, 0x00010000, 4096, EMPTY)));
        assertFalse(incompatible.commands().contains(OPEN));
    }

    @Test
    public void validatesInputsWithoutOpeningASocket() {
        assertThrows(IllegalArgumentException.class, () -> new AdbShellClient("", 5555, 10));
        assertThrows(IllegalArgumentException.class, () -> new AdbShellClient("127.0.0.1", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AdbShellClient("127.0.0.1", 5555, 0));
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 10);
        assertThrows(IllegalArgumentException.class, () -> client.executeShell("id\0reboot", (bytes, length) -> {}));
        assertThrows(IllegalArgumentException.class, () -> client.executeShell("id", null));
    }

    @Test
    public void missingAuthenticationIdentityNeverOpensCommand() {
        Harness harness = new Harness(null);
        assertThrows(IOException.class, () -> harness.run(packet(AUTH, 1, 0, new byte[20])));
        assertEquals(Arrays.asList(CNXN), harness.commands());
    }

    @Test
    public void socketReadTimeoutIsBoundedByTheWholeHandshakeDeadline() {
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 1);
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("simulated socket poll");
            }
        };
        assertThrows(IOException.class,
                () -> client.executeProtocol(stalled, new ByteArrayOutputStream(), "id -u", (bytes, length) -> {}));
    }

    @Test
    public void partialHeaderOrPayloadNeverCompletesTheCommand() {
        Harness header = new Harness(null);
        assertThrows(IOException.class, () -> header.run(new byte[12]));
        Harness data = new Harness(null);
        byte[] full = normalSession();
        assertThrows(IOException.class, () -> data.run(Arrays.copyOf(full, full.length - 12)));
    }

    @Test
    public void shellV2RequiresAdvertisedFeatureAndSeparatesExitFromStdout() throws Exception {
        Harness harness = new Harness(null);
        byte[] stdout = {1, 4, 0, 0, 0, 0, 0, 1, 0x65, 3, 1, 0, 0, 0, 0};
        byte[] peer = concat(packet(CNXN, 0x01000000, 4096,
                "device::features=cmd,shell_v2,stat_v2\0".getBytes(StandardCharsets.US_ASCII)),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, stdout), packet(CLSE, 7, 1, EMPTY));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        harness.client.executeShellV2Protocol(new ByteArrayInputStream(peer), harness.sent, "screenrecord -",
                (data, length) -> raw.write(data, 0, length));
        assertArrayEquals(new byte[] {0, 0, 1, 0x65}, raw.toByteArray());
        assertEquals("shell,v2,raw:screenrecord -\0",
                new String(harness.sentPackets().get(1).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void missingOrLookalikeShellV2FeatureNeverStartsCapture() {
        for (String banner : new String[] {"device::\0", "device::features=shell_v2_unsafe\0",
                "device::notfeatures=shell_v2\0"}) {
            Harness harness = new Harness(null);
            byte[] peer = packet(CNXN, 0x01000000, 4096, banner.getBytes(StandardCharsets.US_ASCII));
            assertThrows(IOException.class, () -> harness.client.executeShellV2Protocol(
                    new ByteArrayInputStream(peer), harness.sent, "screenrecord -", harness.collected));
            assertFalse(harness.commands().contains(OPEN));
        }
    }

    @Test
    public void shellV2TransportCloseWithoutExitCannotBecomeCaptureSuccess() {
        Harness harness = new Harness(null);
        byte[] peer = concat(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)),
                packet(OKAY, 7, 1, EMPTY), packet(CLSE, 7, 1, EMPTY));
        assertThrows(IOException.class, () -> harness.client.executeShellV2Protocol(
                new ByteArrayInputStream(peer), harness.sent, "screenrecord -", harness.collected));
    }

    private static byte[] connect() {
        return packet(CNXN, 0x01000000, 4096, "device::\0".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void dataProgressRenewsIdleDeadlineBeyondOldAbsoluteTimeout() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 20000, null,
                message -> {}, new AtomicBoolean(), clock::get);
        ByteArrayOutputStream peer = new ByteArrayOutputStream();
        peer.write(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)));
        peer.write(packet(OKAY, 7, 1, EMPTY));
        for (int index = 0; index < 360; index++) {
            peer.write(packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 42}));
        }
        peer.write(packet(WRTE, 7, 1, new byte[] {3, 1, 0, 0, 0, 0}));
        peer.write(packet(CLSE, 7, 1, EMPTY));
        final int[] packets = {0};
        client.executeControlledProtocol(new ByteArrayInputStream(peer.toByteArray()), new ByteArrayOutputStream(), "owned",
                (data, length) -> {
                    packets[0]++;
                    clock.addAndGet(5_000_000_000L);
                }, new AtomicBoolean());
        assertEquals(360, packets[0]);
        assertEquals(1_800_000_000_000L, clock.get());
    }

    @Test
    public void legacyDiagnosticsKeepAbsoluteDeadlineEvenIfPeerKeepsSendingData() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 20000, null,
                message -> {}, new AtomicBoolean(), clock::get);
        byte[] peer = concat(connect(), packet(OKAY, 7, 1, EMPTY),
                packet(WRTE, 7, 1, new byte[] {1}), packet(WRTE, 7, 1, new byte[] {1}),
                packet(WRTE, 7, 1, new byte[] {1}), packet(WRTE, 7, 1, new byte[] {1}),
                packet(WRTE, 7, 1, new byte[] {1}), packet(CLSE, 7, 1, EMPTY));
        final int[] consumed = {0};
        assertThrows(IOException.class, () -> client.executeProtocol(new ByteArrayInputStream(peer),
                new ByteArrayOutputStream(), "diagnostic", (data, length) -> {
                    consumed[0]++;
                    clock.addAndGet(5_000_000_000L);
                }));
        assertEquals(4, consumed[0]);
    }

    @Test
    public void liveStopSendsOnlyFixedStdinOnceAndStillRequiresSuccessfulShellExit() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Harness harness = new Harness(null);
        byte[] peer = concat(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 42}),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, new byte[] {3, 1, 0, 0, 0, 0}),
                packet(CLSE, 7, 1, EMPTY));
        harness.client.executeControlledProtocol(new ByteArrayInputStream(peer), harness.sent, "owned",
                (data, length) -> stop.set(true), stop);
        List<WirePacket> stdin = new ArrayList<>();
        for (WirePacket sent : harness.sentPackets()) {
            if (sent.command == WRTE) {
                stdin.add(sent);
            }
        }
        assertEquals(1, stdin.size());
        assertEquals(1, stdin.get(0).arg0);
        assertEquals(7, stdin.get(0).arg1);
        assertArrayEquals(new byte[] {0, 5, 0, 0, 0, 'S', 'T', 'O', 'P', '\n'}, stdin.get(0).payload);
    }

    @Test
    public void failedFrameConsumerRequestsOwnedStopAndDrainsTheSameAdbStream() throws Exception {
        Harness harness = new Harness(null);
        AtomicBoolean userStop = new AtomicBoolean();
        int[] consumerCalls = {0};
        int[] receiverStops = {0};
        byte[] peer = concat(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 42}),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 43}),
                packet(WRTE, 7, 1, new byte[] {2, 2, 0, 0, 0, 'o', 'k'}),
                packet(WRTE, 7, 1, new byte[] {3, 1, 0, 0, 0, 0}), packet(CLSE, 7, 1, EMPTY));
        ByteArrayInputStream input = new ByteArrayInputStream(peer);
        IOException error = assertThrows(IOException.class, () -> harness.client.executeControlledProtocol(
                input, harness.sent, "owned", (data, length) -> {
                    consumerCalls[0]++;
                    throw new IOException("MEDIA_SUBMIT rejected");
                }, userStop, () -> receiverStops[0]++));
        assertEquals("MEDIA_SUBMIT rejected", error.getMessage());
        assertEquals(0, input.available());
        assertEquals(1, consumerCalls[0]);
        assertEquals(1, receiverStops[0]);
        assertFalse(userStop.get());
        assertEquals(1, harness.sentPackets().stream().filter(item -> item.command == WRTE).count());
        assertTrue(harness.log.stream().anyMatch(item -> item.startsWith("CAPTURE_STOP_STDIN_SENT")));
    }

    @Test
    public void failedConsumerCannotExtendStopDeadlineWithEndlessOutputAndKeepsOriginalCause() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AtomicBoolean failed = new AtomicBoolean();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 20000, null,
                message -> {}, new AtomicBoolean(), clock::get);
        ByteArrayOutputStream peer = new ByteArrayOutputStream();
        peer.write(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)));
        peer.write(packet(OKAY, 7, 1, EMPTY));
        for (int index = 0; index < 100; index++) {
            peer.write(packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 42}));
        }
        InputStream input = new FilterInputStream(new ByteArrayInputStream(peer.toByteArray())) {
            @Override
            public int read(byte[] data, int offset, int length) throws IOException {
                if (failed.get()) {
                    clock.addAndGet(1_000_000_000L);
                }
                return super.read(data, offset, length);
            }
        };
        IOException root = new IOException("MEDIA_SUBMIT rejected");
        IOException result = assertThrows(IOException.class, () -> client.executeControlledProtocol(input,
                new ByteArrayOutputStream(), "owned", (bytes, length) -> {
                    failed.set(true);
                    throw root;
                }, new AtomicBoolean()));
        assertEquals(root, result);
        assertTrue(Arrays.stream(result.getSuppressed())
                .anyMatch(item -> "CAPTURE_STOP_EXIT_UNCONFIRMED".equals(item.getMessage())));
        assertTrue(clock.get() < 20_000_000_000L);
    }

    @Test
    public void stopDrainCannotBeKeptAliveForeverByFurtherStdout() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 20000, null,
                message -> {}, new AtomicBoolean(), clock::get);
        ByteArrayOutputStream peer = new ByteArrayOutputStream();
        peer.write(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)));
        peer.write(packet(OKAY, 7, 1, EMPTY));
        for (int index = 0; index < 10; index++) {
            peer.write(packet(WRTE, 7, 1, new byte[] {1, 1, 0, 0, 0, 42}));
        }
        IOException error = assertThrows(IOException.class, () -> client.executeControlledProtocol(
                new ByteArrayInputStream(peer.toByteArray()), new ByteArrayOutputStream(), "owned",
                (data, length) -> { stop.set(true); clock.addAndGet(5_000_000_000L); }, stop));
        assertEquals("CAPTURE_STOP_EXIT_UNCONFIRMED", error.getMessage());
    }

    @Test
    public void nativeStopHookRunsBeforeStdinAndItsCleanupDoesNotConsumeCaptureDrainBudget() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        AdbShellClient client = new AdbShellClient("127.0.0.1", 5555, 20000, null,
                message -> {}, new AtomicBoolean(), clock::get);
        byte[] peer = concat(packet(CNXN, 0x01000000, 4096,
                "device::features=shell_v2\0".getBytes(StandardCharsets.US_ASCII)),
                packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, new byte[] {3, 1, 0, 0, 0, 0}),
                packet(CLSE, 7, 1, EMPTY));
        final int[] called = {0};
        client.executeControlledProtocol(new ByteArrayInputStream(peer), sent, "owned", (data, length) -> {}, stop,
                () -> {
                    called[0]++;
                    assertFalse(new String(sent.toByteArray(), StandardCharsets.US_ASCII).contains("STOP\n"));
                    clock.addAndGet(12_000_000_000L);
                });
        assertEquals(1, called[0]);
        assertTrue(new String(sent.toByteArray(), StandardCharsets.US_ASCII).contains("STOP\n"));
    }

    private static byte[] normalSession() {
        return concat(connect(), packet(OKAY, 7, 1, EMPTY),
                packet(WRTE, 7, 1, "2000\n".getBytes(StandardCharsets.UTF_8)), packet(CLSE, 7, 1, EMPTY));
    }

    private static byte[] packet(int command, int arg0, int arg1, byte[] payload) {
        int checksum = 0;
        for (byte octet : payload) {
            checksum += octet & 0xff;
        }
        return ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(command)
                .putInt(arg0).putInt(arg1).putInt(payload.length).putInt(checksum).putInt(~command)
                .put(payload).array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part, 0, part.length);
        }
        return output.toByteArray();
    }

    private static final class Harness {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        private final AdbTextProbe collected = new AdbTextProbe(4096);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<String> log = new ArrayList<>();
        private final AdbShellClient client;

        private Harness(AdbAuthenticator identity) {
            client = new AdbShellClient("127.0.0.1", 5555, 2000, identity, log::add, cancelled);
        }

        private void run(byte[] input) throws IOException {
            client.executeProtocol(new ByteArrayInputStream(input), sent, "id -u", collected);
        }

        private List<Integer> commands() {
            List<Integer> commands = new ArrayList<>();
            for (WirePacket packet : sentPackets()) {
                commands.add(packet.command);
            }
            return commands;
        }

        private List<WirePacket> sentPackets() {
            ByteBuffer input = ByteBuffer.wrap(sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            List<WirePacket> packets = new ArrayList<>();
            while (input.hasRemaining()) {
                int command = input.getInt();
                int arg0 = input.getInt();
                int arg1 = input.getInt();
                int length = input.getInt();
                int checksum = input.getInt();
                assertEquals(~command, input.getInt());
                byte[] payload = new byte[length];
                input.get(payload);
                int actualChecksum = 0;
                for (byte octet : payload) {
                    actualChecksum += octet & 0xff;
                }
                assertEquals(checksum, actualChecksum);
                packets.add(new WirePacket(command, arg0, arg1, payload));
            }
            return packets;
        }
    }

    private static final class WirePacket {
        private final int command;
        private final int arg0;
        private final int arg1;
        private final byte[] payload;

        private WirePacket(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }

    private static final class FakeIdentity implements AdbAuthenticator {
        private int signs;
        private int publications;
        private byte[] token;

        @Override
        public byte[] sign(byte[] token) {
            signs++;
            this.token = token.clone();
            return new byte[256];
        }

        @Override
        public byte[] publicKey() {
            publications++;
            return "test-base64-key dashcast@headunit\0".getBytes(StandardCharsets.US_ASCII);
        }
    }
}
