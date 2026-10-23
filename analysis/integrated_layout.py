"""RWV4: reversible native ADAS presentation and protected OEM-themed information.

All inputs are exact-version bound. This modifies presentation, never vehicle
state, warnings' visibility, warning state managers or native data bindings.
Idle removes our modifiers rather than applying a stale snapshot of vehicle data.
"""
import math
import struct

from analyze_kzb_hmi import KzbDirectory, Cursor, node_head
from background_poc_native import apply_hmi_overlay
from display_fusion import encode_manager, encode_node, text, PROPERTY, TOP, BOTTOM
from navigation_layout import words, srt, dictionary, repack, digest
from viewport_fusion import build_viewport_kzb, ViewportSource, RULES, BRUSH, build_viewport_receiver
from ref2_noauth_native import Patch

CAPABILITY = 0x52575634  # RWV4. An RWV3 APK must not activate these new bounds.
FULL_INNER = (96, 144, 1728, 384)
FULL_SOURCE = (1728, 384)
CENTER_OUTER = (576, 128, 768, 432)
CENTER_INNER = (600, 152, 720, 384)
CENTER_SOURCE = (1200, 640)
ADAS_RESOURCE = "/Prefabs/Prefab_ADAS_2D_ACCSys"
SMALL_RESOURCE = "/Prefabs/Prefab_ADAS_2D_ACCSys_Small"
ADAS_MANAGER = "/State Managers/DCADASPresentation"
SMALL_MANAGER = "/State Managers/DCSmallADASPresentation"
ROAD_MANAGER = "/State Managers/DCDecorativeRoad"
PANEL_THEME = "/State Managers/DCADASPanelTheme"
SAFETY_MANAGER = "/State Managers/DCSafetyRail"
PANEL = "DCADASPanel"
FRAME_TOP, FRAME_BOTTOM = "DVTop", "DVBottom"
F = lambda value: struct.pack("<f", value)


def rectangles(center):
    """Native four-side cosine feathers; native text is entirely on opaque cores.

    Edges carry OEM blue-gray rather than black. Corner overlap is an alpha
    union; opaque cores are reached before the instrument's information rows.
    """
    inner = CENTER_INNER if center else FULL_INNER
    # Power/speed glyphs begin at y548, before range/OK at y579. Reach the
    # opaque core at y544, not merely before the lower range-information row.
    outer = CENTER_OUTER if center else (16, 80, 1888, 464)
    x, y, w, h = outer
    a, b, c, d = inner
    rows = [(0, 0, 1920, y, 1), (0, y + h, 1920, 720-y-h, 1),
            (0, 0, x, 720, 1), (x+w, 0, 1920-x-w, 720, 1)]
    for i in range(24):
        alpha = (1 + math.cos(math.pi * (i+.5)/24)) / 2
        for l, t, width, height in (
            (0, y+(b-y)*i/24, 1920, (b-y)/24),
            (0, b+d+(y+h-b-d)*(23-i)/24, 1920, (y+h-b-d)/24),
            (x+(a-x)*i/24, 0, (a-x)/24, 720),
            (a+c+(x+w-a-c)*(23-i)/24, 0, (x+w-a-c)/24, 720)):
            rows.append((l, t, width, height, alpha))
    return rows


def encoded_inner(center):
    outer = CENTER_OUTER if center else (0, 0, 1920, 720)
    x, y, w, h = outer
    a, b, c, d = CENTER_INNER if center else FULL_INNER
    return ((a-x)*1920/w, (b-y)*720/h, c*1920/w, d*720/h)


def add_manager(directory, raw, offset, expected_name, manager):
    """Add one property to a proven node head, preserving every existing byte."""
    head = node_head(directory, raw, offset)
    if head["name"] != expected_name or "Node.StateManager" in head["properties"]:
        raise ValueError("Node manager/identity drift: " + expected_name)
    cursor = Cursor(raw, offset+12)
    count = cursor.word()
    # end_properties includes bindings. Insert BEFORE their count, not after it.
    from analyze_kzb_hmi import custom_types, STANDARD_NODE_TYPES
    names, types = directory.dictionary("/$property_dictionary"), custom_types(directory)
    for _ in range(count):
        key = cursor.word()
        cursor.variant(types[names[key]]["kind"] if names[key] in types else STANDARD_NODE_TYPES[key])
    insertion = words(names.index("Node.StateManager")) + text("kzb://bic_byd_stha" + manager)
    result = raw[:offset+12] + words(count+1) + raw[offset+16:cursor.offset] + insertion + raw[cursor.offset:]
    return result, head, len(insertion)


