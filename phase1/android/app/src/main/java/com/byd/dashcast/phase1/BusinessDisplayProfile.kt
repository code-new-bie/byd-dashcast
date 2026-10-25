package com.byd.dashcast.phase1

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** A hosted business UI keeps the primary display's logical aspect/density. Never changes wm.
 * Instrument safe bounds are a separate output mapping, not the business application's size.
 * @author Codex
 * @date 2026/10/25
 */
internal data class BusinessDisplayProfile(val width: Int, val height: Int, val density: Int) {
    init { require(valid(width, height, density)) { "MIRROR_PRIMARY_PROFILE_UNSUPPORTED" } }
    companion object {
        fun valid(width: Int, height: Int, density: Int): Boolean = width in 256..3840 &&
            height in 256..2160 && width.toLong() * height <= 8_294_400 && density in 72..640

        @JvmStatic fun read(context: Context): BusinessDisplayProfile {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: error("MIRROR_PRIMARY_DISPLAY_MISSING")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return BusinessDisplayProfile(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }
}
