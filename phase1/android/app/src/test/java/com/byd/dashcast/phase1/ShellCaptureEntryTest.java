package com.byd.dashcast.phase1;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated command -> source/geometry parser regressions from the named-entry failure. */
public final class ShellCaptureEntryTest {
    private static final String APK = "/data/app/test/base.apk";

    private static String[] arguments(String command) {
        String marker = "com.byd.dashcast.phase1.ShellScreenCapture ";
        int offset = command.indexOf(marker);
        assertTrue(offset >= 0);
        // Only the fixed suffix is decoded; nothing is executed by this test.
        // A component is already allowlisted and can contain no quote/space.
        return command.substring(offset + marker.length()).replace("'", "").split(" ");
    }

    @Test
    public void actualNamedCommandRoundTripsEveryProductGeometryAndUnlimitedDuration() {
        for (String token : Arrays.asList("viewport-center-crop", "viewport-center-fit",
                "viewport-full-crop", "viewport-full-fit", "fit", "crop", "region:0:5:100:95")) {
            CaptureOptions options = CaptureOptions.parse(token);
            String[] args = arguments(ShellCaptureCommand.continuous(APK, 0, options, ProjectionTarget.namedDisplay()));
            assertEquals("0", args[0]);
            assertEquals("live", args[1]);
            assertEquals("named", args[2]);
            assertEquals(ProjectionTarget.namedDisplay(), ProjectionTarget.fromShellArguments(args));
            assertEquals(token, CaptureOptions.fromShellArguments(args).token());
        }
    }

    @Test
    public void primaryAndRemoteGeneratedCommandsKeepTheOriginalContracts() {
        ProjectionTarget remote = ProjectionTarget.remoteComponent("com.example.map/com.example.map.MainActivity", null);
        for (ProjectionTarget target : Arrays.asList(ProjectionTarget.primary(), remote)) {
            CaptureOptions options = CaptureOptions.parse("viewport-full-fit");
            String[] args = arguments(ShellCaptureCommand.continuous(APK, 0, options, target));
            assertEquals(target, ProjectionTarget.fromShellArguments(args));
            assertEquals(options.token(), CaptureOptions.fromShellArguments(args).token());
        }
        String[] probe = arguments(ShellCaptureCommand.create(APK, 5, remote));
        assertEquals(remote, ProjectionTarget.fromShellArguments(probe));
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(probe).mode());
    }

    @Test
    public void malformedNamedGeometryAndUncontrolledPrefixesAreRejected() {
        for (String[] args : new String[][] {
                {"0", "live", "named", "fit", "extra"},
                {"0", "live", "named", "remote", "com.test/.Main", "fit"},
                {"0", "live", "named", "region", "0", "0", "100"},
                {"0", "live", "remote"}}) {
            assertThrows(IllegalArgumentException.class, () -> CaptureOptions.fromShellArguments(args));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionTarget.fromShellArguments(new String[] {"0", "fit", "named"}));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionTarget.fromShellArguments(new String[] {"5", "named"}));
    }
}
