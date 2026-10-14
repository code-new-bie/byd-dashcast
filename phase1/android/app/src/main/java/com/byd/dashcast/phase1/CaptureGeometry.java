package com.byd.dashcast.phase1;

import java.util.Objects;

/** Pure, bounded source/destination geometry for the 1920x720 encoder surface.
 * @author Codex
 * @date 2026/10/14
 */
final class CaptureGeometry {
    static final int ENCODED_WIDTH = NoAuthVideoProtocol.WIDTH;
    static final int ENCODED_HEIGHT = NoAuthVideoProtocol.HEIGHT;
    private static final int MAX_DISPLAY_SIZE = 4096;
    private static final int PERCENT_SCALE = 100;
    private final Bounds source;
    private final Bounds destination;

    /** Immutable pixel rectangle, with exclusive right/bottom and an explicit origin. */
    static final class Bounds {
        private final Integer left;
        private final Integer top;
        private final Integer right;
        private final Integer bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int left() { return left; }
        int top() { return top; }
        int right() { return right; }
        int bottom() { return bottom; }
        int width() { return right - left; }
        int height() { return bottom - top; }

        @Override
        public String toString() {
            return "[" + left + "," + top + "," + right + "," + bottom + "]";
        }
    }

    private CaptureGeometry(Bounds source, Bounds destination) {
        this.source = source;
        this.destination = destination;
    }

    static CaptureGeometry create(int displayWidth, int displayHeight, CaptureOptions options) {
        Objects.requireNonNull(options, "Capture selection required");
        if (displayWidth < 2 || displayHeight < 2 || displayWidth > MAX_DISPLAY_SIZE
                || displayHeight > MAX_DISPLAY_SIZE) {
            throw new IllegalArgumentException("Display dimensions must be 2..4096 pixels");
        }
        Bounds input = bounds(0, 0, displayWidth, displayHeight);
        if (options.mode() == CaptureOptions.Mode.REGION) {
            input = bounds(displayWidth * options.leftPercent() / PERCENT_SCALE,
                    displayHeight * options.topPercent() / PERCENT_SCALE,
                    displayWidth * options.rightPercent() / PERCENT_SCALE,
                    displayHeight * options.bottomPercent() / PERCENT_SCALE);
        }
        if (options.mode() == CaptureOptions.Mode.CROP) {
            input = crop(input);
        }
        Bounds output = options.mode() == CaptureOptions.Mode.STRETCH
                || options.mode() == CaptureOptions.Mode.CROP
                ? bounds(0, 0, ENCODED_WIDTH, ENCODED_HEIGHT) : fit(input);
        return new CaptureGeometry(input, output);
    }

    private static Bounds crop(Bounds input) {
        int width = input.width();
        int height = input.height();
        if ((long)width * ENCODED_HEIGHT > (long)height * ENCODED_WIDTH) {
            width = Math.max(2, height * ENCODED_WIDTH / ENCODED_HEIGHT);
        } else {
            height = Math.max(2, width * ENCODED_HEIGHT / ENCODED_WIDTH);
        }
        width = Math.min(width, input.width());
        height = Math.min(height, input.height());
        int left = input.left() + (input.width() - width) / 2;
        int top = input.top() + (input.height() - height) / 2;
        return bounds(left, top, left + width, top + height);
    }

    private static Bounds fit(Bounds input) {
        int width = ENCODED_WIDTH;
        int height = ENCODED_HEIGHT;
        if ((long)input.width() * ENCODED_HEIGHT > (long)input.height() * ENCODED_WIDTH) {
            height = Math.max(1, ENCODED_WIDTH * input.height() / input.width());
        } else {
            width = Math.max(1, ENCODED_HEIGHT * input.width() / input.height());
        }
        int left = (ENCODED_WIDTH - width) / 2;
        int top = (ENCODED_HEIGHT - height) / 2;
        return bounds(left, top, left + width, top + height);
    }

    private static Bounds bounds(int left, int top, int right, int bottom) {
        if (left < 0 || top < 0 || right <= left || bottom <= top) {
            throw new IllegalArgumentException("Empty or negative capture rectangle");
        }
        return new Bounds(left, top, right, bottom);
    }

    Bounds source() {
        return source;
    }

    Bounds destination() {
        return destination;
    }

    @Override
    public String toString() {
        return "source=" + source + " destination=" + destination + " encoded="
                + ENCODED_WIDTH + "x" + ENCODED_HEIGHT;
    }
}
