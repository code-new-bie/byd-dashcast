package com.byd.dashcast.phase1

import java.util.Locale

/** Exposes actual compiled production geometry to independent desktop fixture tooling.
 * @author Codex
 * @date 2026/10/14
 */
object CaptureGeometryOfflineCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) {
            throw IllegalArgumentException("width height allowlisted-capture-selection")
        }
        val geometry = CaptureGeometry.create(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
            CaptureOptions.parse(args[2]))
        val source = geometry.source()
        val target = geometry.destination()
        System.out.printf(Locale.ROOT, "%d %d %d %d %d %d %d %d%n",
            source.left(), source.top(), source.width(), source.height(),
            target.left(), target.top(), target.width(), target.height())
    }
}
