package com.byd.dashcast.phase1

import java.util.Objects

/**
 * Immutable per-session capture selection; no instrument window or codec size changes.
 * @author Codex
 * @date 2026/10/14
 */
internal class CaptureOptions private constructor(
    private val mode: Mode,
    private val leftPercent: Int,
    private val topPercent: Int,
    private val rightPercent: Int,
    private val bottomPercent: Int,
    private val centerWindow: Boolean = false,
    private val contentViewport: Boolean = false,
) {
    /** Capture-only choices, never a QNX window layout request. */
    enum class Mode {
        /** Entire source, preserving aspect ratio with letterboxing. */
        FIT,

        /** Center crop preserving aspect ratio and filling the encoder surface. */
        CROP,

        /** Operator-selected source region fitted without stretching. */
        REGION,

        /** Original 0.5.0 projection, retained for explicit regression comparisons. */
        STRETCH
    }

    fun forCenterWindow(): CaptureOptions =
        CaptureOptions(mode, leftPercent, topPercent, rightPercent, bottomPercent, true)

    fun centerWindow(): Boolean = centerWindow

    fun forContentViewport(center: Boolean): CaptureOptions {
        if (mode != Mode.FIT && mode != Mode.CROP) {
            throw IllegalArgumentException("Content viewport supports fit/crop only")
        }
        return CaptureOptions(mode, leftPercent, topPercent, rightPercent, bottomPercent, center, true)
    }

    fun contentViewport(): Boolean = contentViewport

    fun mode(): Mode = mode

    fun leftPercent(): Int = leftPercent

    fun topPercent(): Int = topPercent

    fun rightPercent(): Int = rightPercent

    fun bottomPercent(): Int = bottomPercent

    fun token(): String {
        if (contentViewport) {
            return "viewport-" + (if (centerWindow) "center-" else "full-") + (if (mode == Mode.FIT) "fit" else "crop")
        }
        if (centerWindow) {
            if (mode != Mode.FIT && mode != Mode.CROP) {
                throw IllegalArgumentException("Center window requires fit/crop")
            }
            return "center-" + (if (mode == Mode.FIT) "fit" else "crop")
        }
        return when (mode) {
            Mode.FIT -> "fit"
            Mode.CROP -> "crop"
            Mode.STRETCH -> "stretch"
            Mode.REGION -> "region:$leftPercent:$topPercent:$rightPercent:$bottomPercent"
        }
    }

    /** Allowlisted tokens only: validates first, then emits fixed words/decimal values. */
    fun shellArguments(): String = token().replace(':', ' ')

    override fun toString(): String = token()

    companion object {
        private const val MAX_PERCENT = 100
        private const val MIN_REGION_PERCENT = 5
        private const val MAX_TOKEN_CHARS = 32

        @JvmStatic
        fun of(mode: Mode?): CaptureOptions {
            Objects.requireNonNull(mode, "Capture mode required")
            if (mode == Mode.REGION) {
                throw IllegalArgumentException("Region requires four explicit percentages")
            }
            return CaptureOptions(mode!!, 0, 0, MAX_PERCENT, MAX_PERCENT)
        }

        @JvmStatic
        fun region(left: Int, top: Int, right: Int, bottom: Int): CaptureOptions {
            if (left < 0 || left > MAX_PERCENT || top < 0 || top > MAX_PERCENT ||
                right < 0 || right > MAX_PERCENT || bottom < 0 || bottom > MAX_PERCENT ||
                right - left < MIN_REGION_PERCENT || bottom - top < MIN_REGION_PERCENT) {
                throw IllegalArgumentException("Region must be inside 0..100%, each span at least 5%")
            }
            return CaptureOptions(Mode.REGION, left, top, right, bottom)
        }

        @JvmStatic
        fun parse(token: String?): CaptureOptions {
            if (token == null || token.isEmpty() || token.length > MAX_TOKEN_CHARS) {
                throw IllegalArgumentException("Missing or oversized capture selection")
            }
            if (token.startsWith("viewport-")) {
                return when (token) {
                    "viewport-full-fit" -> of(Mode.FIT).forContentViewport(false)
                    "viewport-full-crop" -> of(Mode.CROP).forContentViewport(false)
                    "viewport-center-fit" -> of(Mode.FIT).forContentViewport(true)
                    "viewport-center-crop" -> of(Mode.CROP).forContentViewport(true)
                    else -> throw IllegalArgumentException("Unknown fixed content viewport")
                }
            }
            if (token.startsWith("center-")) {
                val selection = token.substring(7)
                if ("fit" != selection && "crop" != selection) {
                    throw IllegalArgumentException("Center window supports explicit fit/crop only")
                }
                return parse(selection).forCenterWindow()
            }
            // Kotlin's split keeps trailing empty fields, like Java's split(":", -1).
            val words = token.split(":")
            if (words.size == 1) {
                return when (token) {
                    "fit" -> of(Mode.FIT)
                    "crop" -> of(Mode.CROP)
                    "stretch" -> of(Mode.STRETCH)
                    else -> throw IllegalArgumentException("Unknown capture selection")
                }
            }
            if (words.size != 5 || "region" != words[0]) {
                throw IllegalArgumentException("Expected region:left:top:right:bottom")
            }
            val values = IntArray(4)
            for (index in values.indices) {
                val word = words[index + 1]
                if (word.isEmpty() || word.length > 3) {
                    throw IllegalArgumentException("Percentage must be an unsigned integer")
                }
                for (character in word) {
                    if (character < '0' || character > '9') {
                        throw IllegalArgumentException("Only decimal percentages are permitted")
                    }
                }
                values[index] = Integer.parseInt(word)
            }
            return region(values[0], values[1], values[2], values[3])
        }

        /** Decodes only the fixed shell capture suffix; legacy invocations remain stretch. */
        @JvmStatic
        fun fromShellArguments(args: Array<String>?): CaptureOptions {
            if (args == null || args.isEmpty()) {
                throw IllegalArgumentException("Missing shell capture arguments")
            }
            if (args.size == 1 || (args.size == 2 && "live" == args[1])) {
                return of(Mode.STRETCH)
            }
            if (args.size == 3 && ("remote" == args[1] || "workspace" == args[1])) {
                return of(Mode.STRETCH)
            }
            if ("live" != args[1]) {
                throw IllegalArgumentException("Geometry options require the controlled live stream")
            }
            // Source selectors have different arities: remote consumes a component,
            // named consumes only its keyword. Geometry starts AFTER that selector.
            var geometryStart = 2
            if (args.size > 2 && ("remote" == args[2] || "workspace" == args[2])) {
                if (args.size < 4) {
                    throw IllegalArgumentException("Remote display component is required")
                }
                geometryStart = 4
            } else if (args.size > 2 && "named" == args[2]) {
                geometryStart = 3
            }
            if (args.size == geometryStart) {
                return of(Mode.STRETCH)
            }
            if (args.size == geometryStart + 1) {
                return parse(args[geometryStart])
            }
            if (args.size != geometryStart + 5 || "region" != args[geometryStart]) {
                throw IllegalArgumentException("Unexpected shell capture geometry arguments")
            }
            return parse("region:" + args[geometryStart + 1] + ":" + args[geometryStart + 2] + ":" +
                args[geometryStart + 3] + ":" + args[geometryStart + 4])
        }
    }
}
