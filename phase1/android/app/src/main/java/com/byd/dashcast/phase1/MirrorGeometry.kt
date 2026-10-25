package com.byd.dashcast.phase1

/** One aspect-preserving rectangle for drawing AND input. Never maps letterbox touches into the app.
 * @author Codex
 * @date 2026/10/25
 */
internal data class MirrorGeometry(val left: Float, val top: Float, val width: Float, val height: Float,
    val sourceWidth: Int, val sourceHeight: Int) {
    fun contains(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite() &&
        x >= left && y >= top && x < left + width && y < top + height

    companion object {
        fun fit(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): MirrorGeometry {
            require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0)
            val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
            val w = sourceWidth * scale
            val h = sourceHeight * scale
            return MirrorGeometry((width - w) / 2, (height - h) / 2, w, h, sourceWidth, sourceHeight)
        }
    }
}
