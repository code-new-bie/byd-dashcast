package com.byd.dashcast.phase1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent descriptor/visibility tests, with no Android runtime or USB writes. */
class KernelUsbEvidenceTest {
    @Test
    fun rootHubsAloneAreNotAnInstrumentOrPermissionFailure() {
        val evidence = ready("USB_NODE=usb1\nbDeviceClass=09\n")
        val summary = evidence.summary(0, 0)
        assertTrue(summary.contains("KERNEL_NO_PERIPHERAL"))
        assertTrue(summary.contains("kernel_root_hubs=1"))
        assertTrue(summary.contains("kernel_peripherals=0"))
    }

    @Test
    fun kernelNcmAndEmptyFrameworkAreDifferentFromNoEnumeration() {
        val evidence = ready(device("1-1") +
            "USB_NODE=1-1:1.0\nbInterfaceClass=02\nbInterfaceSubClass=0D\nbInterfaceProtocol=00\n")
        assertTrue(evidence.summary(0, 0).contains("KERNEL_NCM_FRAMEWORK_NOT_VISIBLE"))
        assertTrue(evidence.summary(1, 1).contains("FRAMEWORK_NCM_VISIBLE"))
        assertTrue(evidence.snapshot(1, 1).contains("NOT QNX role"))
    }

    @Test
    fun otherUsbIsNotNcm() {
        val evidence = ready(device("2-1") +
            "USB_NODE=2-1:1.0\nbInterfaceClass=08\nbInterfaceSubClass=06\n")
        assertTrue(evidence.summary(1, 0).contains("KERNEL_OTHER_USB_NO_NCM"))
    }

    @Test
    fun unreadableCappedFailedIncompleteOrMissingExitAreUnknown() {
        val outputs = arrayOf(
            "SYSFS_STATUS=UNAVAILABLE\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_TRUNCATED=1\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nREAD_FAILED=idVendor\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=1\n",
            "SYSFS_STATUS=READABLE\nUSB_NODE=1-1\nbDeviceClass=02\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_DONE=1\n",
        )
        for (output in outputs) {
            val evidence = KernelUsbEvidence()
            feed(evidence, output)
            assertTrue(evidence.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"))
        }
    }

    @Test
    fun chunkingAndEofAreSupportedWithoutCollectingSerialOrInjectedFields() {
        val evidence = KernelUsbEvidence()
        val output = "SYSFS_STATUS=READABLE\n" + device("1-2") +
            "serial=private-value\nmanufacturer=private-vendor\n" +
            "SYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0"
        for (value in output.toByteArray(Charsets.US_ASCII)) {
            evidence.onOutput(byteArrayOf(value), 1)
        }
        val snapshot = evidence.snapshot(1, 0)
        assertFalse(snapshot.contains("private"))
        assertFalse(snapshot.contains("serial="))
        assertTrue(snapshot.contains("kernel_scan_complete=true"))
        val malformed = ready("USB_NODE=1-1\nidVendor=abcd;echo-secret\n")
        assertTrue(malformed.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"))
        assertFalse(malformed.snapshot(0, 0).contains("secret"))
    }

    @Test
    fun oversizedOrDuplicateRecordsAreBoundedAndUnknown() {
        val evidence = ready("USB_NODE=1-1\nidVendor=" + "a".repeat(10000) + "\n")
        assertTrue(evidence.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"))
        val duplicate = ready(device("1-1") + device("1-1"))
        assertTrue(duplicate.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"))
        val records = StringBuilder()
        for (index in 0 until 1000) {
            records.append(device("1-" + (index + 1)))
        }
        val capped = ready(records.toString())
        assertTrue(capped.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"))
        assertTrue(capped.snapshot(0, 0).length < 20000)
    }

    @Test
    fun invalidCountsAndChunksAreRejectedAndCommandIsReadOnly() {
        val evidence = KernelUsbEvidence()
        assertThrows(IllegalArgumentException::class.java) { evidence.summary(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { evidence.onOutput(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { evidence.onOutput(ByteArray(1), 2) }
        assertFalse(KernelUsbEvidence.COMMAND.contains("serial"))
        assertFalse(KernelUsbEvidence.COMMAND.contains(" > "))
        assertFalse(KernelUsbEvidence.COMMAND.contains("setprop"))
        assertTrue(KernelUsbEvidence.COMMAND.contains("-ge 64"))
    }

    private companion object {
        fun device(name: String): String =
            "USB_NODE=" + name + "\ndevnum=2\nidVendor=1234\nidProduct=5678\nbDeviceClass=02\n"

        fun ready(records: String): KernelUsbEvidence {
            val evidence = KernelUsbEvidence()
            feed(evidence, "SYSFS_STATUS=READABLE\n" + records + "SYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n")
            return evidence
        }

        fun feed(evidence: KernelUsbEvidence, output: String) {
            val data = output.toByteArray(Charsets.UTF_8)
            evidence.onOutput(data, data.size)
        }
    }
}
