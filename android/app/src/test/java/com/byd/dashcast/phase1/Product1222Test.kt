package com.byd.dashcast.phase1

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 1.22.2: a system day/night switch while the app is in front redraws without changing pages. */
class Product1222Test {
    private val source = File("src/main/java/com/byd/dashcast/phase1/ProductActivity.kt").readText()

    @Test fun refreshLoopRedrawsWhenTheResolvedThemeNoLongerMatchesThePage() {
        val loop = source.substringAfter("private val refresh = object : Runnable").substringBefore("private val light")
        assertTrue(loop.contains("if (light != renderedLight) { build() }"))
        assertTrue(loop.indexOf("build()") < loop.indexOf("updateStatus()"))
    }

    @Test fun configurationCallbackAlsoTrustsTheDeliveredConfiguration() {
        val callback = source.substringAfter("override fun onConfigurationChanged").substringBefore("override fun onResume")
        assertTrue(callback.contains("ThemePolicy.systemNight(newConfig)"))
    }

    @Test fun followModeReadsOnlyTheNightBit() {
        val night = Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES }
        val day = Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO }
        assertTrue(ThemePolicy.systemNight(night))
        assertFalse(ThemePolicy.systemNight(day))
        assertFalse(ThemePolicy.light(ProductSettings.THEME_SYSTEM, true))
        assertTrue(ThemePolicy.light(ProductSettings.THEME_SYSTEM, false))
    }
}
