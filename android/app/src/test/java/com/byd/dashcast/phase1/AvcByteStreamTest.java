package com.byd.dashcast.phase1;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

/** @author Codex @date 2026/10/10 */
public final class AvcByteStreamTest {
    @Test
    public void annexBCopyRespectsInputPosition() {
        ByteBuffer input = ByteBuffer.wrap(new byte[] {99, 0, 0, 1, 0x65, 44});
        input.position(1);
        assertArrayEquals(new byte[] {0, 0, 1, 0x65, 44}, AvcByteStream.normalize(input));
        assertEquals(1, input.position());
    }

    @Test
    public void convertsMultipleLengthPrefixedNals() {
        byte[] source = {0, 0, 0, 2, 0x67, 11, 0, 0, 0, 2, 0x68, 22};
        byte[] expected = {0, 0, 0, 1, 0x67, 11, 0, 0, 0, 1, 0x68, 22};
        assertArrayEquals(expected, AvcByteStream.normalize(ByteBuffer.wrap(source)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedNal() {
        AvcByteStream.normalize(ByteBuffer.wrap(new byte[] {0, 0, 0, 5, 0x67}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedPrefix() {
        AvcByteStream.normalize(ByteBuffer.wrap(new byte[] {0, 0}));
    }

    @Test
    public void usbCommandsAreWhitelistedUtf16LeWithoutBom() {
        assertEquals("SCMD_USBMODE1", InstrumentUsbCommand.HOST.commandText());
        assertEquals(26, InstrumentUsbCommand.NCM.payload().length);
        assertEquals('S', InstrumentUsbCommand.NCM.payload()[0]);
        assertEquals(0, InstrumentUsbCommand.NCM.payload()[1]);
    }

    @Test
    public void removesExistingAudWithoutDroppingSpsOrFrame() {
        byte[] source = {0, 0, 0, 1, 0x67, 11, 0, 0, 1, 9, (byte)0xf0, 0, 0, 1, 0x65, 22};
        byte[] expected = {0, 0, 0, 1, 0x67, 11, 0, 0, 1, 0x65, 22};
        assertArrayEquals(expected, AvcByteStream.withoutAud(source));
    }
}
