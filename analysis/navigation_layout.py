"""Exact native navigation layout overlay; no SDK/physical-display assertion.

Only three empty parent groups receive new state managers. Original numeric,
unit, warning, data binding, theme and indicator resources remain byte-exact.
State zero contains NO overrides: restoration reveals current OEM state rather
than replaying a stale snapshot. Private inherited property has no CAN meaning.
"""
import struct
from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES, node_head
from background_poc_native import ROOT, apply_hmi_overlay, digest
from unlimited_native import HMI_SOURCE

SOURCE = ROOT / "phase1/qnx/native-hmi/navigation-layout.thumb"
PROPERTY = "Dashcast.NavigationLayout"
KZB_SHA = "4a1938ec7eb6714b8bc116c57ad44447cfb1ec7371931827b886e288c319dc3d"
NAV_STATUS = 0x4e415600


class NavigationSource:
    def read_text(self, encoding):
        base = HMI_SOURCE.read_text(encoding)
        if base.count("bg_done:\n") != 1:
            raise ValueError("Unlimited render-thread return changed")
        return base.replace("bg_done:\n", "bg_done:\nbl nav_tick\n") + "\n" + SOURCE.read_text(encoding)


def build_navigation_hmi(original):
    binary, report = apply_hmi_overlay(original, source_path=NavigationSource())
    report.update(navigation_layout=True, inherited_property=PROPERTY,
                  status_word=29, status_ready=NAV_STATUS, status_active=NAV_STATUS | 1,
                  base_source_sha256=digest(HMI_SOURCE.read_bytes()),
                  extension_source_sha256=digest(SOURCE.read_bytes()),
                  vehicle_data_modified=False, qnx_runtime_tested=False)
    return binary, report


def words(*values):
    return struct.pack("<" + "I" * len(values), *values)


def srt(x, y, scale=1.0):
    return struct.pack("<5f", scale, scale, 0.0, x, y)


# All paths below are present in the original OEM text TOC. No warning, lamp,
# battery/range or gear visibility is changed. Decorative dials only are hidden.
CLUSTER = "content/Prefab_Cluster_Power_Speed/content/"
CLUSTER_RULES = [
    (CLUSTER + "Power/PowerShow", "Node2D.RenderTransformation", srt(-110, 270)),
    (CLUSTER + "Power/RPMShow", "Node2D.RenderTransformation", srt(-110, 270)),
    (CLUSTER + "Speed/SpeedShow", "Node2D.RenderTransformation", srt(40, 230)),
] + [(CLUSTER + path, "Node.Opacity", struct.pack("<f", 0.0)) for path in (
    "Power/MeterPic", "Power/Rotation_Effect", "Power/UperText", "Power/TextPowerRange",
    "Power/Light", "Power/Text_RPM", "Speed/MeterPic", "Speed/Rotation_Effect", "Speed/Text",
)] + [("content/Prefab_Cluster_Board", "Node.Opacity", struct.pack("<f", 0.0))]
LAYOUTS = (
    ("Cluster", 2544, CLUSTER_RULES),
    ("Gear", 1855, [(".", "Node2D.RenderTransformation", srt(0, -48))]),
    ("Top_Bottom", 4024, [
        ("Prefab_TopBottom/content/Time", "Node2D.RenderTransformation", srt(0, -38)),
        ("Prefab_TopBottom/content/Mileage_Information", "Node2D.RenderTransformation", srt(1393, 676, 0.8)),
    ]),
)


def dictionary(values):
    return words(len(values)) + b"".join(value.encode("utf-8") + b"\0" for value in values)


def state_manager(strings, properties, rules):
    """StateManager wire grammar from native loadStateGroup, not JSON binding syntax."""
    string_id = lambda text: strings.index(text)
    prop_id = lambda text: properties.index(text)
    # Object properties/localizations/bindings: empty. One controlled state group.
    output = words(0, 0, 0, 1, string_id("State Group"), prop_id(PROPERTY), 0xffffffff, 2)
    # Native state 0 removes our modifiers. Do not snapshot OEM property values.
    output += words(string_id("Null"), 0, 0)
    output += words(string_id("DashcastActive"), 1, len(rules))
    for path, key, value in rules:
        output += words(string_id(path), 1, prop_id(key)) + value + words(0, 0, 0)
    return output + words(0)  # No transitions/animations.


