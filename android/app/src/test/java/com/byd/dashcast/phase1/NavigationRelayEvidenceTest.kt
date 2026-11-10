package com.byd.dashcast.phase1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline AIR/BCDE checks: fresh evidence, wrong commands, privacy and output bounds. */
class NavigationRelayEvidenceTest {
    @Test
    fun retainsFreshInputAndSetAttemptAcrossUtf8ChunkBoundaries() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        val bytes = (log("1791638746.123", "AmapService", "下一道路名: SCMD_USBMODE2") +
            log("1791638746.124", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2")).toByteArray(Charsets.UTF_8)
        for (value in bytes) {
            evidence.onOutput(byteArrayOf(value), 1)
        }
        val result = evidence.snapshot()
        assertTrue(result.contains("fresh_oem_next_road_input_observed=true"))
        assertTrue(result.contains("fresh_oem_set_attempt_observed=true"))
        assertTrue(result.contains("NOT a CAN delivery"))
        assertTrue(evidence.summary().contains("no CAN/QNX/role acknowledgement"))
    }

    @Test
    fun rejectsStaleAndFarFutureSameCommand() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, log("1791638745.999", "AmapService", "下一道路名: SCMD_USBMODE2") +
            log("1791638806.001", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2"))
        assertTrue(evidence.snapshot().contains("fresh_oem_next_road_input_observed=false"))
        assertTrue(evidence.snapshot().contains("fresh_oem_set_attempt_observed=false"))
    }

    @Test
    fun doesNotRetainOtherRoadsOrArbitraryMessages() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, log("1791638746.101", "AmapService", "下一道路名: 私人家庭住址") +
            log("1791638746.102", "OtherApp", "下一道路名: SCMD_USBMODE2") +
            log("1791638746.103", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE1") +
            log("1791638746.104", "AmapService", "access_token=secret") +
            log("1791638746.105", "AmapService", "下一道路名: SCMD_USBMODE2 suffix"))
        val result = evidence.snapshot()
        assertFalse(result.contains("私人家庭住址"))
        assertFalse(result.contains("secret"))
        assertTrue(result.contains("fresh_oem_set_attempt_observed=false"))
        assertTrue(result.contains("fresh_oem_next_road_input_observed=false"))
    }

    @Test
    fun flagsTimeCorrelatedPermissionErrorWithoutCopyingRawMessage() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, "1791638746.102 1000 1001 E BYDAutoService: [setBuffer] permission deny secret-private-data\n")
        val result = evidence.snapshot()
        assertTrue(result.contains("permission_error_in_window=true"))
        assertTrue(result.contains("time-correlated only"))
        assertFalse(result.contains("secret-private-data"))
    }

    @Test
    fun capsLongLinesAndEventCountAndHandlesEof() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, log("1791638746.101", "AmapService", "下一道路名: SCMD_USBMODE2")
            .replace("\n", "x".repeat(8192) + "\n"))
        for (count in 0 until 500) {
            feed(evidence, log("1791638746.102", "AmapService", "下一道路名: SCMD_USBMODE2"))
        }
        // java.lang.String.trim, as in the Java version of this test.
        feed(evidence, log("1791638746.103", "AmapService", "发送独立仪表下个道路名称:SCMD_USBMODE2").trim { it <= ' ' })
        val result = evidence.snapshot()
        assertTrue(result.contains("discarded_oversize_lines=1"))
        assertTrue(result.contains("fresh_oem_set_attempt_observed=true"))
        assertTrue(result.length < 5000)
        assertTrue(result == evidence.snapshot())
    }

    @Test
    fun malformedTimesAndChunksCannotBecomeSuccess() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, "NOT_EPOCH 1 2 D AmapService: 下一道路名: SCMD_USBMODE2\n")
        assertTrue(evidence.snapshot().contains("Not observed = UNKNOWN"))
        assertThrows(IllegalArgumentException::class.java) { evidence.onOutput(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { evidence.onOutput(ByteArray(1), 2) }
        assertThrows(IllegalArgumentException::class.java) { NavigationRelayEvidence(InstrumentUsbCommand.NCM, -1) }
        assertThrows(NullPointerException::class.java) { NavigationRelayEvidence(null, START) }
    }

    @Test
    fun diagnosticCommandIsReadOnlyAndUsesTagAllowlist() {
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("-d"))
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("-t 256"))
        assertTrue(NavigationRelayEvidence.LOG_COMMAND.contains("'*:S'"))
        assertFalse(NavigationRelayEvidence.LOG_COMMAND.contains("-c"))
        assertFalse(NavigationRelayEvidence.LOG_COMMAND.contains("am broadcast"))
    }

    @Test
    fun failedLogcatExitIsNotHiddenByAnEmptySnapshot() {
        val evidence = NavigationRelayEvidence(InstrumentUsbCommand.NCM, START)
        feed(evidence, "logcat: permission denied\nDASHCAST_EXIT_CODE=1\n")
        assertTrue(evidence.snapshot().contains("logcat_command_success=false"))
        assertFalse(evidence.hasProcessingEvidence())
        feed(evidence, "DASHCAST_EXIT_CODE=0\n")
        assertTrue(evidence.snapshot().contains("logcat_command_success=true"))
    }

    private companion object {
        const val START = 1791638746000L

        fun feed(evidence: NavigationRelayEvidence, text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            evidence.onOutput(data, data.size)
        }

        fun log(timestamp: String, tag: String, message: String): String =
            timestamp + " 1000 1001 D " + tag + ": " + message + "\n"
    }
}
