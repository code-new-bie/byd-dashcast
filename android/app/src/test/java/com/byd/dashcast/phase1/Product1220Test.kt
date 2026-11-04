package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

/** RWVG fade-in: viewport-shaped sources fill their carrier, band included; nothing is shrunk into the safe box. */
class Product1220Test {
    @Test fun viewportShapedSourcesFillTheWholeCarrierWithoutCropping() {
        for (small in listOf(false, true)) {
            val carrier = if (small) ContentViewport.center() else ContentViewport.full()
            val named = carrier.withSourceSize(ExternalDisplayWire.width(small), ExternalDisplayWire.height(small))
            for (viewport in listOf(carrier, named)) {
                val mapping = SourceRenderMapping.create(viewport, true)
                assertArrayEquals(carrier.normalized(), mapping.content, 0f)
                assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), mapping.source, 1e-6f)
            }
        }
    }

    @Test fun fittedBusinessLayoutsNeverReachTheFadeInBand() {
        for (small in listOf(false, true)) {
            val viewport = ContentViewport.forBusiness(BusinessDisplayProfile(1920, 1080, 240), small)
            val fit = SourceRenderMapping.create(viewport, ProductSettings.DEFAULT_CROP)
            val inside = viewport.readableCandidates().any { box ->
                fit.content[0] >= box[0] - 1e-5f && fit.content[1] >= box[1] - 1e-5f &&
                    fit.content[0] + fit.content[2] <= box[0] + box[2] + 1e-5f &&
                    fit.content[1] + fit.content[3] <= box[1] + box[3] + 1e-5f
            }
            assertTrue(inside)
        }
    }

    @Test fun sessionOptionIsAlwaysFitSoTheRemovedRatioChoiceCannotComeBack() {
        assertFalse(ProductSettings.DEFAULT_CROP)
    }
}
