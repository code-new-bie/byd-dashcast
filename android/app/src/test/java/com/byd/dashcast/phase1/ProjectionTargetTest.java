package com.byd.dashcast.phase1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class ProjectionTargetTest {
    @Test
    public void primaryTargetRemainsTheDefaultAndHasNoRemoteSuffix() {
        ProjectionTarget target = ProjectionTarget.primary();

        assertFalse(target.isRemote());
        assertEquals("primary", target.token());
        assertEquals("", target.shellArguments());
    }

    @Test
    public void remoteTargetUsesAnAllowlistedComponentAndFixedShellWords() {
        ProjectionTarget target = ProjectionTarget.remote("com.example.maps", "com.example.maps.MainActivity",
                "地图");

        assertTrue(target.isRemote());
        assertEquals("remote:com.example.maps/com.example.maps.MainActivity", target.token());
        assertEquals("remote 'com.example.maps/com.example.maps.MainActivity'", target.shellArguments());
        assertEquals(target, ProjectionTarget.parse(target.token()));
    }

    @Test
    public void shellArgumentsDecodeRemoteTargetBeforeGeometry() {
        String[] args = {"600", "live", "remote", "com.example.maps/com.example.maps.MainActivity", "fit"};

        assertTrue(ProjectionTarget.fromShellArguments(args).isRemote());
        assertEquals(CaptureOptions.Mode.FIT, CaptureOptions.fromShellArguments(args).mode());
    }

    @Test
    public void nonLiveRemoteProbeKeepsTheRawH264EntryPoint() {
        String[] args = {"5", "remote", "com.example.maps/com.example.maps.MainActivity"};

        assertTrue(ProjectionTarget.fromShellArguments(args).isRemote());
        assertEquals(CaptureOptions.Mode.STRETCH, CaptureOptions.fromShellArguments(args).mode());
    }

    @Test
    public void unsafeOrMalformedComponentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionTarget.remoteComponent("com.example.maps;id/com.example.MainActivity", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionTarget.remote("com.example.maps", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionTarget.parse("remote:com.example.maps/com.example.MainActivity bad"));
    }
}
