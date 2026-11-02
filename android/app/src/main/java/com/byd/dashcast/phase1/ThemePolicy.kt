package com.byd.dashcast.phase1

import android.content.res.Configuration

/** Explicit choices override the system; "follow" uses the resolved system night state.
 * The head unit maps its own day/night setting onto Configuration.uiMode, so no vendor API is read.
 */
internal object ThemePolicy {
    fun light(mode: Int, systemNight: Boolean): Boolean = when (mode) {
        ProductSettings.THEME_DARK -> false
        ProductSettings.THEME_LIGHT -> true
        else -> !systemNight
    }

    fun systemNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
