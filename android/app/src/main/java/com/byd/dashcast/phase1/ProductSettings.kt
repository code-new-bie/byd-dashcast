package com.byd.dashcast.phase1

import android.content.Context

/** App-private product preferences; never copies or replaces the existing ADB identity.
 * @author Codex
 * @date 2026/10/21
 */
internal class ProductSettings(context: Context) {
    companion object {
        const val DEFAULT_CROP = false
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        private const val FRAMING_MIGRATED = "instrument_framing_v3_complete"
    }

    private val preferences = context.applicationContext.getSharedPreferences("dashcast.product.v1", Context.MODE_PRIVATE)

    init {
        // Migrate the old cropping default once. Later deliberate choices survive.
        synchronized(preferences) {
            if (!preferences.getBoolean(FRAMING_MIGRATED, false)) {
                preferences.edit().putBoolean("crop", DEFAULT_CROP)
                    .putBoolean(FRAMING_MIGRATED, true).apply()
            }
        }
    }

    var target: String?
        get() = preferences.getString("target", null)
        set(value) { preferences.edit().putString("target", value).apply() }
    var crop: Boolean
        get() = preferences.getBoolean("crop", DEFAULT_CROP)
        set(value) { preferences.edit().putBoolean("crop", value).apply() }
    /** 0 follows the system day/night setting, 1 is dark, 2 is light. The old two-way "light" key is ignored. */
    var themeMode: Int
        get() = preferences.getInt("theme_mode", THEME_SYSTEM).takeIf { it in THEME_SYSTEM..THEME_LIGHT } ?: THEME_SYSTEM
        set(value) { preferences.edit().putInt("theme_mode", value.coerceIn(THEME_SYSTEM, THEME_LIGHT)).apply() }
    fun light(systemNight: Boolean): Boolean = ThemePolicy.light(themeMode, systemNight)
    var bootStart: Boolean
        get() = preferences.getBoolean("boot_start", false)
        set(value) { preferences.edit().putBoolean("boot_start", value).apply() }
    var underlay: Boolean
        get() = preferences.getBoolean("underlay", true)
        set(value) { preferences.edit().putBoolean("underlay", value).apply() }
    // The two switches show live state; these remember what the user asked for so a restart can re-apply it.
    var instrumentConnection: Boolean
        get() = preferences.getBoolean("instrument_connection", false)
        set(value) { preferences.edit().putBoolean("instrument_connection", value).apply() }
    var externalAccess: Boolean
        get() = preferences.getBoolean("external_access", false)
        set(value) { preferences.edit().putBoolean("external_access", value).apply() }
    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet())!!.toSet()
    fun toggleFavorite(component: String) {
        val next = favorites().toMutableSet()
        if (!next.add(component)) { next.remove(component) }
        preferences.edit().putStringSet("favorites", next).apply()
    }
    fun options(): CaptureOptions {
        val selection = CaptureOptions.of(if (crop) CaptureOptions.Mode.CROP else CaptureOptions.Mode.FIT)
        return selection.forContentViewport(!underlay)
    }
}
