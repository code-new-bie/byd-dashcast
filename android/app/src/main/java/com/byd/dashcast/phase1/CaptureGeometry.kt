package com.byd.dashcast.phase1

import java.util.Objects

/**
 * Pure, bounded source/destination geometry for the 1920x720 encoder surface.
 * @author Codex
 * @date 2026/10/14
 */
internal class CaptureGeometry private constructor(private val source: Bounds, private val destination: Bounds) {

    /** Immutable pixel rectangle, with exclusive right/bottom and an explicit origin. */
    class Bounds internal constructor(
        private val leftEdge: Int,
        private val topEdge: Int,
        private val rightEdge: Int,
        private val bottomEdge: Int,
    ) {
        fun left(): Int = leftEdge
        fun top(): Int = topEdge
        fun right(): Int = rightEdge
        fun bottom(): Int = bottomEdge
        fun width(): Int = rightEdge - leftEdge
        fun height(): Int = bottomEdge - topEdge

        override fun toString(): String = "[$leftEdge,$topEdge,$rightEdge,$bottomEdge]"
    }

    fun source(): Bounds = source

    fun destination(): Bounds = destination

    override fun toString(): String =
        "source=" + source + " destination=" + destination + " encoded=" + ENCODED_WIDTH + "x" + ENCODED_HEIGHT

    companion object {
        const val ENCODED_WIDTH = NoAuthVideoProtocol.WIDTH
        const val ENCODED_HEIGHT = NoAuthVideoProtocol.HEIGHT
        private const val MAX_DISPLAY_SIZE = 4096
        private const val PERCENT_SCALE = 100

        @JvmStatic
        @JvmOverloads
        fun create(displayWidth: Int, displayHeight: Int, options: CaptureOptions?,
            outputWidth: Int = ENCODED_WIDTH, outputHeight: Int = ENCODED_HEIGHT): CaptureGeometry {
            Objects.requireNonNull(options, "Capture selection required")
            options!!
            if (displayWidth < 2 || displayHeight < 2 || displayWidth > MAX_DISPLAY_SIZE ||
                displayHeight > MAX_DISPLAY_SIZE || outputWidth < 2 || outputHeight < 2 ||
                outputWidth > MAX_DISPLAY_SIZE || outputHeight > MAX_DISPLAY_SIZE) {
                throw IllegalArgumentException("Display dimensions must be 2..4096 pixels")
            }
            var input = bounds(0, 0, displayWidth, displayHeight)
            if (options.mode() == CaptureOptions.Mode.REGION) {
                input = bounds(displayWidth * options.leftPercent() / PERCENT_SCALE,
                    displayHeight * options.topPercent() / PERCENT_SCALE,
                    displayWidth * options.rightPercent() / PERCENT_SCALE,
                    displayHeight * options.bottomPercent() / PERCENT_SCALE)
            }
            if (options.mode() == CaptureOptions.Mode.CROP) {
                input = crop(input, outputWidth, outputHeight)
            }
            val output = if (options.mode() == CaptureOptions.Mode.STRETCH || options.mode() == CaptureOptions.Mode.CROP) {
                bounds(0, 0, outputWidth, outputHeight)
            } else {
                fit(input, outputWidth, outputHeight)
            }
            return CaptureGeometry(input, output)
        }

        private fun crop(input: Bounds, outputWidth: Int, outputHeight: Int): Bounds {
            var width = input.width()
            var height = input.height()
            if (width.toLong() * outputHeight > height.toLong() * outputWidth) {
                width = maxOf(2, height * outputWidth / outputHeight)
            } else {
                height = maxOf(2, width * outputHeight / outputWidth)
            }
            width = minOf(width, input.width())
            height = minOf(height, input.height())
            val left = input.left() + (input.width() - width) / 2
            val top = input.top() + (input.height() - height) / 2
            return bounds(left, top, left + width, top + height)
        }

        private fun fit(input: Bounds, outputWidth: Int, outputHeight: Int): Bounds {
            var width = outputWidth
            var height = outputHeight
            if (input.width().toLong() * outputHeight > input.height().toLong() * outputWidth) {
                height = maxOf(1, outputWidth * input.height() / input.width())
            } else {
                width = maxOf(1, outputHeight * input.width() / input.height())
            }
            val left = (outputWidth - width) / 2
            val top = (outputHeight - height) / 2
            return bounds(left, top, left + width, top + height)
        }

        private fun bounds(left: Int, top: Int, right: Int, bottom: Int): Bounds {
            if (left < 0 || top < 0 || right <= left || bottom <= top) {
                throw IllegalArgumentException("Empty or negative capture rectangle")
            }
            return Bounds(left, top, right, bottom)
        }
    }
}
