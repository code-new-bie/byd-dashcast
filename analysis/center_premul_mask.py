"""1.19.3 small-layout multiplier for the exact 1.19.2 B2 HMI/KZB pair.

Field photos of 1.19.2 showed the full layout composited as straight alpha
but the small layout as premultiplied alpha. B2 mode 5 multiplies the
framebuffer by texture RGB, so DXCenterDark keeps its alpha and uses RGB=A:
the small-layout framebuffer becomes (C*a, a) again while DXFullDark and the
HMI stay exactly B2. The composition split is inferred from vehicle photos,
not read back from the target compositor.
"""
import hashlib
import io
import struct
from pathlib import Path

import numpy as np
from PIL import Image

from analyze_kzb_hmi import KzbDirectory
from navigation_layout import digest, repack

ROOT = Path(__file__).resolve().parents[1]
MASK = ROOT / "instrument/assets/b3-center/DXCenterDark.png"
RESOURCE = "/Resource Files/Images/DXCenterDark.png"
HMI_SHA = "94afdc93bb641dd2d9e9b7cd62e0ffaf4094ac7be376f605fbb05c9eaa910bc8"
KZB_SHA = "bc2929b3b061a7ac8399537f6b42810d19de29b127c13036345edf39d21c9713"
MASK_SHA = "0e91c53698e5bc06d5739e8caf7a8467608cc8e8d64506bd3b1351eb8f471e75"
KZB_MD5 = "112569bd7e44d6815d618bdafcfbd7d8"
KZB_BYTES = 12446478


def png_chunks(raw):
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not a PNG mask")
    at, chunks = 8, []
    while at < len(raw):
        size = struct.unpack_from(">I", raw, at)[0]
        if at + size + 12 > len(raw):
            raise ValueError("Truncated PNG chunk")
        chunks.append(raw[at + 4:at + 8].decode("ascii"))
        at += size + 12
    if at != len(raw) or chunks[0] != "IHDR" or chunks[-1] != "IEND" or set(chunks) != {"IHDR", "IDAT", "IEND"}:
        raise ValueError("Only the inspected IHDR/IDAT/IEND mask is allowed")
    return chunks


def patch_kzb(base, mask=MASK):
    if digest(base) != KZB_SHA:
        raise ValueError("Exact 1.19.2 B2 KZB required; do not mix firmware baselines")
    d = KzbDirectory(base)
    if repack(d, {}, {}) != base:
        raise ValueError("No-change KZB rebuild must be byte-identical")
    path = Path(mask)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Use the exact regular center mask file")
    raw = path.read_bytes()
    if digest(raw) != MASK_SHA:
        raise ValueError("Center mask bytes differ from the reviewed image")
    chunks = png_chunks(raw)
    old = d.read(RESOURCE)
    if old[:16] != bytes(16) or struct.unpack_from("<I", old, 16)[0] != len(old) - 20:
        raise ValueError("B2 image resource header changed")
    a = np.asarray(Image.open(io.BytesIO(old[20:])).convert("RGBA"))
    image = Image.open(io.BytesIO(raw))
    if image.mode != "RGBA" or image.size != (1920, 720):
        raise ValueError("Center mask dimensions/format changed")
    b = np.asarray(image)
    if not np.array_equal(a[:, :, 3], b[:, :, 3]):
        raise ValueError("The B2 clear area or feather alpha has changed")
    if not all(np.array_equal(b[:, :, c], b[:, :, 3]) for c in range(3)):
        raise ValueError("The center multiplier requires RGB equal to alpha")
    change = old[:16] + struct.pack("<I", len(raw)) + raw
    result = repack(d, {RESOURCE: change}, {})
    after = KzbDirectory(result)
    if list(d.records) != list(after.records):
        raise ValueError("KZB directory names/order changed")
    modified = [name for name in d.records if d.read(name) != after.read(name)]
    if modified != [RESOURCE]:
        raise ValueError("KZB changes escaped the center mask resource")
    for name in d.records:
        for key in ("id", "class_id", "flags"):
            assert d.records[name][key] == after.records[name][key]
    assert after.read(RESOURCE) == change
    if len(result) != KZB_BYTES or hashlib.md5(result).hexdigest() != KZB_MD5:
        raise ValueError("1.19.3 KZB differs from the exact reviewed reconstruction")
    return result, dict(input_sha256=digest(base), output_sha256=digest(result), output_md5=KZB_MD5,
        no_change_selftest_identical=True, changed_resources=modified, resources=len(after.records),
        relocated_resources=sum(d.records[name]["offset"] != after.records[name]["offset"] for name in d.records),
        bytes_added=len(result) - len(base), mask=dict(resource=RESOURCE, png_sha256=MASK_SHA, bytes=len(raw),
            chunks=chunks, alpha_byte_identical=True, rgb_equals_alpha=True),
        full_mask_and_theme_backings_unchanged=True, vehicle=False)


def build_pair(hmi, kzb, mask=MASK):
    # The HMI is B2 unchanged; it is checked so only a matching pair is emitted.
    if digest(hmi) != HMI_SHA:
        raise ValueError("Exact 1.19.2 B2 HMI required; do not mix firmware baselines")
    final_kzb, report = patch_kzb(kzb, mask)
    return hmi, final_kzb, dict(hmi=dict(sha256=HMI_SHA, unchanged_from_b2=True), kzb=report,
        deploy_both_components_together=True, small_layout_premultiplied_is_photo_inference=True, vehicle=False)
