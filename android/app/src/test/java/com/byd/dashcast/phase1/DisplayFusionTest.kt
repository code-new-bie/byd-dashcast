package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DisplayFusionTest {
    @Test fun centerChoicesRoundTripThroughTheControlledShellParser() {
        for (token in listOf("center-fit", "center-crop")) {
            val options = CaptureOptions.parse(token)
            assertTrue(options.centerWindow())
            assertEquals(token, options.token())
            assertEquals(token, CaptureOptions.fromShellArguments(arrayOf("0", "live", "remote",
                "com.example.map/.Main", token)).token())
            assertTrue(ShellCaptureCommand.continuous("/data/app/map/base.apk", 0, options,
                ProjectionTarget.primary()).endsWith(" live $token"))
        }
    }

    @Test fun centerSelectorRejectsUnknownGeometryAndShellWords() {
        for (token in listOf("center-stretch", "center-region:0:0:100:100", "center-center-fit", "center-fit;id")) {
            assertThrows(IllegalArgumentException::class.java) { CaptureOptions.parse(token) }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureOptions.of(CaptureOptions.Mode.STRETCH)
            .forCenterWindow().token() }
    }

    @Test fun centerWindowHasItsOwnExactFlagWithoutChangingTheLegacyStart() {
        val center = ByteBuffer.wrap(NoAuthVideoProtocol.startCenterWindow())
        assertEquals(48, center.capacity())
        val expected = intArrayOf(576, 128, 768, 432, 30, 2)
        expected.forEachIndexed { index, value -> assertEquals(value, center.getInt(24 + index * 4)) }
        val legacy = ByteBuffer.wrap(NoAuthVideoProtocol.start())
        assertEquals(640, legacy.getInt(24)); assertEquals(0, legacy.getInt(44))
        assertEquals(1, ByteBuffer.wrap(NoAuthVideoProtocol.start(true)).getInt(44))
    }

    @Test fun centerLeaseSelectorDoesNotIncreaseTheFiveSecondLeaseOrChangeRenewal() {
        assertEquals(5001, ByteBuffer.wrap(ProductCompositionSession.request(1, 77, true)).getInt(36))
        for (center in listOf(false, true)) {
            assertEquals(5000, ByteBuffer.wrap(ProductCompositionSession.request(3, 77, center)).getInt(36))
            assertEquals(0, ByteBuffer.wrap(ProductCompositionSession.request(2, 77, center)).getInt(36))
            assertEquals(0, ByteBuffer.wrap(ProductCompositionSession.request(0, 77, center)).getInt(36))
        }
    }

    @Test fun primarySourceFitsAndCropsAgainstTheLogicalSixteenNineCanvas() {
        val fit = CaptureGeometry.create(1920, 1080, CaptureOptions.parse("center-fit"), 1280, 720)
        assertEquals(1280, fit.destination().width()); assertEquals(720, fit.destination().height())
        val crop = CaptureGeometry.create(1920, 720, CaptureOptions.parse("center-crop"), 1280, 720)
        assertEquals(1280, crop.source().width()); assertEquals(720, crop.source().height())
        assertEquals(320, crop.source().left())
        assertThrows(IllegalArgumentException::class.java) {
            CaptureGeometry.create(1920, 1080, CaptureOptions.parse("center-fit"), 0, 720)
        }
    }

    @Test fun pairedResamplingPreservesFinalMapAspectRatio() {
        val xScale = 1920.0 / 1280.0 * (768.0 / 1920.0)
        val yScale = 720.0 / 720.0 * (432.0 / 720.0)
        assertEquals(xScale, yScale, 0.0000001)
        assertEquals(16.0 / 9, CenterFrameRenderer.INPUT_WIDTH.toDouble() / CenterFrameRenderer.INPUT_HEIGHT, 0.0000001)
    }
}
