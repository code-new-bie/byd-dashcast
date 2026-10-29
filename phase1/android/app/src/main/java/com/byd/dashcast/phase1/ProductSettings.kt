package com.byd.dashcast.phase1

import android.content.Context

/** App-private product preferences; never copies or replaces the existing ADB identity.
 * @author Codex
 * @date 2026/10/21
 */
internal class ProductSettings(context: Context) {
    companion object {
        const val DEFAULT_CROP = true
        private const val FRAMING_MIGRATED = "instrument_framing_v2"
    }

    private val preferences = context.applicationContext.getSharedPreferences("dashcast.product.v1", Context.MODE_PRIVATE)

    init {
        // Older installs persisted FIT for a carrier-shaped business display.
        // Business displays now follow the main screen. Migrate once; a later
        // deliberate choice of complete display must survive every reopen.
        synchronized(preferences) {
            if (!preferences.getBoolean(FRAMING_MIGRATED, false)) {
                preferences.edit().putBoolean("crop", DEFAULT_CROP)
                    .putBoolean(FRAMING_MIGRATED, true).apply()
            }
        }
    }

    var setupComplete: Boolean
        get() = preferences.getBoolean("setup_complete", false)
        set(value) { preferences.edit().putBoolean("setup_complete", value).apply() }
    var target: String?
        get() = preferences.getString("target", null)
        set(value) { preferences.edit().putString("target", value).apply() }
    var crop: Boolean
        get() = preferences.getBoolean("crop", DEFAULT_CROP)
        set(value) { preferences.edit().putBoolean("crop", value).apply() }
    var light: Boolean
        get() = preferences.getBoolean("light", false)
        set(value) { preferences.edit().putBoolean("light", value).apply() }
    var underlay: Boolean
        get() = preferences.getBoolean("underlay", true)
        set(value) { preferences.edit().putBoolean("underlay", value).apply() }
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