def band_node(directory, strings, name, y, height):
    """Owned, initially invisible native contrast strip; no new renderer/library."""
    props = directory.dictionary("/$property_dictionary")
    classes = directory.dictionary("/$metaclass_dictionary")
    text = lambda value: words(len(value.encode())) + value.encode()
    values = [
        ("Node.Opacity", struct.pack("<f", 0.0)),
        ("Node.Width", struct.pack("<f", 1920.0)),
        ("Node.Height", struct.pack("<f", height)),
        ("Node2D.RenderTransformation", srt(0, y)),
        ("Node2D.BackgroundBrush", text("kzb://bic_byd_stha/Brushes/Color Brush_Black")),
        ("Node.StateManager", text("kzb://bic_byd_stha/State Managers/Dashcast_Band")),
    ]
    empty = strings.index("")
    output = words(strings.index(name), empty, classes.index("Kanzi.EmptyNode2D"), len(values))
    output += b"".join(words(props.index(key)) + value for key, value in values)
    # Same empty localization/children/bindings/actions tail as OEM UI_Layout1.
    if directory.read("/StartupPrefab")[3932:3952] != words(0, 0, 0, 0, empty):
        raise ValueError("OEM empty-node tail grammar changed")
    return output + words(0, 0, 0, 0, empty)


def attach_manager(directory, data, offset, expected_name, url):
    header = node_head(directory, data, offset)
    if header["name"] != expected_name or header["node_class"] != "Kanzi.EmptyNode2D":
        raise ValueError("OEM navigation parent changed")
    if header["properties"].get("Node.StateManager", ""):
        raise ValueError("Must not replace an OEM state manager")
    properties = directory.dictionary("/$property_dictionary")
    types = custom_types(directory)
    c = Cursor(data, offset + 12)
    count = c.word()
    encoded = []
    found = False
    for _ in range(count):
        begin = c.offset
        key = c.word()
        kind = types[properties[key]]["kind"] if properties[key] in types else STANDARD_NODE_TYPES[key]
        c.variant(kind)
        if properties[key] == "Node.StateManager":
            raw = url.encode()
            encoded.append(words(key, len(raw)) + raw)
            found = True
        else:
            encoded.append(data[begin:c.offset])
    if not found:
        raw = url.encode()
        encoded.append(words(properties.index("Node.StateManager"), len(raw)) + raw)
    result = data[offset:offset + 12] + words(len(encoded)) + b"".join(encoded)
    result += data[c.offset:header["end_properties"]]
    return data[:offset] + result + data[header["end_properties"]:]


def repack(directory, changes, additions):
    names = list(directory.records) + list(additions)
    if len(set(names)) != len(names):
        raise ValueError("Duplicate KZB resource")
    project = directory.project.encode()
    prefix = b"KZBF\x03\0\0\0" + words(len(project)) + project + words(len(names))
    prefix += b"".join(name.encode() + b"\0" for name in names) + words(len(names))
    cursor = len(prefix) + len(names) * 24
    records, payload = [], bytearray()
    for index, name in enumerate(names):
        if name in directory.records:
            old = directory.records[name]
            raw = changes.get(name, directory.data[old["offset"]:old["offset"] + old["size"]])
            cls, flags, decoded = old["class_id"], old["flags"], old["decoded_size"]
            if name in changes:
                if flags:
                    raise ValueError("Cannot rewrite compressed resource")
                decoded = len(raw)
        else:
            cls, raw = additions[name]
            flags, decoded = 0, len(raw)
        padding = (-cursor) % 4
        payload.extend(bytes(padding))
        cursor += padding
        records.append(words(index, cls, cursor, len(raw), flags, decoded))
        payload.extend(raw)
        cursor += len(raw)
    result = prefix + b"".join(records) + payload
    KzbDirectory(result)
    return result


