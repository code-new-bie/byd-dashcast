package com.byd.dashcast.phase1;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Independent descriptor/visibility tests, with no Android runtime or USB writes. */
public final class KernelUsbEvidenceTest {
    @Test
    public void rootHubsAloneAreNotAnInstrumentOrPermissionFailure() throws Exception {
        KernelUsbEvidence evidence = ready("USB_NODE=usb1\nbDeviceClass=09\n");
        String summary = evidence.summary(0, 0);
        assertTrue(summary.contains("KERNEL_NO_PERIPHERAL"));
        assertTrue(summary.contains("kernel_root_hubs=1"));
        assertTrue(summary.contains("kernel_peripherals=0"));
    }

    @Test
    public void kernelNcmAndEmptyFrameworkAreDifferentFromNoEnumeration() throws Exception {
        KernelUsbEvidence evidence = ready(device("1-1")
                + "USB_NODE=1-1:1.0\nbInterfaceClass=02\nbInterfaceSubClass=0D\nbInterfaceProtocol=00\n");
        assertTrue(evidence.summary(0, 0).contains("KERNEL_NCM_FRAMEWORK_NOT_VISIBLE"));
        assertTrue(evidence.summary(1, 1).contains("FRAMEWORK_NCM_VISIBLE"));
        assertTrue(evidence.snapshot(1, 1).contains("NOT QNX role"));
    }

    @Test
    public void otherUsbIsNotNcm() throws Exception {
        KernelUsbEvidence evidence = ready(device("2-1")
                + "USB_NODE=2-1:1.0\nbInterfaceClass=08\nbInterfaceSubClass=06\n");
        assertTrue(evidence.summary(1, 0).contains("KERNEL_OTHER_USB_NO_NCM"));
    }

    @Test
    public void unreadableCappedFailedIncompleteOrMissingExitAreUnknown() throws Exception {
        String[] outputs = {
            "SYSFS_STATUS=UNAVAILABLE\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_TRUNCATED=1\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nREAD_FAILED=idVendor\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=1\n",
            "SYSFS_STATUS=READABLE\nUSB_NODE=1-1\nbDeviceClass=02\nSYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n",
            "SYSFS_STATUS=READABLE\nSYSFS_SCAN_DONE=1\n"
        };
        for (String output : outputs) {
            KernelUsbEvidence evidence = new KernelUsbEvidence();
            feed(evidence, output);
            assertTrue(evidence.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"));
        }
    }

    @Test
    public void chunkingAndEofAreSupportedWithoutCollectingSerialOrInjectedFields() throws Exception {
        KernelUsbEvidence evidence = new KernelUsbEvidence();
        String output = "SYSFS_STATUS=READABLE\n" + device("1-2")
                + "serial=private-value\nmanufacturer=private-vendor\n"
                + "SYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0";
        for (byte value : output.getBytes(StandardCharsets.US_ASCII)) {
            evidence.onOutput(new byte[] {value}, 1);
        }
        String snapshot = evidence.snapshot(1, 0);
        assertFalse(snapshot.contains("private"));
        assertFalse(snapshot.contains("serial="));
        assertTrue(snapshot.contains("kernel_scan_complete=true"));
        KernelUsbEvidence malformed = ready("USB_NODE=1-1\nidVendor=abcd;echo-secret\n");
        assertTrue(malformed.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"));
        assertFalse(malformed.snapshot(0, 0).contains("secret"));
    }

    @Test
    public void oversizedOrDuplicateRecordsAreBoundedAndUnknown() throws Exception {
        KernelUsbEvidence evidence = ready("USB_NODE=1-1\nidVendor=" + "a".repeat(10000) + "\n");
        assertTrue(evidence.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"));
        KernelUsbEvidence duplicate = ready(device("1-1") + device("1-1"));
        assertTrue(duplicate.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"));
        StringBuilder records = new StringBuilder();
        for (int index = 0; index < 1000; index++) {
            records.append(device("1-" + (index + 1)));
        }
        KernelUsbEvidence capped = ready(records.toString());
        assertTrue(capped.summary(0, 0).contains("KERNEL_ENUMERATION_UNKNOWN"));
        assertTrue(capped.snapshot(0, 0).length() < 20000);
    }

    @Test
    public void invalidCountsAndChunksAreRejectedAndCommandIsReadOnly() throws Exception {
        KernelUsbEvidence evidence = new KernelUsbEvidence();
        assertThrows(IllegalArgumentException.class, () -> evidence.summary(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> evidence.onOutput(null, 0));
        assertThrows(IllegalArgumentException.class, () -> evidence.onOutput(new byte[1], 2));
        assertFalse(KernelUsbEvidence.COMMAND.contains("serial"));
        assertFalse(KernelUsbEvidence.COMMAND.contains(" > "));
        assertFalse(KernelUsbEvidence.COMMAND.contains("setprop"));
        assertTrue(KernelUsbEvidence.COMMAND.contains("-ge 64"));
    }

    private static String device(String name) {
        return "USB_NODE=" + name + "\ndevnum=2\nidVendor=1234\nidProduct=5678\nbDeviceClass=02\n";
    }

    private static KernelUsbEvidence ready(String records) throws Exception {
        KernelUsbEvidence evidence = new KernelUsbEvidence();
        feed(evidence, "SYSFS_STATUS=READABLE\n" + records + "SYSFS_SCAN_DONE=1\nDASHCAST_EXIT_CODE=0\n");
        return evidence;
    }

    private static void feed(KernelUsbEvidence evidence, String output) throws Exception {
        byte[] data = output.getBytes(StandardCharsets.UTF_8);
        evidence.onOutput(data, data.length);
    }
}
