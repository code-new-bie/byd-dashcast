package com.byd.dashcast.phase1

import java.nio.file.Files
import java.nio.file.Path

/** Test-only CLI bridge for running the actual read-only shell command against isolated fixtures. */
object KernelUsbOfflineCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && "command" == args[0]) {
            print(AdbCommandOutput.wrap(KernelUsbEvidence.COMMAND))
            return
        }
        if (args.size != 2) {
            throw IllegalArgumentException("Expected command, or output file and expected layer")
        }
        val output = Files.readAllBytes(Path.of(args[0]))
        if (output.size > 48 * 1024) {
            throw AssertionError("Unbounded USB shell output")
        }
        val evidence = KernelUsbEvidence()
        evidence.onOutput(output, output.size)
        val result = evidence.snapshot(0, 0)
        if (!result.contains("USB_ENUM_LAYER=" + args[1] + " ")) {
            throw AssertionError(result)
        }
        if (result.contains("private-serial") || result.contains("private-product")) {
            throw AssertionError("Private attributes collected")
        }
        println(evidence.summary(0, 0))
    }
}
