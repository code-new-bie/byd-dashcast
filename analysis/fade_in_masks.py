"""Offline generator for the RWVG fade-in masks (instrument/assets/rwvg/*.png); the build only reads the PNGs.

The clear opening of each 1.19.3 mask (alpha == 0) is kept exactly. Outside it alpha rises along a long-tailed
S curve (normalised error function, the same profile as the user's reference picture) over an anisotropic
distance: FULL 130 px at the sides / 50 px above and below, CENTER 200 / 50. The APK draws sharp source under
the whole band (ContentViewport RWVG), so the picture fades in instead of turning blurry.
RGB is the B2 straight multiplier: 255 where alpha > 0, 0 in the clear opening (both layouts are now 1:1 frames).
Needs scipy; run: python analysis/fade_in_masks.py
"""
import io
from math import erf, sqrt
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {"DXFullDark": (ROOT / "instrument/assets/b2/DXFullDark.png", 130, 50),
           "DXCenterDark": (ROOT / "instrument/assets/b3-center/DXCenterDark.png", 200, 50)}
OUT = ROOT / "instrument/assets/rwvg"
SHARPNESS = 4.2


def ease(t):
    phi = np.vectorize(lambda x: 0.5 * (1 + erf(x / sqrt(2))))
    lo, hi = phi(-.5 * SHARPNESS), phi(.5 * SHARPNESS)
    return (phi((np.clip(t, 0, 1) - .5) * SHARPNESS) - lo) / (hi - lo)


def fade_in(alpha, horizontal, vertical):
    clear = alpha == 0
    distance = ndimage.distance_transform_edt(~clear, sampling=(horizontal / vertical, 1.0))
    value = np.where(clear, 0, np.round(255 * ease(distance / horizontal))).astype(np.uint8)
    rgb = np.where(value > 0, 255, 0).astype(np.uint8)
    return np.dstack([rgb, rgb, rgb, value])


def main():
    OUT.mkdir(exist_ok=True)
    for name, (source, horizontal, vertical) in SOURCES.items():
        alpha = np.asarray(Image.open(source).convert("RGBA"))[..., 3]
        rgba = fade_in(alpha, horizontal, vertical)
        buffer = io.BytesIO()
        Image.fromarray(rgba, "RGBA").save(buffer, "PNG", optimize=True)
        (OUT / (name + ".png")).write_bytes(buffer.getvalue())
        print(name, len(buffer.getvalue()), "bytes")


if __name__ == "__main__":
    main()