def build_navigation_kzb(original, toc):
    if digest(original) != KZB_SHA:
        raise ValueError("Not the exact inspected domestic KZB")
    d = KzbDirectory(original)
    strings = d.dictionary("/$strings")
    properties = d.dictionary("/$property_dictionary")
    # Keep every old ID stable; append only. No generated expression language.
    if PROPERTY in properties or PROPERTY in strings:
        raise ValueError("Navigation overlay already applied")
    properties.append(PROPERTY)
    required = [PROPERTY, "DashcastActive", "DashcastTopBand", "DashcastBottomBand",
                "kzb://bic_byd_stha/State Managers/Dashcast_Band"]
    for parent, _, rules in LAYOUTS:
        required.append("kzb://bic_byd_stha/State Managers/Dashcast_" + parent)
        required.extend(path for path, _, _ in rules)
        prefix = "Screens/Screen/RootPage/Content/" + parent
        for path, _, _ in rules:
            # Startup inherited Cluster/TopBottom prefab paths are listed twice:
            # resolve to the original prefab TOC, not a guessed runtime target.
            if parent == "Cluster":
                candidate = "Screens/Screen/RootPage/Content/Cluster/" + path
                if path.startswith(CLUSTER):
                    candidate = "Prefabs/Prefab_Cluster_Power_Speed/Prefab_Cluster_Power_Speed/" + path[len("content/Prefab_Cluster_Power_Speed/"):]
            elif parent == "Top_Bottom":
                candidate = "Prefabs/Prefab_TopBottom/Prefab_TopBottom/" + path[len("Prefab_TopBottom/"):]
            else:
                candidate = prefix if path == "." else prefix + "/" + path
            if candidate not in toc:
                raise ValueError("Unproven OEM path: " + candidate)
    for text in required:
        if text not in strings:
            strings.append(text)
    custom = d.read("/$property_types")
    # Same inherited int registration as existing animationstate_*: flags=0,
    # inherited=1, default=0. No meaning in the data-engine/CAN namespace.
    custom = words(struct.unpack_from("<I", custom)[0] + 1) + custom[4:]
    custom += words(strings.index(PROPERTY), 1, 0, 1, 0)
    startup = d.read("/StartupPrefab")
    additions = {}
    state_class = d.records["/State Managers/NaviShowContent"]["class_id"]
    for parent, offset, rules in sorted(LAYOUTS, key=lambda x: x[1], reverse=True):
        name = "/State Managers/Dashcast_" + parent
        url = "kzb://bic_byd_stha" + name
        startup = attach_manager(d, startup, offset, parent, url)
        additions[name] = (state_class, state_manager(strings, properties, rules))
    # Insert owned contrast bands after BG_New, before all original foreground
    # groups. Original Content had 13 children; no original ordering is changed.
    if struct.unpack_from("<I", startup, 476)[0] != 13:
        raise ValueError("OEM Content child count changed")
    startup = startup[:476] + words(15) + startup[480:]
    bands = band_node(d, strings, "DashcastTopBand", 0, 118)
    bands += band_node(d, strings, "DashcastBottomBand", 550, 170)
    startup = startup[:1855] + bands + startup[1855:]
    additions["/State Managers/Dashcast_Band"] = (
        state_class, state_manager(strings, properties, [(".", "Node.Opacity", struct.pack("<f", 1.0))]))
    changes = {"/$strings": dictionary(strings), "/$property_dictionary": dictionary(properties),
               "/$property_types": custom, "/StartupPrefab": startup}
    result = repack(d, changes, additions)
    check = KzbDirectory(result)
    if custom_types(check)[PROPERTY] != dict(kind=1, flags=0, inherited=1, default=0):
        raise ValueError("Inherited property registration readback failed")
    for name, row in d.records.items():
        if name not in changes and original[row["offset"]:row["offset"] + row["size"]] != check.read(name):
            raise ValueError("Unrelated OEM resource changed: " + name)
    return result, dict(input_sha256=digest(original), output_sha256=digest(result),
                        modified_resources=list(changes), added_resources=list(additions),
                        unchanged_resources=len(d.records) - len(changes),
                        rules={parent: [(path, key) for path, key, _ in rules] for parent, _, rules in LAYOUTS},
                        owned_bands=[[0, 0, 1920, 118], [0, 550, 1920, 170]],
                        qnx_runtime_tested=False, physical_layout_tested=False)