def adas_rules():
    # Static 300x310 perspective road is decoration, not an alarm or lane signal.
    # Signal images retain every OEM state/binding. Only their presentation moves.
    return [
        ("Lane", "Node.Opacity", F(0)),
        ("ACCSys", "Node2D.RenderTransformation", srt(948, -32, .65)),
        ("LaneDeparture/RightLane", "Node2D.RenderTransformation", srt(1600, 132, .65)),
        ("LaneDeparture/LeftLane", "Node2D.RenderTransformation", srt(1474, 132, .65)),
        ("LaneDeparture/Car", "Node2D.RenderTransformation", srt(1527, 228, .65)),
        ("LaneDeparture/LDW", "Node2D.RenderTransformation", srt(1524, 311, .65)),
        (PANEL, "Node.Opacity", F(1)),
    ]


def build_integrated_kzb(original, toc):
    base, prior = build_viewport_kzb(original, toc, shape_factory=rectangles)
    d = KzbDirectory(base)
    strings, props = d.dictionary("/$strings"), d.dictionary("/$property_dictionary")
    # Names and all state target paths use dictionary IDs. Same-length private
    # RWV4 markers make an old KZB fail HMI readback even if files were mixed.
    def frame_marker(value):
        if value == TOP:
            return FRAME_TOP
        if value == BOTTOM:
            return FRAME_BOTTOM
        return value.replace("Content/"+TOP+"/", "Content/"+FRAME_TOP+"/").replace(
            "Content/"+BOTTOM+"/", "Content/"+FRAME_BOTTOM+"/")
    strings = [frame_marker(value) for value in strings]
    rules = adas_rules()
    values = ["DCDark", "DCLight", "DCAlternateDark", ".", PANEL,
              "kzb://bic_byd_stha"+PANEL_THEME] + [p for p, _, _ in rules]
    values += [f"CueEdge{i:02d}" for i in range(10)]
    for value in values:
        if value not in strings:
            strings.append(value)
    class Extended:
        def dictionary(self, name):
            return props if name == "/$property_dictionary" else d.dictionary(name)
    layers, previous_alpha = [], 0.
    for i in range(10):
        cumulative = math.sin(math.pi/2*(i+1)/10)**2
        alpha = (cumulative-previous_alpha)/(1-previous_alpha)
        previous_alpha = cumulative
        layers.append(encode_node(Extended(), strings, f"CueEdge{i:02d}", [
            ("Node.Width", F(284-i*4)), ("Node.Height", F(238-i*4)),
            ("Node2D.RenderTransformation", srt(i*2, i*2)),
            ("Node.Opacity", F(alpha)), ("Node2D.BackgroundBrush", text(BRUSH+"Dark"))]))
    panel = encode_node(Extended(), strings, PANEL, [
        ("Node.Width", F(284)), ("Node.Height", F(238)), ("Node.Opacity", F(0)),
        ("Node2D.RenderTransformation", srt(1432, 106)),
        ("Node.StateManager", text("kzb://bic_byd_stha"+PANEL_THEME))], layers)
    resource = d.read(ADAS_RESOURCE)
    full, head, delta = add_manager(d, resource, 176, "content", ADAS_MANAGER)
    child_count = head["end_properties"]+delta
    if struct.unpack_from("<I", full, child_count)[0] != 3:
        raise ValueError("Full ADAS content topology drift")
    full = full[:child_count]+words(4)+panel+full[child_count+4:]
    # Preserve root, paths and native theme; append a mode group to a private copy.
    small_raw = d.read(SMALL_RESOURCE)
    small_head = node_head(d, small_raw, 151)
    expected_theme = "kzb://bic_byd_stha/State Managers/01_ThemeLightMode_EffectOnRoot"
    if small_head["properties"].get("Node.StateManager") != expected_theme:
        raise ValueError("Small's original theme identity drift")
    old_link = text(expected_theme)
    if small_raw[151:small_head["end_properties"]].count(old_link) != 1:
        raise ValueError("Small's content manager must be unique")
    part = small_raw[151:small_head["end_properties"]].replace(old_link, text("kzb://bic_byd_stha"+SMALL_MANAGER))
    small = small_raw[:151]+part+small_raw[small_head["end_properties"]:]
    # Small's original rendered origin (1250,430), scale .93. Move to the cue slot
    # without changing the root's theme manager, children, bindings or state images.
    small_rules = [(".", "Node2D.RenderTransformation", srt(1450, 156, 1))]
    # WarnText's visible road image is independent of CarDoorAndSafe. Its own
    # manager applies opacity only to that decorative image, never the alert root.
    warn_resource = "/Prefabs/Prefab_WarnText"
    warn = d.read(warn_resource)
    warn, warn_head, _ = add_manager(d, warn, 224, "Road", ROAD_MANAGER)
    if warn_head["properties"].get("Node.Visible") != 0:
        raise ValueError("Expected original hidden-by-default warning road")
    cls = d.records["/State Managers/NaviShowContent"]["class_id"]
    states = lambda active: [("DCIdle", 0, []), ("DCFull", 1, active), ("DCCenter", 2, active)]
    original_theme = d.read(expected_theme.removeprefix("kzb://bic_byd_stha"))
    if struct.unpack_from("<4I", original_theme) != (0, 0, 0, 1) or original_theme[-4:] != words(0):
        raise ValueError("Simple native theme group shape drift")
    mode_group = encode_manager(strings, props, PROPERTY, states(small_rules))
    combined = original_theme[:12]+words(2)+original_theme[16:]+mode_group[16:]
    additions = {
        ADAS_MANAGER: (cls, encode_manager(strings, props, PROPERTY, states(rules))),
        SMALL_MANAGER: (cls, combined),
        ROAD_MANAGER: (cls, encode_manager(strings, props, PROPERTY, states([(".", "Node.Opacity", F(0))]))),
        PANEL_THEME: (cls, encode_manager(strings, props, "IntThemeLightMode", [
            (label, value, [(f"CueEdge{i:02d}", "Node2D.BackgroundBrush", text(BRUSH+theme)) for i in range(10)])
            for label, value, theme in (("DCDark", 0, "Dark"), ("DCLight", 1, "Light"), ("DCAlternateDark", 2, "Dark"))])),
        SAFETY_MANAGER: (cls, encode_manager(strings, props, PROPERTY, [
            ("DCIdle", 0, []), ("DCFull", 1, [(".", "Node2D.RenderTransformation", srt(360, 584))]),
            ("DCCenter", 2, [])])),
    }
    changes = {"/$strings": dictionary(strings), ADAS_RESOURCE: full,
               SMALL_RESOURCE: small, warn_resource: warn}
    # The native belt lamp remains native-sized, with its original visibility,
    # data and update path. Only full mode moves it off the navigation turn card.
    seat_heads = {}
    for style in ("Classic", "Fashion", "ACC"):
        name = "/Prefabs/Prefab_Indicator_"+style
        raw = d.read(name)
        pattern, candidates, start = words(strings.index("SeatBelt")), [], 0
        while True:
            offset = raw.find(pattern, start)
            if offset < 0:
                break
            start = offset+1
            try:
                candidate = node_head(d, raw, offset)
                if candidate["name"] == "SeatBelt" and candidate["node_class"] == "Kanzi.Image2D":
                    candidates.append(candidate)
            except (ValueError, IndexError, KeyError, struct.error, UnicodeError):
                continue
        if len(candidates) != 1:
            raise ValueError("Unique native belt lamp required in "+style)
        head = candidates[0]
        if head["properties"].get("Node.Width") != 40 or head["properties"].get("Node.Height") != 48:
            raise ValueError("Native belt lamp dimensions drift")
        changes[name], _, _ = add_manager(d, raw, head["offset"], "SeatBelt", SAFETY_MANAGER)
        seat_heads[name] = head
    out = repack(d, changes, additions)
    reread = KzbDirectory(out)
    for name in d.records:
        if name not in changes and d.read(name) != reread.read(name):
            raise ValueError("Unrelated resource changed: "+name)
    return out, dict(input_sha256=digest(original), output_sha256=digest(out),
        base=prior, modified_resources=list(changes), added_resources=list(additions),
        original_alert_state_resources_byte_exact=True, original_takeover_text_and_font_unchanged=True,
        seat_belt_heads=seat_heads, native_3d_scene_modified=False, real_kanzi_executed=False)


