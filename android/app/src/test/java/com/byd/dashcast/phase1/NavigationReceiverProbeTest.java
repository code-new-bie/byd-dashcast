package com.byd.dashcast.phase1;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Live receiver identity/action matching across fragmented dumps and bounded lines.
 * @author Codex
 * @date 2026/10/10
 */
public final class NavigationReceiverProbeTest {
    private static final String LIVE = "ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)\n"
            + "  Registered Receivers:\n  * ReceiverList{abcd 123 1000/u0 local:efgh}\n"
            + "    app=123:com.example.amapservice/1000 pid=123 uid=1000 user=0\n"
            + "    Filter #0: BroadcastFilter{abcd}\n"
            + "      Action: \"AUTONAVI_STANDARD_BROADCAST_SEND\"\n";

    @Test
    public void findsActualProcessAndRegisteredActionWithOneByteChunks() throws Exception {
        NavigationReceiverProbe probe = new NavigationReceiverProbe();
        for (byte octet : LIVE.getBytes(StandardCharsets.UTF_8)) {
            probe.onOutput(new byte[] {octet}, 1);
        }
        assertTrue(probe.isRegistered());
        assertTrue(probe.isRegistered());
    }

    @Test
    public void doesNotConfuseAnotherProcessWithOriginalService() throws Exception {
        assertFalse(parse(LIVE.replace("com.example.amapservice", "notcom.example.amapservice")));
        assertFalse(parse(LIVE.replace("AUTONAVI_STANDARD_BROADCAST_SEND", "DEBUG_CASE")));
    }

    @Test
    public void doesNotMatchHistoryOrResolverTableAsALiveReceiver() throws Exception {
        assertFalse(parse(LIVE.replace("Registered Receivers:", "Historical broadcasts [foreground]:")));
        assertFalse(parse("Registered Receivers:\nReceiver Resolver Table:\n" + LIVE.substring(LIVE.indexOf("  *"))));
    }

    @Test
    public void doesNotCombineDifferentReceiverBlocks() throws Exception {
        String dump = LIVE.substring(0, LIVE.indexOf("      Action:"))
                + "  * ReceiverList{abcd}\n    app=1:other.app/1000 pid=1 uid=1000 user=0\n"
                + "      Action: \"AUTONAVI_STANDARD_BROADCAST_SEND\"\n";
        assertFalse(parse(dump));
    }

    @Test
    public void ignoresOversizedLinesAndHandlesEofWithoutNewline() throws Exception {
        assertTrue(parse("x".repeat(9000) + "\n" + LIVE.trim()));
        assertFalse(parse("Registered Receivers:\n" + "x".repeat(9000)));
    }

    private static boolean parse(String dump) throws Exception {
        NavigationReceiverProbe probe = new NavigationReceiverProbe();
        byte[] bytes = dump.getBytes(StandardCharsets.UTF_8);
        probe.onOutput(bytes, bytes.length);
        return probe.isRegistered();
    }
}
