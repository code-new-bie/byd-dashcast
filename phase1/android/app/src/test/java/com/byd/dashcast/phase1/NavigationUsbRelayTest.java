package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
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
 * One-shot OEM input, strict runtime profile and cancellation checks with no real car/ADB access.
 * @author Codex
 * @date 2026/10/10
 */
public final class NavigationUsbRelayTest {
    @Test
    public void sendsOneDirectedBroadcastAfterProfileIdentityAndReceiverChecks() throws Exception {
        FakeBackend backend = new FakeBackend();
        List<String> logs = new ArrayList<>();
        new NavigationUsbRelay(backend, new AtomicBoolean(), logs::add).request(InstrumentUsbCommand.NCM);
        assertEquals(Arrays.asList("profile", "id -u", "am get-current-user", NavigationUsbProtocol.START_SERVICE,
                "receiver", NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM)), backend.calls);
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("NAV_BROADCAST_SUBMITTED")));
        assertFalse(logs.stream().anyMatch(line -> line.contains("REQUEST_ACCEPTED")));
    }

    @Test
    public void protocolMatchesVerifiedOriginalBroadcastAndUtf16Command() throws Exception {
        assertEquals("am broadcast --user current -p com.example.amapservice"
                + " -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 0"
                + " --es NEXT_ROAD_NAME SCMD_USBMODE2",
                NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM));
        assertTrue(NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.HOST).endsWith("SCMD_USBMODE1"));
        assertEquals(0x43fa1008, NavigationUsbProtocol.NEXT_PATH_FEATURE);
        assertArrayEquals("SCMD_USBMODE2".getBytes(StandardCharsets.UTF_16LE), InstrumentUsbCommand.NCM.payload());
        NavigationUsbProtocol.verify(NavigationUsbProtocol.DEX_SHA256, 1140461576);
        assertThrows(IOException.class, () -> NavigationUsbProtocol.verify("unknown", 1140461576));
        assertThrows(IOException.class, () -> NavigationUsbProtocol.verify(NavigationUsbProtocol.DEX_SHA256, 1));
        assertThrows(IllegalArgumentException.class, () -> NavigationUsbProtocol.broadcastCommand(null));
    }

    @Test
    public void unsupportedProfileStopsBeforeAnyAdbCommand() {
        FakeBackend backend = new FakeBackend();
        backend.profileError = true;
        assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
        assertEquals(Arrays.asList("profile"), backend.calls);
    }

    @Test
    public void ordinaryAppUidNeverStartsTheServiceOrBroadcasts() {
        FakeBackend backend = new FakeBackend();
        backend.uid = "10123";
        assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
        assertEquals(Arrays.asList("profile", "id -u"), backend.calls);
    }

    @Test
    public void differentOrUnknownAndroidUserNeverSendsToTheWrongUser() {
        for (String user : Arrays.asList("10", "unknown", "")) {
            FakeBackend backend = new FakeBackend();
            backend.user = user;
            assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
            assertEquals(0, backend.broadcasts());
            assertFalse(backend.calls.contains(NavigationUsbProtocol.START_SERVICE));
        }
    }

    @Test
    public void serviceStartFailureNeverBroadcasts() {
        FakeBackend backend = new FakeBackend();
        backend.failedCommand = NavigationUsbProtocol.START_SERVICE;
        assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
        assertFalse(backend.calls.contains("receiver"));
        assertEquals(0, backend.broadcasts());
    }

    @Test
    public void pollsOnlyReadinessAndDoesNotRetryTheWrite() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.readyOn = 3;
        relay(backend).request(InstrumentUsbCommand.HOST);
        assertEquals(3, backend.receiverChecks);
        assertEquals(1, backend.broadcasts());
    }

    @Test
    public void missingReceiverFailsClosedAfterBoundedReadOnlyPolling() {
        FakeBackend backend = new FakeBackend();
        backend.readyOn = 99;
        assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
        assertEquals(6, backend.receiverChecks);
        assertEquals(0, backend.broadcasts());
    }

    @Test
    public void ambiguousBroadcastFailureIsNeverResent() {
        FakeBackend backend = new FakeBackend();
        backend.failedCommand = NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM);
        assertThrows(IOException.class, () -> relay(backend).request(InstrumentUsbCommand.NCM));
        assertEquals(1, backend.broadcasts());
    }

    @Test
    public void cancellationBeforeStartOrAfterReadinessNeverSends() {
        FakeBackend backend = new FakeBackend();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        NavigationUsbRelay relay = new NavigationUsbRelay(backend, cancelled, line -> {});
        assertThrows(IOException.class, () -> relay.request(InstrumentUsbCommand.NCM));
        assertTrue(backend.calls.isEmpty());
        cancelled.set(false);
        backend.cancelAfterReceiver = cancelled;
        assertThrows(IOException.class, () -> relay.request(InstrumentUsbCommand.NCM));
        assertEquals(0, backend.broadcasts());
    }

    private static NavigationUsbRelay relay(FakeBackend backend) {
        return new NavigationUsbRelay(backend, new AtomicBoolean(), line -> {});
    }

    private static final class FakeBackend implements NavigationUsbRelay.Backend {
        private final List<String> calls = new ArrayList<>();
        private String uid = "2000";
        private String user = "0";
        private String failedCommand;
        private boolean profileError;
        private int readyOn = 1;
        private int receiverChecks;
        private AtomicBoolean cancelAfterReceiver;

        @Override
        public void verifyProfile() throws IOException {
            calls.add("profile");
            if (profileError) {
                throw new IOException("unverified profile");
            }
        }

        @Override
        public String executeChecked(String command) throws IOException {
            calls.add(command);
            if (command.equals(failedCommand)) {
                throw new IOException("command completion unknown");
            }
            if ("id -u".equals(command)) {
                return uid;
            }
            return "am get-current-user".equals(command) ? user : "Broadcast completed: result=0";
        }

        @Override
        public boolean receiverRegistered() {
            calls.add("receiver");
            receiverChecks++;
            if (cancelAfterReceiver != null) {
                cancelAfterReceiver.set(true);
            }
            return receiverChecks >= readyOn;
        }

        @Override
        public void pause() {}

        private long broadcasts() {
            return calls.stream().filter(line -> line.startsWith("am broadcast")).count();
        }
    }
}
