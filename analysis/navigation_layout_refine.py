"""1.1.2 geometry/feathering on the field-working local controller.

Only KZB changes: retain 1.1.1 HMI/receiver/role/MCU/OS bytes and state lifetime.
Native vector rectangles form the fade; no generated bitmap or new shader/ABI.
"""
import math
import struct
from navigation_layout import words, srt, state_manager, dictionary, repack, digest, CLUSTER
from navigation_layout_fix import MANAGER, PROPERTY, RULES as PREVIOUS_RULES, build_navigation_fix_kzb
from analyze_kzb_hmi import KzbDirectory, node_head

FEATHER_STEPS = 32
MAX_DARKENING = 0.94
TOP_CORE = 96
FADE_HEIGHT = 64
BOTTOM_START = 510
TOP_HEIGHT = TOP_CORE + FADE_HEIGHT
BOTTOM_HEIGHT = 720 - BOTTOM_START
PREFIX = "Cluster/" + CLUSTER
TIME = "Top_Bottom/Prefab_TopBottom/content/"

# Relative offsets are composed with original child transforms, not mistaken
# for absolute glyph coordinates. Original top-row placement already has margin.
OVERRIDES = {
    (PREFIX + "Power/PowerShow", "Node2D.RenderTransformation"): srt(-110, 294),
    (PREFIX + "Power/RPMShow", "Node2D.RenderTransformation"): srt(-110, 294),
    (PREFIX + "Speed/SpeedShow", "Node2D.RenderTransformation"): srt(40, 243),
    (TIME + "Mileage_Information", "Node2D.RenderTransformation"): srt(1075, 662, 0.70),
}
REMOVED = {("Gear", "Node2D.RenderTransformation"), (TIME + "Time", "Node2D.RenderTransformation")}
RULES = [(path, key, OVERRIDES.get((path, key), value)) for path, key, value in PREVIOUS_RULES
         if (path, key) not in REMOVED]
RULES += [
    (PREFIX + "Power/PowerShow/PowerShowText", "Node2D.RenderTransformation", srt(-533, 287, 0.74)),
    (PREFIX + "Power/PowerShow/Power_Unit", "Node2D.RenderTransformation", srt(324, 362)),
    (PREFIX + "Speed/SpeedShow/SpeedShowText", "Node2D.RenderTransformation", srt(596, 335, 0.72)),
    (PREFIX + "Speed/SpeedShow/Km_Per_Hour", "Node2D.RenderTransformation", srt(1484, 410, 0.85714286)),
    (TIME + "Line", "Node.Opacity", struct.pack("<f", 0.0)),
]


def fade_rectangles(top):
    """Non-overlapping native rectangles: (local y, height, alpha, unique name)."""
    width = FADE_HEIGHT / FEATHER_STEPS
    output = [(0, TOP_CORE, MAX_DARKENING, "Core")] if top else []
    for index in range(FEATHER_STEPS):
        t = (index + 0.5) / FEATHER_STEPS
        alpha = MAX_DARKENING * (1 - math.cos(math.pi * t)) / 2
        if top:
            alpha = MAX_DARKENING - alpha
        y = (TOP_CORE if top else 0) + index * width
        output.append((y, width, alpha, "Fade%02d" % index))
    if not top:
        output.append((FADE_HEIGHT, BOTTOM_HEIGHT - FADE_HEIGHT, MAX_DARKENING, "Core"))
    return output


def empty_node(directory, strings, name, properties, children=()):
    props = directory.dictionary("/$property_dictionary")
    classes = directory.dictionary("/$metaclass_dictionary")
    empty = strings.index("")
    encoded = words(strings.index(name), empty, classes.index("Kanzi.EmptyNode2D"), len(properties))
    encoded += b"".join(words(props.index(key)) + value for key, value in properties)
    return encoded + words(0, len(children)) + b"".join(children) + words(0, 0, empty)


