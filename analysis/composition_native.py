"""Exact 0.9.1-to-composition adaptation. No SDK/physical composition claim."""
import re
from background_poc_native import ROOT, RECEIVER_BASE_SHA, apply_receiver_overlay, digest
from ref2_noauth_native import Patch, NOP

SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-composition.arm"
POC_SHA = "75239aac25f5418386b088305f46213e66985142f8772b46a9568416f8627715"


def configure_alpha(original):
    lines = original.replace(b"\r", b"").splitlines()
    if lines != [b"SurfaceBitsRed = 8", b"SurfaceBitsGreen = 8", b"SurfaceBitsBlue = 8"]:
        raise ValueError("Unexpected HMI application.cfg; refuse to replace user settings")
    boundary = b"" if original.endswith(b"\n") else b"\r\n"
    return original + boundary + b"SurfaceBitsAlpha = 8\r\n"


def apply_composition_overlay(poc):
    # The exact baseline identity is independently supplied by the old builder.
    patch = Patch(poc, digest(poc))
    if digest(poc) != POC_SHA:
        raise ValueError("Composition requires the exact delivered 0.9.1 receiver")
    source = SOURCE.read_text("utf-8")
    blocks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    expected = {"continuous_idle_and_layer": (0x1029d4, 80),
                "composition_window_order": (0x102c80, 72), "composition_rect": (0x1041ac, 316)}
    seen = set()
    for i in range(1, len(blocks), 4):
        name, address, size, code = blocks[i:i + 4]
        address, size = int(address, 16), int(size)
        if name in seen or expected.get(name) != (address, size):
            raise ValueError("Composition slot changed")
        if name != "composition_rect":
            off = patch.offset(address, size)
            if poc[off:off + size] != NOP * (size // 4):
                raise ValueError("Helper is not unused pre-existing NOP padding")
        patch.arm(name, address, size, re.sub(r"(?m)//.*$", "", code).strip())
        seen.add(name)
    if seen != set(expected):
        raise ValueError("Composition slots missing")
    patch.arm("continuous_frame_idle_gate", 0x10191c, 4, "bl #0x1029d4")
    patch.arm("owned_parent_layer_format", 0x104b20, 4, "bl #0x1029ec")
    patch.arm("owned_window_layer_before_post", 0x104d40, 4, "bl #0x102c80")
    patch.put("owned_underlay_order_string", 0x10ae68, b"%s::-3\n\0", expected=bytes(8))
    binary, report = patch.finish()
    report.update(source_sha256=digest(source.encode()), fullscreen_preset=(0, 0, 1920, 720, 30, 1),
                  fullscreen_parent_z=-3, small_window_unchanged=True, foreground_pipeline_unchanged=True,
                  hmi_five_second_bridge_unchanged=True, continuous_frame_idle_ms=1860000,
                  heartbeat_limit_ms=15000, physical_composition_tested=False)
    return binary, report


def build_composition_receiver(base):
    if digest(base) != RECEIVER_BASE_SHA:
        raise ValueError("Expected frozen 0.8.2 receiver input")
    poc, _ = apply_receiver_overlay(base)
    return apply_composition_overlay(poc)
