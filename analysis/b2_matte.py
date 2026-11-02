"""B2 source-color multiplier for the exact 1.19 HMI/KZB pair.

The display-driver gfx path uses straight-alpha composition. Keep RGB=B
where the supplied aperture has alpha>0, RGB=0 in its clear interior, and
the original alpha everywhere. These two components must be deployed together.
No current-window API success or target hardware execution is assumed here.
"""
import hashlib
import io
import re
import struct
from pathlib import Path

import numpy as np
from PIL import Image
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB
from elftools.elf.elffile import ELFFile

from analyze_kzb_hmi import KzbDirectory
from navigation_layout import digest, repack
from ref2_noauth_native import Patch
import aperture_fit_overlay as previous
from product_runtime_layout import CAPABILITY, RECURSIVE_2D, RENDER_MESH

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "instrument/native-hmi/background-b2.thumb"
MASKS = ROOT / "instrument/assets/b2"
HMI_SHA = "5dfef87a7786c3b6af5866b44572c795156473001f0ffa29e7a0f2d3d966b9a2"
KZB_SHA = "15822ca13bf5b2c8ca07d4c5a64bec6af1a1562117def4211af951bb46da0a63"
HMI_MD5 = "d87808a04fc74d5ed5ce2fe4c1d2da71"
KZB_MD5 = "283f693a72ac84dcd7982fabc5fc0a1e"
SITE = 0x086A2E50
ORIGINAL = bytes.fromhex("40f2023100220b46")
EXPECTED = bytes.fromhex("40f2003100228b1c")
MASK_SHA = {"DXFullDark.png": "b36b53392adb3cbf5cbbff9c558086f9c9219e804e66009ea9eae2a41947cbfe",
            "DXCenterDark.png": "eb34697ca949808502749f11b591d24f1c7a5346f02695b0104a68a13aabc7de"}


def patch_hmi(base):
    patch = Patch(base, HMI_SHA)
    source = SOURCE.read_text(encoding="utf-8")
    code = re.sub(r"(?m)//.*$", "", source).strip()
    raw = bytes(Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(code, addr=SITE)[0])
    if raw != EXPECTED or patch.offset(SITE, len(raw)) != 0x65BE50:
        raise ValueError("B2 assembly source no longer matches the inspected slot")
    instructions = [(i.mnemonic, i.op_str) for i in Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(raw, SITE)]
    if instructions != [("movw", "r1, #0x300"), ("movs", "r2, #0"), ("adds", "r3, r1, #2")]:
        raise ValueError("Unexpected B2 instruction semantics")
    patch.put("private_mode5_source_color_and_source_alpha", SITE, raw, expected=ORIGINAL)
    result, report = patch.finish()
    changed = [i for i, (a, b) in enumerate(zip(base, result)) if a != b]
    if changed != [0x65BE52, 0x65BE56, 0x65BE57] or hashlib.md5(result).hexdigest() != HMI_MD5:
        raise ValueError("B2 HMI differs from the exact three-byte plan")
    a, b = ELFFile(io.BytesIO(base)), ELFFile(io.BytesIO(result))
    assert len(base) == len(result) and a.header == b.header
    assert [dict(s.header) for s in a.iter_segments()] == [dict(s.header) for s in b.iter_segments()]
    report.update(changed_bytes=len(changed), changed_file_offsets=changed, output_md5=HMI_MD5,
        assembly_source_sha256=digest(source.encode()), blend_factors=[0, 768, 0, 770],
        original_1190_creation_hook_preserved=True, usage_hook_1191_not_applied=True,
        loader_headers_imports_and_all_other_code_unchanged=True, vehicle=False)
    return result, report


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
        raise ValueError("Only the inspected IHDR/IDAT/IEND masks are allowed")
    return chunks


