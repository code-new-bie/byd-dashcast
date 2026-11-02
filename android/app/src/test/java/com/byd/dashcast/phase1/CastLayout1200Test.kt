package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class CastLayout1200Test {
    private val small = 2304f / 1296f
    private val full = 3392f / 912f

    @Test fun fittedAreaIsLimitedByTheTighterSide() {
        assertEquals(390f * small * 390f, CastLayoutMath.fittedArea(small, 1848, 390), 1f)
        assertEquals(1000f * (1000f / full), CastLayoutMath.fittedArea(full, 1000, 800), 1f)
        assertEquals(0f, CastLayoutMath.fittedArea(small, 0, 800), 0f)
    }

    /** 1920x990 head unit at density 2: 838 px below the header, about 360 px of stacked chrome. */
    @Test fun smallModeMovesControlsBesideTheWindowAndFullModeStaysStacked() {
        val width = 1848; val height = 838; val stacked = height - 360
        val panel = CastLayoutMath.panelWidth(width, 2f)
        assertEquals(520, panel)
        val side = width - panel - 24
        assertTrue(CastLayoutMath.preferSide(CastLayoutMath.fittedArea(small, width, stacked),
            CastLayoutMath.fittedArea(small, side, height)))
        assertFalse(CastLayoutMath.preferSide(CastLayoutMath.fittedArea(full, width, stacked),
            CastLayoutMath.fittedArea(full, side, height)))
    }

    @Test fun narrowPagesNeverGiveUpWidthForASideColumn() {
        assertEquals(0, CastLayoutMath.panelWidth(900, 2f))
        assertFalse(CastLayoutMath.preferSide(100f, 109f))
        assertTrue(CastLayoutMath.preferSide(100f, 111f))
    }
}
