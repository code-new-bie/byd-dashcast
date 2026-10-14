package com.byd.dashcast.phase1;

/** Exposes actual compiled production geometry to independent desktop fixture tooling.
 * @author Codex
 * @date 2026/10/14
 */
public final class CaptureGeometryOfflineCheck {
    private CaptureGeometryOfflineCheck() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("width height allowlisted-capture-selection");
        }
        CaptureGeometry geometry = CaptureGeometry.create(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                CaptureOptions.parse(args[2]));
        CaptureGeometry.Bounds source = geometry.source();
        CaptureGeometry.Bounds target = geometry.destination();
        System.out.printf(java.util.Locale.ROOT, "%d %d %d %d %d %d %d %d%n",
                source.left(), source.top(), source.width(), source.height(),
                target.left(), target.top(), target.width(), target.height());
    }
}
