"""1.1.1 local controller/StateManager plus native band-effect readback.

Build from the pinned original only; retain old 1.1.0 generator and reports.
No new Screen layer, receiver/USB/OS/MCU changes, or warning-parent suppression.
"""
import struct
from navigation_layout import (ROOT, KZB_SHA, PROPERTY, LAYOUTS, words, srt,
                               state_manager, dictionary, repack, digest)
from analyze_kzb_hmi import KzbDirectory, node_head, Cursor, custom_types, STANDARD_NODE_TYPES
from background_poc_native import apply_hmi_overlay
from unlimited_native import HMI_SOURCE

SOURCE = ROOT / "phase1/qnx/native-hmi/navigation-layout-local.thumb"
MANAGER = "/State Managers/Dashcast_LocalLayout"
NAV_STATUS = 0x4e4c4300
RULES = [(owner + ("" if path == "." else "/" + path), key, value)
         for owner, _, rules in LAYOUTS for path, key, value in rules]
RULES += [(name, "Node.Opacity", struct.pack("<f", 1.0))
          for name in ("DashcastTopBand", "DashcastBottomBand")]


class LocalNavigationSource:
    def read_text(self, encoding):
        base = HMI_SOURCE.read_text(encoding)
        if base.count("bg_done:\n") != 1:
            raise ValueError("Unlimited bridge return changed")
        return base.replace("bg_done:\n", "bg_done:\nbl nav_tick\n") + "\n" + SOURCE.read_text(encoding)


def build_navigation_fix_hmi(original):
    binary, report = apply_hmi_overlay(original, source_path=LocalNavigationSource())
    report.update(local_controller_owner="RootPage/Content", status_word=29,
                  native_band_effect_readback=True, status_ready=NAV_STATUS,
                  status_active=NAV_STATUS | 1, status_pending=NAV_STATUS | 2,
                  extension_source_sha256=digest(SOURCE.read_bytes()), physical_layout_tested=False)
    return binary, report


def band_node(directory, strings, name, y, height):
    props = directory.dictionary("/$property_dictionary")
    classes = directory.dictionary("/$metaclass_dictionary")
    text = lambda value: words(len(value.encode())) + value.encode()
    values = [("Node.Opacity", struct.pack("<f", 0.0)),
              ("Node.Width", struct.pack("<f", 1920.0)), ("Node.Height", struct.pack("<f", height)),
              ("Node2D.RenderTransformation", srt(0, y)),
              ("Node2D.BackgroundBrush", text("kzb://bic_byd_stha/Brushes/Color Brush_Black"))]
    empty = strings.index("")
    return (words(strings.index(name), empty, classes.index("Kanzi.EmptyNode2D"), len(values))
            + b"".join(words(props.index(key)) + value for key, value in values)
            + words(0, 0, 0, 0, empty))


def build_navigation_fix_kzb(original, toc):
    if digest(original) != KZB_SHA:
        raise ValueError("Not the exact original KZB; no reapplication to a patched package")
    d = KzbDirectory(original)
    strings, props = d.dictionary("/$strings"), d.dictionary("/$property_dictionary")
    if PROPERTY in props:
        raise ValueError("Layout already registered")
    # Reuse the independently allowlisted old relative paths, but resolve ALL
    # targets from their actual common owner Content. The owner gets a local 0.
    from navigation_layout import build_navigation_kzb
    build_navigation_kzb(original, toc)  # Original input/path preconditions, not used as output.
    props.append(PROPERTY)
    for text in [PROPERTY, "DashcastActive", "DashcastTopBand", "DashcastBottomBand",
                 "kzb://bic_byd_stha" + MANAGER] + [path for path, _, _ in RULES]:
        if text not in strings:
            strings.append(text)
    raw = d.read("/StartupPrefab")
    content = node_head(d, raw, 405)
    if content["name"] != "Content" or content["end_properties"] != 476 or "Node.StateManager" in content["properties"]:
        raise ValueError("Content owner/state-manager precondition changed")
    if struct.unpack_from("<I", raw, 476)[0] != 13:
        raise ValueError("Content children changed")
    raw = raw[:476] + words(15) + raw[480:]
    bands = band_node(d, strings, "DashcastTopBand", 0, 118)
    bands += band_node(d, strings, "DashcastBottomBand", 550, 170)
    raw = raw[:1855] + bands + raw[1855:]
    url = ("kzb://bic_byd_stha" + MANAGER).encode()
    cursor = Cursor(raw, 417)
    count = cursor.word()
    kinds = custom_types(d)
    original_props = d.dictionary("/$property_dictionary")
    for _ in range(count):
        key = cursor.word()
        kind = kinds[original_props[key]]["kind"] if original_props[key] in kinds else STANDARD_NODE_TYPES[key]
        cursor.variant(kind)
    raw = (raw[:417] + words(count + 2) + raw[421:cursor.offset]
           + words(props.index("Node.StateManager"), len(url)) + url
           + words(props.index(PROPERTY), 0) + raw[cursor.offset:])
    custom = d.read("/$property_types")
    custom = words(struct.unpack_from("<I", custom)[0] + 1) + custom[4:]
    custom += words(strings.index(PROPERTY), 1, 0, 1, 0)
    changes = {"/$strings": dictionary(strings), "/$property_dictionary": dictionary(props),
               "/$property_types": custom, "/StartupPrefab": raw}
    additions = {MANAGER: (d.records["/State Managers/NaviShowContent"]["class_id"],
                           state_manager(strings, props, RULES))}
    result = repack(d, changes, additions)
    check = KzbDirectory(result)
    owner = node_head(check, check.read("/StartupPrefab"), 405)
    if owner["properties"].get(PROPERTY) != 0 or owner["properties"]["Node.StateManager"] != url.decode():
        raise ValueError("Local control storage or StateManager association missing")
    for name in d.records:
        if name not in changes and d.read(name) != check.read(name):
            raise ValueError("Original unrelated resource changed: " + name)
    return result, dict(input_sha256=digest(original), output_sha256=digest(result),
                        modified_resources=list(changes), added_resources=list(additions),
                        unchanged_resources=len(d.records) - len(changes),
                        local_controller_owner="RootPage/Content", controlled_paths=[p for p, _, _ in RULES],
                        native_band_effect_readback=True, physical_layout_tested=False,
                        vehicle_alert_parent_hidden=False)
