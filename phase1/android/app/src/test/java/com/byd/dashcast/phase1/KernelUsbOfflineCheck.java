package com.byd.dashcast.phase1;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only CLI bridge for running the actual read-only shell command against isolated fixtures. */
public final class KernelUsbOfflineCheck {
    private KernelUsbOfflineCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "command".equals(args[0])) {
            System.out.print(AdbCommandOutput.wrap(KernelUsbEvidence.COMMAND));
            return;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected command, or output file and expected layer");
        }
        byte[] output = Files.readAllBytes(Path.of(args[0]));
        if (output.length > 48 * 1024) {
            throw new AssertionError("Unbounded USB shell output");
        }
        KernelUsbEvidence evidence = new KernelUsbEvidence();
        evidence.onOutput(output, output.length);
        String result = evidence.snapshot(0, 0);
        if (!result.contains("USB_ENUM_LAYER=" + args[1] + " ")) {
            throw new AssertionError(result);
        }
        if (result.contains("private-serial") || result.contains("private-product")) {
            throw new AssertionError("Private attributes collected");
        }
        System.out.println(evidence.summary(0, 0));
    }
}
