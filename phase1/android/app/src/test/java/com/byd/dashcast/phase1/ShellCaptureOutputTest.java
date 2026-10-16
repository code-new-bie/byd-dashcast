package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Actual child JVM FD test; no global System stream mutation in the JUnit process.
 * @author Codex
 * @date 2026/10/16
 */
public final class ShellCaptureOutputTest {
    @Test
    public void frameworkPrintlnCannotCorruptDcs1OrRawAvcStdout() throws Exception {
        String classpath = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + new File(ShellCaptureOutput.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", classpath, Probe.class.getName()).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertArrayEquals(CaptureFrameProtocol.end(0), child.getInputStream().readAllBytes());
            assertEquals("HotFixRoot: synthetic-test\n",
                    new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(2, TimeUnit.SECONDS));
            }
            child.getInputStream().close();
            child.getErrorStream().close();
            child.getOutputStream().close();
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            ShellCaptureOutput.isolateFrameworkDiagnostics();
            System.out.println("HotFixRoot: synthetic-test");
            OutputStream video = ShellCaptureOutput.videoStream();
            video.write(CaptureFrameProtocol.end(0));
            video.flush();
        }
    }
}
