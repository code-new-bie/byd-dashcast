package com.byd.dashcast.phase1

/** Explicit fit/crop from a business source into the physical readable inner rectangle.
 * The native center window rescales its encoded rectangle; use physical, not encoded aspect.
 * @author Codex
 * @date 2026/10/25
 */
internal data class SourceRenderMapping(val content: FloatArray, val source: FloatArray) {
    companion object {
        fun create(viewport: ContentViewport?, crop: Boolean): SourceRenderMapping {
            val content = viewport?.normalized() ?: floatArrayOf(0f, 0f, 1f, 1f)
            if (viewport == null) { return SourceRenderMapping(content, floatArrayOf(0f, 0f, 1f, 1f)) }
            val targetAspect = viewport.physicalAspect
            val sourceAspect = viewport.sourceWidth.toFloat() / viewport.sourceHeight
            val source = floatArrayOf(0f, 0f, 1f, 1f)
            if (crop) {
                if (sourceAspect > targetAspect) {
                    source[2] = targetAspect / sourceAspect; source[0] = (1f - source[2]) / 2
                } else {
                    source[3] = sourceAspect / targetAspect; source[1] = (1f - source[3]) / 2
                }
            } else {
                if (sourceAspect > targetAspect) {
                    val h = content[3] * targetAspect / sourceAspect
                    content[1] += (content[3] - h) / 2; content[3] = h
                } else {
                    val w = content[2] * sourceAspect / targetAspect
                    content[0] += (content[2] - w) / 2; content[2] = w
                }
            }
            return SourceRenderMapping(content, source)
        }
    }
}
