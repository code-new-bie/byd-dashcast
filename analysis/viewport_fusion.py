"""1.4.0: explicit OEM ColorBrush leaves, original theme targets, no new shader.

Only our two Content children are toggled. Every drawable has an initial brush;
original state2 drives the same leaf brushes directly, not an inherited nested
state manager on '.'. Lease, window/receiver and vehicle bindings are unchanged.
"""
import math
import struct
from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES, node_head
from navigation_layout import KZB_SHA, words, srt, dictionary, repack, digest
from display_fusion import (PROPERTY, MANAGER, TOP, BOTTOM, TIME, FULL_RULES, CENTER_RULES,
                            encode_node, encode_manager, text, FusionSource)
from background_poc_native import apply_hmi_overlay
from content_viewport import FULL_INNER, CENTER_OUTER, CAPABILITY
from ref2_noauth_native import Patch
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM

STEPS = 24
BRUSH = "kzb://bic_byd_stha/Brushes/Color Brush_BgBottom_"
# Native odometer's parent and glyph child offsets are composed, not absolute.
# Leave an extra bottom margin versus 1.2's y666/.62 placement.
RULES = [(p, k, srt(1393, 648, .70) if p == TIME + "Mileage_Information" else v)
         for p, k, v in FULL_RULES]


def rectangles(center):
    """Disjoint per-side strips; overlaps form a natural alpha union at corners."""
    if center:
        x, y, w, h = CENTER_OUTER
        inner = (x + 24, y + 24, w - 48, h - 48)
        outer = (x, y, w, h)
    else:
        inner, outer = FULL_INNER, (16, 64, 1888, 560)
    x, y, w, h = outer
    a, b, c, d = inner
    # Core areas outside the feathered aperture. Brush alpha is already one.
    rows = [(0, 0, 1920, y, 1), (0, y + h, 1920, 720 - y - h, 1),
            (0, 0, x, 720, 1), (x + w, 0, 1920 - x - w, 720, 1)]
    for i in range(STEPS):
        t = (i + .5) / STEPS
        alpha = (1 + math.cos(math.pi * t)) / 2
        for left, top, width, height in (
                (0, y + (b - y) * i / STEPS, 1920, (b - y) / STEPS),
                (0, b + d + (y + h - b - d) * (STEPS - 1 - i) / STEPS,
                 1920, (y + h - b - d) / STEPS),
                (x + (a - x) * i / STEPS, 0, (a - x) / STEPS, 720),
                (a + c + (x + w - a - c) * (STEPS - 1 - i) / STEPS,
                 0, (x + w - a - c) / STEPS, 720)):
            if width > 0 and height > 0:
                rows.append((left, top, width, height, alpha))
    return rows


def theme_manager(d, strings, props, paths):
    raw = d.read("/State Managers/Bg_RootPage State Manager")
    c = Cursor(raw)
    if [c.word(), c.word(), c.word(), c.word()] != [0, 0, 0, 2]:
        raise ValueError("Original theme header drift")
    c.word()
    if d.dictionary("/$property_dictionary")[c.word()] != "state2":
        raise ValueError("Original theme control drift")
    c.word()
    if c.word() != 3:
        raise ValueError("Original theme state count drift")
    result = bytearray(raw[:c.offset])
    types, names = custom_types(d), d.dictionary("/$property_dictionary")
    for expected in (0, 1, 2):
        name, value, count = c.word(), c.word(), c.word()
        if value != expected or count > 16:
            raise ValueError("Original theme state identity drift")
        begin = c.offset
        for _ in range(count):
            c.word()
            for _ in range(c.word()):
                key = c.word()
                c.variant(types[names[key]]["kind"] if names[key] in types else STANDARD_NODE_TYPES[key])
            if [c.word(), c.word(), c.word()] != [0, 0, 0]:
                raise ValueError("Original theme actions changed")
        result += words(name, value, count + len(paths)) + raw[begin:c.offset]
        for path in paths:
            result += (words(strings.index(path), 1, props.index("Node2D.BackgroundBrush"))
                       + text(BRUSH + ("Light" if value == 1 else "Dark")) + words(0, 0, 0))
    if c.offset != 236:
        raise ValueError("Original theme group end drift")
    return bytes(result) + raw[c.offset:]


