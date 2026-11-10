"""Versioned physical/encoded/content geometry; exact profiles, not field calibration."""
FULL_OUTER = (0, 0, 1920, 720)
FULL_INNER = (96, 128, 1728, 432)
FULL_SOURCE = (1920, 480)
CENTER_OUTER = (576, 128, 768, 432)
CENTER_INNER = (600, 152, 720, 384)
CENTER_SOURCE = (1200, 640)
CAPABILITY = 0x52575633  # RWV3: require the paired effective-viewport APK.


def encoded_inner(center):
    outer = CENTER_OUTER if center else FULL_OUTER
    inner = CENTER_INNER if center else FULL_INNER
    x, y, w, h = outer
    a, b, c, d = inner
    return ((a - x) * 1920 / w, (b - y) * 720 / h, c * 1920 / w, d * 720 / h)
