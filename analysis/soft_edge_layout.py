"""RWV5: wider native side feather, preserving compact mileage and safety data.

Compose the frozen 1.7.1 resource generator; only the two owned frame nodes and
their same-index private names change. No original warning/data/font is edited.
"""
import math
import struct

from analyze_kzb_hmi import KzbDirectory, node_head
from background_poc_native import apply_hmi_overlay
from compact_info_layout import build_compact_info_kzb
from display_fusion import encode_node, text
from integrated_layout import IntegratedSource, rectangles as prior_rectangles
from integrated_layout import FRAME_TOP as PRIOR_TOP, FRAME_BOTTOM as PRIOR_BOTTOM
from integrated_layout import CENTER_OUTER, CENTER_INNER, CENTER_SOURCE
from navigation_layout import dictionary, repack, srt, digest
from ref2_noauth_native import Patch

CAPABILITY = 0x52575635
FULL_INNER = (192, 144, 1536, 384)
FULL_SOURCE = (1536, 384)
FRAME_TOP, FRAME_BOTTOM = "DWTop", "DWBottom"
BRUSH = "kzb://bic_byd_stha/Brushes/Color Brush_BgBottom_Dark"
PRIOR_RX_SHA = "d56a72f709c14fb89a0ec79f498d8fe791cecd8cb27fa51f356cd698d887c5ee"


def rectangles(center):
    if center:
        return prior_rectangles(True)
    x, y, w, h = 16, 80, 1888, 464
    a, b, c, d = FULL_INNER
    rows = [(0, 0, 1920, y, 1), (0, y+h, 1920, 720-y-h, 1),
            (0, 0, x, 720, 1), (x+w, 0, 1920-x-w, 720, 1)]
    for i in range(24):
        alpha = (1 + math.cos(math.pi*(i+.5)/24))/2
        for l, t, width, height in (
            (0, y+(b-y)*i/24, 1920, (b-y)/24),
            (0, b+d+(y+h-b-d)*(23-i)/24, 1920, (y+h-b-d)/24),
            (x+(a-x)*i/24, 0, (a-x)/24, 720),
            (a+c+(x+w-a-c)*(23-i)/24, 0, (x+w-a-c)/24, 720)):
            rows.append((l, t, width, height, alpha))
    return rows


def encoded_inner(center):
    if not center:
        return FULL_INNER
    x, y, w, h = CENTER_OUTER
    a, b, c, d = CENTER_INNER
    return ((a-x)*1920/w, (b-y)*720/h, c*1920/w, d*720/h)


def frame_nodes(d, strings, factory, top, bottom):
    f = lambda value: struct.pack("<f", value)
    result = b""
    for center, root in ((False, top), (True, bottom)):
        children = [encode_node(d, strings, f"Edge{i:03d}", [
            ("Node.Width", f(w)), ("Node.Height", f(h)), ("Node.Opacity", f(alpha)),
            ("Node2D.RenderTransformation", srt(x, y)), ("Node2D.BackgroundBrush", text(BRUSH))])
            for i, (x, y, w, h, alpha) in enumerate(factory(center))]
        result += encode_node(d, strings, root, [("Node.Width", f(1920)),
            ("Node.Height", f(720)), ("Node.Opacity", f(0))], children)
    return result


def build_soft_edge_kzb(original, toc):
    base, prior = build_compact_info_kzb(original, toc)
    d = KzbDirectory(base)
    strings = d.dictionary("/$strings")
    rename = lambda value: value.replace(PRIOR_TOP, FRAME_TOP).replace(PRIOR_BOTTOM, FRAME_BOTTOM)
    renamed = [rename(value) for value in strings]
    if len(set(renamed)) != len(renamed):
        raise ValueError("Frame identity collision")
    raw = d.read("/StartupPrefab")
    start = 1855 + node_head(d, raw, 405)["end_properties"] - 476
    old_nodes = frame_nodes(d, strings, prior_rectangles, PRIOR_TOP, PRIOR_BOTTOM)
    new_nodes = frame_nodes(d, renamed, rectangles, FRAME_TOP, FRAME_BOTTOM)
    if raw[start:start+len(old_nodes)] != old_nodes or len(old_nodes) != len(new_nodes):
        raise ValueError("Exact owned RWV4 frame nodes required")
    output = repack(d, {"/$strings": dictionary(renamed), "/StartupPrefab":
        raw[:start]+new_nodes+raw[start+len(old_nodes):]}, {})
    current = KzbDirectory(output)
    changed = [n for n in d.records if d.read(n) != current.read(n)]
    if changed != ["/$strings", "/StartupPrefab"]:
        raise ValueError("Unexpected KZB delta")
    return output, dict(input_sha256=digest(original), prior_compact_sha256=digest(base),
        output_sha256=digest(output), changed_resources_from_171=changed,
        full_inner=list(FULL_INNER), full_source=list(FULL_SOURCE),
        old_side_feather=80, side_feather=176, center_geometry_unchanged=True,
        compact_mileage_and_all_adas_bindings_byte_exact=True,
        original_theme_brushes_byte_exact=True, rectangles_per_frame=100, prior=prior,
        real_kanzi_executed=False)


class SoftEdgeSource:
    def read_text(self, encoding):
        base = IntegratedSource().read_text(encoding)
        for old, new in (("movw r0, #0x5634", "movw r0, #0x5635"),
                         ('.asciz "DVTop"', '.asciz "DWTop"'),
                         ('.asciz "DVBottom"', '.asciz "DWBottom"')):
            if base.count(old) != 1:
                raise ValueError("Exact RWV4 bridge marker required")
            base = base.replace(old, new)
        return base


def build_soft_edge_hmi(original):
    output, report = apply_hmi_overlay(original, source_path=SoftEdgeSource())
    report.update(capability=CAPABILITY, source_sha256=digest(SoftEdgeSource().read_text("utf-8").encode()),
                  lease_and_safety_paths_unchanged=True)
    return output, report


def build_soft_edge_receiver(previous):
    patch = Patch(previous, PRIOR_RX_SHA)
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM
    asm = Ks(KS_ARCH_ARM, KS_MODE_ARM)
    start, capacity = 0x108898, 3232
    offset = patch.offset(start, capacity)
    pattern = bytes(asm.asm("ldr r2, [r6, #124]; movw r3, #0x5634; movt r3, #0x5257")[0])
    slot = previous[offset:offset+capacity]
    if slot.count(pattern) != 1:
        raise ValueError("Exact guarded RWV4 receiver required")
    patch.arm("paired_RWV5_wider_feather", start+slot.index(pattern)+4, 4, "movw r3, #0x5635")
    output, report = patch.finish()
    report.update(capability=CAPABILITY, guarded_query_and_media_and_role_byte_exact=True)
    return output, report
