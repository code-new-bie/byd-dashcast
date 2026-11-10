package com.byd.dashcast.phase1

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Reserve raw stdout for video; OEM framework Java println calls belong on stderr.
 * Only the short-lived shell helper calls this, never the APK process.
 * @author Codex
 * @date 2026/10/16
 */
internal object ShellCaptureOutput {
    @JvmStatic
    fun isolateFrameworkDiagnostics() {
        System.setOut(System.err)
    }

    @JvmStatic
    fun videoStream(): OutputStream = FileOutputStream(FileDescriptor.out)
}