def build_viewport_kzb(original, toc):
    if digest(original) != KZB_SHA:
        raise ValueError("Exact original KZB required")
    d = KzbDirectory(original)
    strings, props = d.dictionary("/$strings"), d.dictionary("/$property_dictionary")
    if PROPERTY in props:
        raise ValueError("Overlay already present")
    props.append(PROPERTY)
    paths = []
    shapes = {}
    for center, root in ((False, TOP), (True, BOTTOM)):
        shapes[root] = rectangles(center)
        for i in range(len(shapes[root])):
            paths.append(f"Content/{root}/Edge{i:03d}")
    for value in ([PROPERTY, TOP, BOTTOM, "DCIdle", "DCFull", "DCCenter",
                   "kzb://bic_byd_stha" + MANAGER] + paths
                  + [f"Edge{i:03d}" for i in range(max(map(len, shapes.values()))) ]
                  + [p for p, _, _ in RULES + CENTER_RULES]):
        if value not in strings:
            strings.append(value)
    class Extended:
        def dictionary(self, name):
            return props if name == "/$property_dictionary" else d.dictionary(name)
    nodes = b""
    for root, rows in shapes.items():
        children = [encode_node(Extended(), strings, f"Edge{i:03d}", [
            ("Node.Width", struct.pack("<f", w)), ("Node.Height", struct.pack("<f", h)),
            ("Node.Opacity", struct.pack("<f", alpha)),
            ("Node2D.RenderTransformation", srt(x, y)),
            ("Node2D.BackgroundBrush", text(BRUSH + "Dark"))])
                    for i, (x, y, w, h, alpha) in enumerate(rows)]
        nodes += encode_node(Extended(), strings, root, [
            ("Node.Width", struct.pack("<f", 1920)), ("Node.Height", struct.pack("<f", 720)),
            ("Node.Opacity", struct.pack("<f", 0))], children)
    startup = d.read("/StartupPrefab")
    owner = node_head(d, startup, 405)
    if owner["end_properties"] != 476 or struct.unpack_from("<I", startup, 476)[0] != 13:
        raise ValueError("Original Content drift")
    startup = startup[:476] + words(15) + startup[480:1855] + nodes + startup[1855:]
    c = Cursor(startup, 417)
    count = c.word()
    for _ in range(count):
        key = c.word()
        c.variant(custom_types(d).get(props[key], {}).get("kind", STANDARD_NODE_TYPES.get(key)))
    startup = (startup[:417] + words(count + 2) + startup[421:c.offset]
               + words(props.index("Node.StateManager")) + text("kzb://bic_byd_stha" + MANAGER)
               + words(props.index(PROPERTY), 0) + startup[c.offset:])
    types = d.read("/$property_types")
    types = words(struct.unpack_from("<I", types)[0] + 1) + types[4:] + words(strings.index(PROPERTY), 1, 0, 1, 0)
    opacity = lambda v: struct.pack("<f", v)
    states = [("DCIdle", 0, []),
              ("DCFull", 1, RULES + [(TOP, "Node.Opacity", opacity(1)), (BOTTOM, "Node.Opacity", opacity(0))]),
              ("DCCenter", 2, CENTER_RULES + [(TOP, "Node.Opacity", opacity(0)), (BOTTOM, "Node.Opacity", opacity(1))])]
    changes = {"/$strings": dictionary(strings), "/$property_dictionary": dictionary(props),
               "/$property_types": types, "/StartupPrefab": startup,
               "/State Managers/Bg_RootPage State Manager": theme_manager(d, strings, props, paths)}
    addition = {MANAGER: (d.records["/State Managers/NaviShowContent"]["class_id"],
                           encode_manager(strings, props, PROPERTY, states))}
    output = repack(d, changes, addition)
    reread = KzbDirectory(output)
    for name in d.records:
        if name not in changes and d.read(name) != reread.read(name):
            raise ValueError("Unrelated OEM resource drift: " + name)
    return output, dict(input_sha256=digest(original), output_sha256=digest(output),
        modified_resources=list(changes), added_resources=list(addition), vector_rectangles=sum(map(len, shapes.values())),
        explicit_initial_brush=True, custom_materials=0, nested_theme_managers=0, theme_direct_targets=len(paths),
        physical_layout_tested=False)


class ViewportSource:
    def read_text(self, encoding):
        base = FusionSource().read_text(encoding)
        # Same size bounded code cave, no unknown padding or new ABI.
        edits = {
            "movw r0, #0x4e32": "movw r0, #0x5633",
            "ldr r0, [sp, #8]\nbl nav_band_matches": "movs r7, #1\nldr r0, [sp, #8]\nbl nav_band_matches",
            "ldr r0, [sp, #12]\nbl nav_band_matches": "movs r7, #2\nldr r0, [sp, #12]\nbl nav_band_matches",
            "cmp r6, #0\nit eq\nmoveq r1, #0": "cmp r6, r7\nit ne\nmovne r1, #0",
        }
        for old, new in edits.items():
            if base.count(old) != 1:
                raise ValueError("Viewport bridge precondition: " + old)
            base = base.replace(old, new)
        return base


def build_viewport_hmi(original):
    output, report = apply_hmi_overlay(original, source_path=ViewportSource())
    report.update(capability=CAPABILITY, exclusive_full_center_readback=True,
                  source_sha256=digest(ViewportSource().read_text("utf-8").encode()),
                  fullscreen_enabled=True, physical_layout_tested=False)
    return output, report


def build_viewport_receiver(previous):
    """Only the paired-HMI capability comparison changes; media/USB code is exact."""
    patch = Patch(previous, "8b490f2fd8b6b5b8f97f93e2f085122838e12c67670e60fd7770cb455ac63aa6")
    address, capacity = 0x108898, 3232
    offset = patch.offset(address, capacity)
    assembler = Ks(KS_ARCH_ARM, KS_MODE_ARM)
    pattern = bytes(assembler.asm("ldr r2, [r6, #124]; movw r3, #0x4e32; movt r3, #0x5257")[0])
    slot = previous[offset:offset + capacity]
    if slot.count(pattern) != 1:
        raise ValueError("Exact paired-capability instruction sequence required")
    instruction = address + slot.index(pattern) + 4
    patch.arm("paired_viewport_capability_RWV3", instruction, 4, "movw r3, #0x5633")
    result, report = patch.finish()
    report.update(capability=CAPABILITY, media_window_and_cleanup_byte_exact=True)
    return result, report