class IntegratedSource:
    def read_text(self, encoding):
        base = ViewportSource().read_text(encoding)
        if base.count("movw r0, #0x5633") != 1:
            raise ValueError("Exact RWV3 bridge required")
        for old, new in (("movw r0, #0x5633", "movw r0, #0x5634"),
                         ('.asciz "DCTop"', '.asciz "DVTop"'),
                         ('.asciz "DCBottom"', '.asciz "DVBottom"')):
            if base.count(old) != 1:
                raise ValueError("Exact native marker required: "+old)
            base = base.replace(old, new)
        return base


def build_integrated_hmi(original):
    output, report = apply_hmi_overlay(original, source_path=IntegratedSource())
    report.update(capability=CAPABILITY, source_sha256=digest(IntegratedSource().read_text("utf-8").encode()))
    return output, report


def build_integrated_receiver(previous):
    old, _ = build_viewport_receiver(previous)
    patch = Patch(old, "2f14db977b7adee17be848b3d119b3cb519729771196a23d0c71cef50e86b16e")
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM
    asm = Ks(KS_ARCH_ARM, KS_MODE_ARM)
    start, size = 0x108898, 3232
    offset = patch.offset(start, size)
    pattern = bytes(asm.asm("ldr r2, [r6, #124]; movw r3, #0x5633; movt r3, #0x5257")[0])
    slot = old[offset:offset+size]
    if slot.count(pattern) != 1:
        raise ValueError("Exact RWV3 receiver required")
    patch.arm("paired_viewport_RWV4", start+slot.index(pattern)+4, 4, "movw r3, #0x5634")
    output, report = patch.finish()
    report.update(capability=CAPABILITY, media_and_role_code_byte_exact=True)
    return output, report
