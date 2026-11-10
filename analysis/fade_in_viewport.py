"""RWVG fade-in for the exact 1.19.3 instrument components (HMI, KZB).

The APK (ContentViewport RWVG) draws sharp source 130 px beyond the clear full opening at the sides and 50 px
above/below (small window 200 / 50), and the small window now uses the whole 1:1 frame like the full one.
On the instrument side that needs:
  KZB       DXFullDark / DXCenterDark replaced by the fade-in masks (clear opening unchanged, alpha only lower);
            both use the straight B2 multiplier (RGB 255 where alpha > 0), since both layouts are 1:1 frames now.
  HMI       publishes capability RWVG instead of RWVF (the APK and dashcast-receiver compare it).
That the small layout becomes straight alpha with a 1:1 window is an inference from the full layout, not a
vehicle readback. If the small window shows a bright halo on the car, DXCenterDark needs RGB = alpha again.
"""
import io
import struct
from pathlib import Path

import numpy as np
from PIL import Image

from analyze_kzb_hmi import KzbDirectory
from navigation_layout import digest, repack
from elf_patch import Patch

ROOT = Path(__file__).resolve().parents[1]
HMI_SHA = "94afdc93bb641dd2d9e9b7cd62e0ffaf4094ac7be376f605fbb05c9eaa910bc8"
KZB_SHA = "24909ff06c5bbf3e6574e40bce39c35a6ddc1967b3c753f3237577d7881b81bf"
CAPABILITY = 0x52575640  # RWVG
# Encoded rectangles the APK fills with sharp source (ContentViewport.full() / center()).
FULL_RECT = (4, 89, 1912, 498)
CENTER_RECT = (399, 101, 1122, 486)
MASKS = {
    "/Resource Files/Images/DXFullDark.png": (ROOT / "instrument/assets/rwvg/DXFullDark.png",
        "52952c86dc211e8f97ceb154e8b5b4062a1fc18206d88e8e1ee7964bc1037b6c", FULL_RECT),
    "/Resource Files/Images/DXCenterDark.png": (ROOT / "instrument/assets/rwvg/DXCenterDark.png",
        "2d056caff3dcf873a0767fd83e0471dfd6a97cd44643baac41d25a5f79d81335", CENTER_RECT),
}
# (name, VA, original bytes, new bytes, expected new Thumb disassembly).
HMI_PATCHES = [("published_capability_rwvg", 0x86a2a3c, "45f23f60", "45f24060", "movw r0, #0x5640")]


def _patch(raw, expected_sha, patches, thumb):
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB
    patch = Patch(raw, expected_sha)
    for name, address, before, after, text in patches:
        new = bytes.fromhex(after)
        patch.put(name, address, new, bytes.fromhex(before))
        decoded = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB if thumb else CS_MODE_ARM).disasm(new, address))
        if len(decoded) != 1 or f"{decoded[0].mnemonic} {decoded[0].op_str}" != text:
            raise ValueError("Patched instruction does not decode as intended: " + name)
    return patch.finish()


def png_rgba(raw):
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not a PNG mask")
    at, chunks = 8, []
    while at < len(raw):
        size = struct.unpack_from(">I", raw, at)[0]
        chunks.append(raw[at + 4:at + 8].decode("ascii"))
        at += size + 12
    if at != len(raw) or chunks[0] != "IHDR" or chunks[-1] != "IEND" or set(chunks) != {"IHDR", "IDAT", "IEND"}:
        raise ValueError("Only an IHDR/IDAT/IEND mask is allowed")
    image = Image.open(io.BytesIO(raw))
    if image.mode != "RGBA" or image.size != (1920, 720):
        raise ValueError("Mask must be 1920x720 RGBA")
    return np.asarray(image)


def check_mask(old, new, rect):
    """Clear opening kept, alpha never higher, opaque outside the sharp rectangle, straight B2 multiplier."""
    a_old, a_new = old[..., 3], new[..., 3]
    if not np.all(a_new[a_old == 0] == 0):
        raise ValueError("The clear opening changed")
    if np.any(a_new > a_old):
        raise ValueError("Fade-in may only lower alpha")
    x, y, w, h = rect
    band = np.argwhere(a_new < 255)
    if band[:, 1].min() < x or band[:, 1].max() >= x + w or band[:, 0].min() < y or band[:, 0].max() >= y + h:
        raise ValueError("Transparent pixels outside the rectangle the APK fills")
    if not all(np.array_equal(new[..., c], np.where(a_new > 0, 255, 0)) for c in range(3)):
        raise ValueError("RGB must be the straight B2 multiplier")
    return dict(clear_pixels_before=int((a_old == 0).sum()), clear_pixels_after=int((a_new == 0).sum()),
                band_bbox=[int(band[:, 1].min()), int(band[:, 0].min()), int(band[:, 1].max()), int(band[:, 0].max())])


def patch_kzb(base):
    if digest(base) != KZB_SHA:
        raise ValueError("Exact 1.19.3 KZB required; do not mix firmware baselines")
    d = KzbDirectory(base)
    if repack(d, {}, {}) != base:
        raise ValueError("No-change KZB rebuild must be byte-identical")
    changes, masks = {}, {}
    for resource, (path, sha, rect) in MASKS.items():
        if path.is_symlink() or not path.is_file():
            raise ValueError("Use the exact regular mask file")
        raw = path.read_bytes()
        if digest(raw) != sha:
            raise ValueError("Mask bytes differ from the reviewed image: " + path.name)
        old = d.read(resource)
        if old[:16] != bytes(16) or struct.unpack_from("<I", old, 16)[0] != len(old) - 20:
            raise ValueError("Image resource header changed")
        masks[resource] = dict(png_sha256=sha, bytes=len(raw), rect=list(rect),
                               **check_mask(png_rgba(old[20:]), png_rgba(raw), rect))
        changes[resource] = old[:16] + struct.pack("<I", len(raw)) + raw
    result = repack(d, changes, {})
    after = KzbDirectory(result)
    if list(d.records) != list(after.records):
        raise ValueError("KZB directory names/order changed")
    modified = [name for name in d.records if d.read(name) != after.read(name)]
    if sorted(modified) != sorted(changes):
        raise ValueError("KZB changes escaped the two mask resources")
    for name in d.records:
        for key in ("id", "class_id", "flags"):
            if d.records[name][key] != after.records[name][key]:
                raise ValueError("KZB record metadata changed")
    return result, dict(input_sha256=digest(base), output_sha256=digest(result), changed_resources=modified,
        relocated_resources=sum(d.records[n]["offset"] != after.records[n]["offset"] for n in d.records),
        bytes_added=len(result) - len(base), masks=masks, theme_backings_unchanged=True)


def build(hmi, kzb):
    if digest(hmi) != HMI_SHA:
        raise ValueError("Exact 1.19.3 HMI required; do not mix firmware baselines")
    new_hmi, hmi_report = _patch(hmi, HMI_SHA, HMI_PATCHES, thumb=True)
    new_kzb, kzb_report = patch_kzb(kzb)
    return new_hmi, new_kzb, dict(capability=hex(CAPABILITY), hmi=hmi_report,
        kzb=kzb_report, center_window=[0, 0, 1920, 720],
        small_layout_straight_alpha_is_inference=True, deploy_with_apk_1_22=True, vehicle=False)