def feather_band(directory, strings, name, y, height, top):
    brush = "kzb://bic_byd_stha/Brushes/Color Brush_Black".encode()
    children = []
    for local_y, row_height, opacity, suffix in fade_rectangles(top):
        children.append(empty_node(directory, strings, name + suffix, [
            ("Node.Width", struct.pack("<f", 1920)),
            ("Node.Height", struct.pack("<f", row_height)),
            ("Node.Opacity", struct.pack("<f", opacity)),
            ("Node2D.RenderTransformation", srt(0, local_y)),
            ("Node2D.BackgroundBrush", words(len(brush)) + brush),
        ]))
    return empty_node(directory, strings, name, [
        ("Node.Opacity", struct.pack("<f", 0)),
        ("Node.Width", struct.pack("<f", 1920)),
        ("Node.Height", struct.pack("<f", height)),
        ("Node2D.RenderTransformation", srt(0, y)),
    ], children)


def assert_target_paths(toc):
    for path, _, _ in RULES:
        if path.startswith(PREFIX):
            candidate = "Prefabs/Prefab_Cluster_Power_Speed/Prefab_Cluster_Power_Speed/content/" + path[len(PREFIX):]
        elif path.startswith(TIME):
            candidate = "Prefabs/Prefab_TopBottom/Prefab_TopBottom/content/" + path[len(TIME):]
        elif path.startswith("Dashcast"):
            continue
        else:
            candidate = "Screens/Screen/RootPage/Content/" + path
        if candidate not in toc:
            raise ValueError("Unproven refined node path: " + candidate)


def build_navigation_refined_kzb(original, toc):
    assert_target_paths(toc)
    previous, _ = build_navigation_fix_kzb(original, toc)
    base = KzbDirectory(previous)
    strings, props = base.dictionary("/$strings"), base.dictionary("/$property_dictionary")
    for path, _, _ in RULES:
        if path not in strings:
            strings.append(path)
    for name, top in (("DashcastTopBand", True), ("DashcastBottomBand", False)):
        strings.extend(name + suffix for _, _, _, suffix in fade_rectangles(top))
    if len(set(strings)) != len(strings):
        raise ValueError("New native fade names must be unique")
    raw = base.read("/StartupPrefab")
    owner = node_head(base, raw, 405)
    delta = owner["end_properties"] - 476
    begin = 1855 + delta
    cursor = begin
    for expected in ("DashcastTopBand", "DashcastBottomBand"):
        old = node_head(base, raw, cursor)
        if old["name"] != expected:
            raise ValueError("Previous native bands changed")
        cursor = old["end_properties"] + 16
    bands = feather_band(base, strings, "DashcastTopBand", 0, TOP_HEIGHT, True)
    bands += feather_band(base, strings, "DashcastBottomBand", BOTTOM_START, BOTTOM_HEIGHT, False)
    raw = raw[:begin] + bands + raw[cursor:]
    result = repack(base, {"/$strings": dictionary(strings), "/StartupPrefab": raw,
                           MANAGER: state_manager(strings, props, RULES)}, {})
    a, b = KzbDirectory(original), KzbDirectory(result)
    changed = {"/$strings", "/$property_dictionary", "/$property_types", "/StartupPrefab"}
    for name in a.records:
        if name not in changed and a.read(name) != b.read(name):
            raise ValueError("Unrelated OEM resource drift")
    return result, dict(input_sha256=digest(original), output_sha256=digest(result),
        modified_resources=sorted(changed), added_resources=[MANAGER], unchanged_resources=len(a.records) - len(changed),
        hmi_same_as_1_1_1=True, control_owner="RootPage/Content", native_readback_anchors_unchanged=True,
        vector_rectangles=66, feather_steps=FEATHER_STEPS, max_darkening=MAX_DARKENING,
        top_core=TOP_CORE, top_height=TOP_HEIGHT, bottom_start=BOTTOM_START, bottom_height=BOTTOM_HEIGHT,
        rules=len(RULES), top_time_and_gear_keep_original_position=True,
        warning_and_vehicle_bindings_changed=False, physical_layout_tested=False)
