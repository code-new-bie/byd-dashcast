package com.byd.dashcast.phase1;

import java.util.Objects;

/** Immutable per-session capture selection; no instrument window or codec size changes.
 * @author Codex
 * @date 2026/10/14
 */
final class CaptureOptions {
    /** Capture-only choices, never a QNX window layout request. */
    enum Mode {
        /** Entire source, preserving aspect ratio with letterboxing. */
        FIT,
        /** Center crop preserving aspect ratio and filling the encoder surface. */
        CROP,
        /** Operator-selected source region fitted without stretching. */
        REGION,
        /** Original 0.5.0 projection, retained for explicit regression comparisons. */
        STRETCH
    }

    private static final int MAX_PERCENT = 100;
    private static final int MIN_REGION_PERCENT = 5;
    private static final int MAX_TOKEN_CHARS = 32;
    private final Mode mode;
    private final Integer leftPercent;
    private final Integer topPercent;
    private final Integer rightPercent;
    private final Integer bottomPercent;

    private CaptureOptions(Mode mode, int left, int top, int right, int bottom) {
        this.mode = mode;
        leftPercent = left;
        topPercent = top;
        rightPercent = right;
        bottomPercent = bottom;
    }

    static CaptureOptions of(Mode mode) {
        Objects.requireNonNull(mode, "Capture mode required");
        if (mode == Mode.REGION) {
            throw new IllegalArgumentException("Region requires four explicit percentages");
        }
        return new CaptureOptions(mode, 0, 0, MAX_PERCENT, MAX_PERCENT);
    }

    static CaptureOptions region(int left, int top, int right, int bottom) {
        if (left < 0 || left > MAX_PERCENT || top < 0 || top > MAX_PERCENT
                || right < 0 || right > MAX_PERCENT || bottom < 0 || bottom > MAX_PERCENT
                || right - left < MIN_REGION_PERCENT || bottom - top < MIN_REGION_PERCENT) {
            throw new IllegalArgumentException("Region must be inside 0..100%, each span at least 5%");
        }
        return new CaptureOptions(Mode.REGION, left, top, right, bottom);
    }

    static CaptureOptions parse(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw new IllegalArgumentException("Missing or oversized capture selection");
        }
        String[] words = token.split(":", -1);
        if (words.length == 1) {
            switch (token) {
                case "fit":
                    return of(Mode.FIT);
                case "crop":
                    return of(Mode.CROP);
                case "stretch":
                    return of(Mode.STRETCH);
                default:
                    throw new IllegalArgumentException("Unknown capture selection");
            }
        }
        if (words.length != 5 || !"region".equals(words[0])) {
            throw new IllegalArgumentException("Expected region:left:top:right:bottom");
        }
        int[] values = new int[4];
        for (int index = 0; index < values.length; index++) {
            String word = words[index + 1];
            if (word.isEmpty() || word.length() > 3) {
                throw new IllegalArgumentException("Percentage must be an unsigned integer");
            }
            for (int cursor = 0; cursor < word.length(); cursor++) {
                if (word.charAt(cursor) < '0' || word.charAt(cursor) > '9') {
                    throw new IllegalArgumentException("Only decimal percentages are permitted");
                }
            }
            values[index] = Integer.parseInt(word);
        }
        return region(values[0], values[1], values[2], values[3]);
    }

    /** Decodes only the fixed shell capture suffix; legacy invocations remain stretch. */
    static CaptureOptions fromShellArguments(String[] args) {
        if (args == null || args.length < 1) {
            throw new IllegalArgumentException("Missing shell capture arguments");
        }
        if (args.length == 1 || args.length == 2 && "live".equals(args[1])) {
            return of(Mode.STRETCH);
        }
        if (args.length == 3 && "remote".equals(args[1])) {
            return of(Mode.STRETCH);
        }
        if (!"live".equals(args[1])) {
            throw new IllegalArgumentException("Geometry options require the controlled live stream");
        }
        int geometryStart = args.length > 2 && "remote".equals(args[2]) ? 4 : 2;
        if (args.length == geometryStart) {
            return of(Mode.STRETCH);
        }
        if (args.length == geometryStart + 1) {
            return parse(args[geometryStart]);
        }
        if (args.length != geometryStart + 5 || !"region".equals(args[geometryStart])) {
            throw new IllegalArgumentException("Unexpected shell capture geometry arguments");
        }
        return parse("region:" + args[geometryStart + 1] + ":" + args[geometryStart + 2] + ":"
                + args[geometryStart + 3] + ":" + args[geometryStart + 4]);
    }

    Mode mode() {
        return mode;
    }

    int leftPercent() {
        return leftPercent;
    }

    int topPercent() {
        return topPercent;
    }

    int rightPercent() {
        return rightPercent;
    }

    int bottomPercent() {
        return bottomPercent;
    }

    String token() {
        switch (mode) {
            case FIT:
                return "fit";
            case CROP:
                return "crop";
            case STRETCH:
                return "stretch";
            case REGION:
                return "region:" + leftPercent + ":" + topPercent + ":" + rightPercent + ":" + bottomPercent;
            default:
                throw new IllegalStateException("Unsupported mode");
        }
    }

    /** Allowlisted tokens only: validates first, then emits fixed words/decimal values. */
    String shellArguments() {
        return token().replace(':', ' ');
    }

    @Override
    public String toString() {
        return token();
    }
}