def patch_kzb(base, masks=MASKS):
    if digest(base) != KZB_SHA:
        raise ValueError("Exact 1.17--1.19 KZB required; do not mix firmware baselines")
    d = KzbDirectory(base)
    if repack(d, {}, {}) != base:
        raise ValueError("No-change KZB rebuild must be byte-identical")
    changes, rows = {}, []
    for filename, expected_sha in MASK_SHA.items():
        path = Path(masks) / filename
        if path.is_symlink() or not path.is_file():
            raise ValueError("Use the exact regular B2 mask files")
        raw = path.read_bytes()
        if digest(raw) != expected_sha:
            raise ValueError("B2 mask bytes differ from the reviewed supplied image")
        chunks = png_chunks(raw)
        resource = "/Resource Files/Images/" + filename
        old = d.read(resource)
        if old[:16] != bytes(16) or struct.unpack_from("<I", old, 16)[0] != len(old) - 20:
            raise ValueError("Original image resource header changed")
        a = np.asarray(Image.open(io.BytesIO(old[20:])).convert("RGBA"))
        image = Image.open(io.BytesIO(raw))
        if image.mode != "RGBA" or image.size != (1920, 720):
            raise ValueError("B2 mask dimensions/format changed")
        b = np.asarray(image)
        if not np.array_equal(a[:, :, 3], b[:, :, 3]):
            raise ValueError("The supplied clear area or feather alpha has changed")
        if not (np.all(b[b[:, :, 3] > 0, :3] == 255) and np.all(b[b[:, :, 3] == 0, :3] == 0)):
            raise ValueError("B2 requires white RGB at positive alpha and black RGB at zero alpha")
        changes[resource] = old[:16] + struct.pack("<I", len(raw)) + raw
        rows.append(dict(resource=resource, png_sha256=digest(raw), bytes=len(raw), chunks=chunks,
            alpha_byte_identical=True, rgb_not_premultiplied=True,
            clear_pixels=int((b[:, :, 3] == 0).sum()), feather_pixels=int(((b[:, :, 3] > 0) & (b[:, :, 3] < 255)).sum())))
    result = repack(d, changes, {})
    after = KzbDirectory(result)
    if list(d.records) != list(after.records):
        raise ValueError("KZB directory names/order changed")
    modified = [name for name in d.records if d.read(name) != after.read(name)]
    if set(modified) != set(changes):
        raise ValueError("KZB changes escaped the two owned mask resources")
    for name in d.records:
        for key in ("id", "class_id", "flags"):
            assert d.records[name][key] == after.records[name][key]
        if name in changes:
            assert after.read(name) == changes[name]
            assert after.records[name]["size"] == after.records[name]["decoded_size"] == len(changes[name])
        else:
            assert d.read(name) == after.read(name)
    if len(result) != 12446186 or hashlib.md5(result).hexdigest() != KZB_MD5:
        raise ValueError("B2 KZB differs from the exact reviewed reconstruction")
    return result, dict(input_sha256=digest(base), output_sha256=digest(result), output_md5=KZB_MD5,
        no_change_selftest_identical=True, changed_resources=modified, resources=len(after.records),
        relocated_resources=sum(d.records[name]["offset"] != after.records[name]["offset"] for name in d.records),
        bytes_added=len(result) - len(base), masks=rows, original_ids_flags_other_resources_unchanged=True,
        startup_states_geometry_bindings_and_theme_backings_unchanged=True, vehicle=False)


def build_pair(hmi, kzb, masks=MASKS):
    # Return nothing until both matching components have validated completely.
    final_hmi, hr = patch_hmi(hmi)
    final_kzb, kr = patch_kzb(kzb, masks)
    return final_hmi, final_kzb, dict(hmi=hr, kzb=kr, deploy_both_components_together=True,
        driver_nonpremultiplied_composition_is_static_analysis=True, vehicle=False)


def build_runtime_hmi(original):
    base, previous_report = previous.build_alpha_hmi(original)
    result, report = patch_hmi(base)
    return result, dict(previous_report, previous=previous_report, output_sha256=digest(result), b2=report)


def build_runtime_kzb(original, toc, masks=MASKS):
    base, previous_report = previous.build_runtime_kzb(original, toc)
    result, report = patch_kzb(base, masks)
    return result, dict(previous=previous_report, output_sha256=digest(result), b2=report)


build_plain_scene = previous.build_plain_scene
build_runtime_receiver = previous.build_runtime_receiver
