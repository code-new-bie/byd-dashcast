package com.byd.dashcast.phase1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live receiver identity/action matching across fragmented dumps and bounded lines.
 * @author Codex
 * @date 2026/10/10
 */
class NavigationReceiverProbeTest {
    @Test
    fun findsActualProcessAndRegisteredActionWithOneByteChunks() {
        val probe = NavigationReceiverProbe()
        for (octet in LIVE.toByteArray(Charsets.UTF_8)) {
            probe.onOutput(byteArrayOf(octet), 1)
        }
        assertTrue(probe.isRegistered())
        assertTrue(probe.isRegistered())
    }

    @Test
    fun doesNotConfuseAnotherProcessWithOriginalService() {
        assertFalse(parse(LIVE.replace("com.example.amapservice", "notcom.example.amapservice")))
        assertFalse(parse(LIVE.replace("AUTONAVI_STANDARD_BROADCAST_SEND", "DEBUG_CASE")))
    }

    @Test
    fun doesNotMatchHistoryOrResolverTableAsALiveReceiver() {
        assertFalse(parse(LIVE.replace("Registered Receivers:", "Historical broadcasts [foreground]:")))
        assertFalse(parse("Registered Receivers:\nReceiver Resolver Table:\n" + LIVE.substring(LIVE.indexOf("  *"))))
    }

    @Test
    fun doesNotCombineDifferentReceiverBlocks() {
        val dump = LIVE.substring(0, LIVE.indexOf("      Action:")) +
            "  * ReceiverList{abcd}\n    app=1:other.app/1000 pid=1 uid=1000 user=0\n" +
            "      Action: \"AUTONAVI_STANDARD_BROADCAST_SEND\"\n"
        assertFalse(parse(dump))
    }

    @Test
    fun ignoresOversizedLinesAndHandlesEofWithoutNewline() {
        // java.lang.String.trim, as in the Java version of this test.
        assertTrue(parse("x".repeat(9000) + "\n" + LIVE.trim { it <= ' ' }))
        assertFalse(parse("Registered Receivers:\n" + "x".repeat(9000)))
    }

    private companion object {
        const val LIVE = "ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)\n" +
            "  Registered Receivers:\n  * ReceiverList{abcd 123 1000/u0 local:efgh}\n" +
            "    app=123:com.example.amapservice/1000 pid=123 uid=1000 user=0\n" +
            "    Filter #0: BroadcastFilter{abcd}\n" +
            "      Action: \"AUTONAVI_STANDARD_BROADCAST_SEND\"\n"

        fun parse(dump: String): Boolean {
            val probe = NavigationReceiverProbe()
            val bytes = dump.toByteArray(Charsets.UTF_8)
            probe.onOutput(bytes, bytes.size)
            return probe.isRegistered()
        }
    }
}
