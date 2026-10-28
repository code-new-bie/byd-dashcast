package com.byd.dashcast.phase1

/** Explicit logical profiles, not global wm density or cropped application UI. */
internal object ProjectionDisplayProfile {
    @JvmStatic
    fun densityDpi(width: Int, height: Int): Int = when (width to height) {
        1200 to 640 -> 240 // .6 physical resampling: 16sp becomes 14.4px, not 9.6px.
        1696 to 456 -> 160 // RWVE covers the additional 8px vertical outset.
        1696 to 440 -> 160 // Frozen RWVD diagnostics only.
        1632 to 440 -> 160 // Frozen RWVC diagnostics only.
        1408 to 368 -> 160 // Frozen RWV6 diagnostics only.
        1536 to 384 -> 160 // RWV5 effective content is 1:1, excluding wider native feathers.
        1728 to 384 -> 160 // Frozen RWV4 diagnostics only.
        1920 to 720, 1280 to 720, 1920 to 480 -> 160 // Unchanged diagnostics/legacy probes.
        else -> throw IllegalArgumentException("Unsupported independent display profile")
    }
}
