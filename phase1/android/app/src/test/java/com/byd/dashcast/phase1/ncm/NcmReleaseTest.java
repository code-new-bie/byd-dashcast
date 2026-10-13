package com.byd.dashcast.phase1.ncm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Cleanup order and failure-isolation regressions; all resource operations are fake. */
public final class NcmReleaseTest {
    @Test
    public void deactivateBulkBeforeReleasingClaimsAndClosingTheConnection() {
        FakeSteps steps = new FakeSteps();
        assertTrue(NcmRelease.finish(steps));
        assertEquals(Arrays.asList("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls);
    }

    @Test
    public void deactivationRejectionStillReleasesBothClaimsAndCloses() {
        FakeSteps steps = new FakeSteps();
        steps.reject = "ALT0";
        assertFalse(NcmRelease.finish(steps));
        assertEquals(Arrays.asList("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls);
    }

    @Test
    public void releaseRejectionDoesNotSkipLaterResourcesOrPretendSuccess() {
        for (String failed : new String[] {"RELEASE_DATA", "RELEASE_CONTROL"}) {
            FakeSteps steps = new FakeSteps();
            steps.reject = failed;
            assertFalse(NcmRelease.finish(steps));
            assertEquals(4, steps.calls.size());
        }
    }

    @Test
    public void exceptionsInEveryResourceStepAreIsolatedAndReported() {
        for (String failed : new String[] {"ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"}) {
            FakeSteps steps = new FakeSteps();
            steps.fail = failed;
            assertFalse(NcmRelease.finish(steps));
            assertEquals(Arrays.asList("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls);
        }
    }

    @Test
    public void missingAdapterIsRejectedBeforeAnyResourceOperation() {
        assertThrows(IllegalArgumentException.class, () -> NcmRelease.finish(null));
    }

    private static final class FakeSteps implements NcmRelease.Steps {
        private final List<String> calls = new ArrayList<>();
        private String reject;
        private String fail;

        private boolean step(String name) {
            calls.add(name);
            if (name.equals(fail)) {
                throw new IllegalStateException("USB unplugged during cleanup");
            }
            return !name.equals(reject);
        }

        @Override
        public boolean deactivateData() {
            return step("ALT0");
        }

        @Override
        public boolean releaseData() {
            return step("RELEASE_DATA");
        }

        @Override
        public boolean releaseControl() {
            return step("RELEASE_CONTROL");
        }

        @Override
        public void closeConnection() {
            step("CLOSE");
        }
    }
}
