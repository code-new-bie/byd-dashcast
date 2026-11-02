package com.byd.dashcast.phase1;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Offline AIR/BCDE checks: fresh evidence, wrong commands, privacy and output bounds. */
public final class NavigationRelayEvidenceTest {
    private static final long START = 1791638746000L;

    @Test
    public void retainsFreshInputAndSetAttemptAcrossUtf8ChunkBoundaries() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        byte[] bytes = (log("1791638746.123", "AmapService", "下一道路名: SCMD_USBMODE2")
                + log("1791638746.124", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2")).getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            evidence.onOutput(new byte[] {value}, 1);
        }
        String result = evidence.snapshot();
        assertTrue(result.contains("fresh_oem_next_road_input_observed=true"));
        assertTrue(result.contains("fresh_oem_set_attempt_observed=true"));
        assertTrue(result.contains("NOT a CAN delivery"));
        assertTrue(evidence.summary().contains("no CAN/QNX/role acknowledgement"));
    }

    @Test
    public void rejectsStaleAndFarFutureSameCommand() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, log("1791638745.999", "AmapService", "下一道路名: SCMD_USBMODE2")
                + log("1791638806.001", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2"));
        assertTrue(evidence.snapshot().contains("fresh_oem_next_road_input_observed=false"));
        assertTrue(evidence.snapshot().contains("fresh_oem_set_attempt_observed=false"));
    }

    @Test
    public void doesNotRetainOtherRoadsOrArbitraryMessages() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, log("1791638746.101", "AmapService", "下一道路名: 私人家庭住址")
                + log("1791638746.102", "OtherApp", "下一道路名: SCMD_USBMODE2")
                + log("1791638746.103", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE1")
                + log("1791638746.104", "AmapService", "access_token=secret")
                + log("1791638746.105", "AmapService", "下一道路名: SCMD_USBMODE2 suffix"));
        String result = evidence.snapshot();
        assertFalse(result.contains("私人家庭住址"));
        assertFalse(result.contains("secret"));
        assertTrue(result.contains("fresh_oem_set_attempt_observed=false"));
        assertTrue(result.contains("fresh_oem_next_road_input_observed=false"));
    }

    @Test
    public void flagsTimeCorrelatedPermissionErrorWithoutCopyingRawMessage() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, "1791638746.102 1000 1001 E BYDAutoService: [setBuffer] permission deny secret-private-data\n");
        String result = evidence.snapshot();
        assertTrue(result.contains("permission_error_in_window=true"));
        assertTrue(result.contains("time-correlated only"));
        assertFalse(result.contains("secret-private-data"));
    }

    @Test
    public void capsLongLinesAndEventCountAndHandlesEof() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, log("1791638746.101", "AmapService", "下一道路名: SCMD_USBMODE2")
                .replace("\n", "x".repeat(8192) + "\n"));
        for (int count = 0; count < 500; count++) {
            feed(evidence, log("1791638746.102", "AmapService", "下一道路名: SCMD_USBMODE2"));
        }
        feed(evidence, log("1791638746.103", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2").trim());
        String result = evidence.snapshot();
        assertTrue(result.contains("discarded_oversize_lines=1"));
        assertTrue(result.contains("fresh_oem_set_attempt_observed=true"));
        assertTrue(result.length() < 5000);
        assertTrue(result.equals(evidence.snapshot()));
    }

    @Test
    public void malformedTimesAndChunksCannotBecomeSuccess() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, "NOT_EPOCH 1 2 D AmapService: 下一道路名: SCMD_USBMODE2\n");
        assertTrue(evidence.snapshot().contains("Not observed = UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> evidence.onOutput(null, 0));
        assertThrows(IllegalArgumentException.class, () -> evidence.onOutput(new byte[1], 2));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRelayEvidence(InstrumentUsbCommand.NCM, -1));
        assertThrows(NullPointerException.class, () -> new NavigationRelayEvidence(null, START));
    }

    @Test
    public void diagnosticCommandIsReadOnlyAndUsesTagAllowlist() {
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("-d"));
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("-t 256"));
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("'*:S'"));
        assertFalse(NavigationRelayEvidence.LOG_COMMAND.contains("-c"));
        assertFalse(NavigationRelayEvidence.LOG_COMMAND.contains("am broadcast"));
    }

    @Test
    public void failedLogcatExitIsNotHiddenByAnEmptySnapshot() throws Exception {
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(InstrumentUsbCommand.NCM, START);
        feed(evidence, "logcat: permission denied\nDASHCAST_EXIT_CODE=1\n");
        assertTrue(evidence.snapshot().contains("logcat_command_success=false"));
        assertFalse(evidence.hasProcessingEvidence());
        feed(evidence, "DASHCAST_EXIT_CODE=0\n");
        assertTrue(evidence.snapshot().contains("logcat_command_success=true"));
    }

    private static void feed(NavigationRelayEvidence evidence, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        evidence.onOutput(data, data.length);
    }

    private static String log(String timestamp, String tag, String message) {
        return timestamp + " 1000 1001 D " + tag + ": " + message + "\n";
    }
}
