package com.byd.dashcast.phase1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Owned-child timeout and output limits without invoking Android or a real shell. */
public final class BoundedProcessOutputTest {
    @Test
    public void drainsAndBoundsSuccessfulOutput() throws Exception {
        FakeProcess process = new FakeProcess(true, new byte[32 * 1024]);
        assertEquals(16 * 1024, BoundedProcessOutput.read(process, 1000).length());
        assertFalse(process.killed);
        assertTrue(process.closed);
    }

    @Test
    public void timeoutKillsOnlyTheOwnedChildAndConfirmsItsExit() {
        FakeProcess process = new FakeProcess(false, new byte[0]);
        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> BoundedProcessOutput.read(process, 1));
        assertTrue(error.getMessage().contains("APP_START_TIMEOUT"));
        assertTrue(process.killed);
        assertFalse(process.isAlive());
        assertTrue(process.closed);
    }

    private static final class FakeProcess extends java.lang.Process {
        private boolean ended;
        private boolean killed;
        private boolean closed;
        private final InputStream input;

        FakeProcess(boolean ended, byte[] bytes) {
            this.ended = ended;
            input = new ByteArrayInputStream(bytes) {
                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return ended;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return !ended;
        }

        @Override
        public void destroy() {
            ended = true;
            killed = true;
        }

        @Override
        public java.lang.Process destroyForcibly() {
            destroy();
            return this;
        }
    }
}
