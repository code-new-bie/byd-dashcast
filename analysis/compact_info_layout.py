"""1.7.1: compact full-mode native speed/unit/mileage, no data or lifetime change.

Only the owned full-mode modifiers differ from RWV4/1.7.0. OEM five mileage
modes, fonts, bindings, units, images and center/idle states remain byte exact.
The physical panel contour is not a rectangular 1920x720 visibility guarantee.
"""
import struct

from analyze_kzb_hmi import KzbDirectory, node_head
from navigation_layout import srt, dictionary, repack, digest
from navigation_layout_refine import PREFIX, TIME
from integrated_layout import build_integrated_kzb
from integrated_layout import FRAME_TOP, FRAME_BOTTOM
from display_fusion import PROPERTY, MANAGER, CENTER_RULES, encode_manager
from viewport_fusion import RULES as LEGACY_RULES

TRANSFORM = "Node2D.RenderTransformation"
ALIGN = "Node.HorizontalAlignment"
SPEED = PREFIX + "Speed/SpeedShow"
DIGITS = SPEED + "/SpeedShowText"
STACK = DIGITS + "/SpeedShow"
UNIT = SPEED + "/Km_Per_Hour"
MILEAGE = TIME + "Mileage_Information"
# Keep original 93x98 digit images at .72 scale. Right-align the dynamic
# 1/2/3-digit stack to the same edge; a fixed unit then has a constant 12px gap.
SPEED_RIGHT = 1620
MILEAGE_BOX = (1410, 628, 415 * .70, 60 * .70)
SAFE_BOX = (1380, 544, 360, 128)  # right 1740, bottom 672, not the glass edge
OVERRIDES = {
    (DIGITS, TRANSFORM): srt(1364, 335, .72),
    (UNIT, TRANSFORM): srt(1592, 367, .85714286),
    (MILEAGE, TRANSFORM): srt(1410, 628, .70),
}
RULES = [(p, k, OVERRIDES.get((p, k), v)) for p, k, v in LEGACY_RULES]
RULES += [(DIGITS, ALIGN, struct.pack("<i", 0)),
          (STACK, ALIGN, struct.pack("<i", 1)),
          (UNIT, ALIGN, struct.pack("<i", 0))]


def build_compact_info_kzb(original, toc):
    d = KzbDirectory(original)
    raw = d.read("/Prefabs/Prefab_Cluster_Power_Speed")
    digit, stack, unit = (node_head(d, raw, n) for n in (25199, 25271, 24859))
    footer = node_head(d, d.read("/Prefabs/Prefab_TopBottom"), 2802)
    expected = ((digit, "SpeedShowText", {"Node.Width": 300., "Node.Height": 100., ALIGN: 2}),
                (stack, "SpeedShow", {ALIGN: 2, "StackLayoutConcept.Direction": 0}),
                (unit, "Km_Per_Hour", {"Node.Width": 100., "TextBlockConcept.FontSize": 28.}),
                (footer, "Mileage_Information", {"Node.Width": 415., "Node.Height": 60.}))
    for head, name, properties in expected:
        if head["name"] != name or any(head["properties"].get(k) != v for k, v in properties.items()):
            raise ValueError("Compact information original geometry drift: " + name)
    for path in (DIGITS, STACK, UNIT):
        suffix = path.removeprefix(PREFIX)
        if "Prefabs/Prefab_Cluster_Power_Speed/Prefab_Cluster_Power_Speed/content/" + suffix not in toc:
            raise ValueError("Unproven compact native target: " + path)
    base, report = build_integrated_kzb(original, toc)
    current = KzbDirectory(base)
    strings, props = current.dictionary("/$strings"), current.dictionary("/$property_dictionary")
    # Append after the complete 1.7 dictionary. Inserting earlier would renumber
    # otherwise unchanged ADAS/panel references; retain their exact saved bytes.
    for path, _, _ in RULES:
        if path not in strings:
            strings.append(path)
    f = lambda v: struct.pack("<f", v)
    states = [("DCIdle", 0, []), ("DCFull", 1, RULES + [
        (FRAME_TOP, "Node.Opacity", f(1)), (FRAME_BOTTOM, "Node.Opacity", f(0))]),
        ("DCCenter", 2, CENTER_RULES + [
            (FRAME_TOP, "Node.Opacity", f(0)), (FRAME_BOTTOM, "Node.Opacity", f(1))])]
    output = repack(current, {"/$strings": dictionary(strings),
                            MANAGER: encode_manager(strings, props, PROPERTY, states)}, {})
    reread = KzbDirectory(output)
    for name in current.records:
        if name not in {"/$strings", MANAGER} and current.read(name) != reread.read(name):
            raise ValueError("Unrelated RWV4 resource drift: " + name)
    report.update(output_sha256=digest(output), rwv4_base_sha256=digest(base),
                  changed_resources_from_170=["/$strings", MANAGER])
    report.update(layout_version="1.7.1", full_mode_only=True,
                  native_speed_unit_beside_digits=True, dynamic_digits_right_aligned=True,
                  mileage_box=list(MILEAGE_BOX), design_safe_box=list(SAFE_BOX),
                  all_five_mileage_modes_and_original_fonts_byte_exact=True,
                  center_and_idle_modifiers_unchanged=True, physical_panel_calibrated=False)
    return output, report
