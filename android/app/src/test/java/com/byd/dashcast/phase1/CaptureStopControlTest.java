package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class CaptureStopControlTest {
    @Test
    public void fixedStopAndOwnerPipeEofAreBoundedControlNotShellCommands() throws Exception {
        for (String input : new String[] {"STOP\n", "", "ST", "STOP\nignored"}) {
            CaptureStopControl.readControl(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Test
    public void otherCommandsAndUnboundedLinesAreRejectedWithinFiveBytes() {
        for (String input : new String[] {"stop\n", "STOPx", "kill", "x".repeat(10000)}) {
            assertThrows(IOException.class, () -> CaptureStopControl.readControl(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII))));
        }
    }
}
