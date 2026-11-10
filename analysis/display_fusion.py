"""1.2.0 native theme/frame and center-profile overlays on exact OEM inputs.

No image synthesis, vehicle-data replacement, OS/MCU change or SDK claim.
The existing render-thread lease remains the sole owner of the temporary style.
"""
import struct
from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES, node_head
from navigation_layout import ROOT, KZB_SHA, words, srt, dictionary, repack, digest
from navigation_layout_refine import RULES as OLD_RULES, TIME, PREFIX
from background_poc_native import apply_hmi_overlay
from unlimited_native import HMI_SOURCE
from elf_patch import Patch

PROPERTY = "DCLayout"
THEME = "DCTheme"
MANAGER = "/State Managers/DCLayout"
TOP, BOTTOM = "DCTop", "DCBottom"
CENTER_RECT = (576, 128, 768, 432)
SOURCE = ROOT / "instrument/native-hmi/display-fusion.thumb"
FULL_RULES = [(p, k, v) for p, k, v in OLD_RULES
              if p not in (TOP, BOTTOM, "DashcastTopBand", "DashcastBottomBand", TIME + "Line")
              and p != TIME + "Mileage_Information"]
FULL_RULES = [(p, k, srt(-110, 264) if p in (PREFIX + "Power/PowerShow", PREFIX + "Power/RPMShow")
               else srt(40, 213) if p == PREFIX + "Speed/SpeedShow" else v) for p, k, v in FULL_RULES]
FULL_RULES.append((TIME + "Mileage_Information", "Node2D.RenderTransformation", srt(1393, 666, .62)))
CENTER_RULES = [("Cluster/content/Prefab_Cluster_Board", "Node.Opacity", struct.pack("<f", 0))]


def text(value):
    raw = value.encode()
    return words(len(raw)) + raw


def encode_node(d, strings, name, values, children=()):
    props = d.dictionary("/$property_dictionary")
    classes = d.dictionary("/$metaclass_dictionary")
    return (words(strings.index(name), strings.index(""), classes.index("Kanzi.EmptyNode2D"), len(values))
            + b"".join(words(props.index(k)) + v for k, v in values)
            + words(0, len(children)) + b"".join(children) + words(0, 0, strings.index("")))


def encode_manager(strings, props, control, states):
    result = words(0, 0, 0, 1, strings.index("State Group"), props.index(control), 0xffffffff, len(states))
    for name, value, rules in states:
        result += words(strings.index(name), value, len(rules))
        for path, key, encoded in rules:
            result += words(strings.index(path), 1, props.index(key)) + encoded + words(0, 0, 0)
    return result + words(0)


def extend_theme_manager(d, strings, props):
    """Append ONLY Content.DCTheme to proven first-group states; preserve all transitions/rest."""
    raw = d.read("/State Managers/Bg_RootPage State Manager")
    c = Cursor(raw)
    if [c.word(), c.word(), c.word(), c.word()] != [0, 0, 0, 2]:
        raise ValueError("Unexpected original theme manager")
    c.word()
    if d.dictionary("/$property_dictionary")[c.word()] != "state2":
        raise ValueError("Not the inspected original theme input")
    c.word()
    if c.word() != 3:
        raise ValueError("Theme states changed")
    output, boundaries = bytearray(raw[:c.offset]), []
    kinds = custom_types(d)
    names = d.dictionary("/$property_dictionary")
    for expected in (0, 1, 2):
        name, value, count = c.word(), c.word(), c.word()
        if value != expected or count > 16:
            raise ValueError("Theme state identity changed")
        begin = c.offset
        for _ in range(count):
            c.word()
            for _ in range(c.word()):
                key = c.word()
                c.variant(kinds[names[key]]["kind"] if names[key] in kinds else STANDARD_NODE_TYPES[key])
            if [c.word(), c.word(), c.word()] != [0, 0, 0]:
                raise ValueError("Original first-group actions/bindings drift")
        output += words(name, value, count + 1) + raw[begin:c.offset]
        output += words(strings.index("Content"), 1, props.index(THEME), int(value == 1), 0, 0, 0)
        boundaries.append((begin, c.offset))
    if c.offset != 236:
        raise ValueError("Original first-group end changed")
    output += raw[c.offset:]
    return bytes(output), dict(first_group_end=236, original_transitions_and_second_group_byte_exact=True,
                               original_states=boundaries, native_theme_values=[0, 1, 0])


def frame_fragment(rgb, center, half):
    # The original TextureBrush vertex/uniform metadata remain intact. Keep its
    # sampler live without turning bitmap contents into theme or vehicle data.
    shape = ("vec2 enter = smoothstep(vec2(576.0,128.0),vec2(600.0,152.0),p);\n"
             "vec2 leave = 1.0-smoothstep(vec2(1320.0,536.0),vec2(1344.0,560.0),p);\n"
             "float alpha = 1.0-enter.x*enter.y*leave.x*leave.y;" if center else
             "float top = 1.0-smoothstep(84.0,156.0,p.y);\n"
             "float bottom = smoothstep(516.0,588.0,p.y);\n"
             "float left = 1.0-smoothstep(12.0,84.0,p.x);\n"
             "float right = smoothstep(1836.0,1908.0,p.x);\n"
             "float alpha = 0.94*(1.0-(1.0-top)*(1.0-bottom)*(1.0-left)*(1.0-right));")
    return ("precision mediump float;\nuniform lowp float BlendIntensity;\n"
            "varying mediump vec2 vTexCoord;\nuniform sampler2D Brush_Texture;\nvoid main(){\n"
            "vec2 p=vec2(gl_FragCoord.x,720.0-gl_FragCoord.y);\n"
            + shape + "\nvec3 color=vec3(" + ",".join(f"{v:.9f}" for v in rgb) + ");\n"
            "float a=alpha*BlendIntensity*(0.999+0.001*texture2D(Brush_Texture,vec2(0.5)+vTexCoord*0.0001).a);\n"
            "gl_FragColor=vec4(color*a,a);\n}\n").encode()


def build_fusion_kzb(original, toc):
    if digest(original) != KZB_SHA:
        raise ValueError("Exact original domestic KZB required")
    d = KzbDirectory(original)
    strings, props = d.dictionary("/$strings"), d.dictionary("/$property_dictionary")
    for key in (PROPERTY, THEME):
        if key in props:
            raise ValueError("Fusion already applied")
        props.append(key)
    additions = {}
    for value in (PROPERTY, THEME, TOP, BOTTOM, "DCFullFrame", "DCCenterFrame", "DCIdle", "DCFull", "DCCenter",
                  "DCDark", "DCLight", ".", "Content", "kzb://bic_byd_stha" + MANAGER):
        if value not in strings:
            strings.append(value)
    for p, _, _ in FULL_RULES + CENTER_RULES:
        if p not in strings:
            strings.append(p)
    palette = [struct.unpack_from("<4f", d.read("/Brushes/Color Brush_BgBottom_" + mode), 16)[:3]
               for mode in ("Dark", "Light")]
    material_type = d.read("/Material Types/TextureBrush")
    vertex_end = material_type.index(b"\0", 8)
    fragment_end = material_type.index(b"\0", vertex_end + 1)
    material = d.read("/Materials/TextureBrushMaterial")
    managers = {}
    for center in (False, True):
        for half in (0, 1):
            rules = []
            for theme in (0, 1):
                key = f"DCFrame{int(center)}{half}{theme}"
                typ, mat, brush = "/Material Types/" + key, "/Materials/" + key, "/Brushes/" + key
                typ_url = "kzb://bic_byd_stha" + typ
                if typ_url not in strings:
                    strings.append(typ_url)
                additions[typ] = (d.records["/Material Types/TextureBrush"]["class_id"],
                    material_type[:vertex_end + 1] + frame_fragment(palette[theme], center, half)
                    + material_type[fragment_end:])
                additions[mat] = (d.records["/Materials/TextureBrushMaterial"]["class_id"],
                    material[:4] + words(strings.index(typ_url)) + material[8:])
                additions[brush] = (d.records["/Brushes/Material Brush_Texture"]["class_id"],
                    words(0, strings.index("Kanzi.MaterialBrush"), 1, props.index("MaterialBrush.Material"))
                    + text("kzb://bic_byd_stha" + mat) + words(0))
                rules.append(("DCLight" if theme else "DCDark", theme,
                              [(".", "Node2D.BackgroundBrush", text("kzb://bic_byd_stha" + brush))]))
            path = f"/State Managers/DCTheme{int(center)}{half}"
            managers[(center, half)] = "kzb://bic_byd_stha" + path
            additions[path] = (d.records["/State Managers/NaviShowContent"]["class_id"], rules)
    for url in managers.values():
        if url not in strings:
            strings.append(url)
    mode_rules = []
    for center in (False, True):
        rules = list(CENTER_RULES if center else FULL_RULES)
        for half, parent in enumerate((TOP, BOTTOM)):
            child_path = parent + ("/DCCenterFrame" if center else "/DCFullFrame")
            rules += [(parent, "Node.Opacity", struct.pack("<f", 1)),
                      (child_path, "Node.Opacity", struct.pack("<f", 1))]
            if child_path not in strings:
                strings.append(child_path)
        mode_rules.append(("DCCenter" if center else "DCFull", 2 if center else 1, rules))
    for path in list(additions):
        if path.startswith("/State Managers/"):
            cls, states = additions[path]
            additions[path] = cls, encode_manager(strings, props, THEME, states)
    additions[MANAGER] = (d.records["/State Managers/NaviShowContent"]["class_id"],
        encode_manager(strings, props, PROPERTY, [("DCIdle", 0, [])] + mode_rules))
    # New native nodes need the appended property dictionary for serialization.
    class Extended:
        def dictionary(self, name):
            return props if name == "/$property_dictionary" else d.dictionary(name)
    nodes = b""
    for half, name in enumerate((TOP, BOTTOM)):
        children = [encode_node(Extended(), strings, "DCCenterFrame" if center else "DCFullFrame", [
            ("Node.Width", struct.pack("<f", 1920)), ("Node.Height", struct.pack("<f", 360)),
            ("Node.Opacity", struct.pack("<f", 0)),
            ("Node.StateManager", text(managers[(center, half)]))]) for center in (False, True)]
        nodes += encode_node(Extended(), strings, name, [
            ("Node.Width", struct.pack("<f", 1920)), ("Node.Height", struct.pack("<f", 360)),
            ("Node.Opacity", struct.pack("<f", 0)),
            ("Node2D.RenderTransformation", srt(0, half * 360))], children)
    startup = d.read("/StartupPrefab")
    owner = node_head(d, startup, 405)
    if owner["end_properties"] != 476 or struct.unpack_from("<I", startup, 476)[0] != 13:
        raise ValueError("Original Content precondition")
    startup = startup[:476] + words(15) + startup[480:1855] + nodes + startup[1855:]
    c = Cursor(startup, 417)
    count = c.word()
    for _ in range(count):
        key = c.word()
        kind = custom_types(d).get(props[key], {}).get("kind", STANDARD_NODE_TYPES.get(key))
        c.variant(kind)
    startup = (startup[:417] + words(count + 3) + startup[421:c.offset]
               + words(props.index("Node.StateManager")) + text("kzb://bic_byd_stha" + MANAGER)
               + words(props.index(PROPERTY), 0, props.index(THEME), 0) + startup[c.offset:])
    custom = d.read("/$property_types")
    custom = words(struct.unpack_from("<I", custom)[0] + 2) + custom[4:]
    for key in (PROPERTY, THEME):
        custom += words(strings.index(key), 1, 0, 1, 0)
    theme_manager, theme_evidence = extend_theme_manager(d, strings, props)
    changes = {"/$strings": dictionary(strings), "/$property_dictionary": dictionary(props),
               "/$property_types": custom, "/StartupPrefab": startup,
               "/State Managers/Bg_RootPage State Manager": theme_manager}
    result = repack(d, changes, additions)
    reread = KzbDirectory(result)
    for name in d.records:
        if name not in changes and d.read(name) != reread.read(name):
            raise ValueError("Unrelated OEM payload changed: " + name)
    return result, dict(input_sha256=digest(original), output_sha256=digest(result),
        modified_resources=list(changes), added_resources=list(additions),
        unchanged_resources=len(d.records) - len(changes), palette=palette, theme=theme_evidence,
        center_rect=CENTER_RECT, original_mileage_data_and_modes_unchanged=True,
        top_native_line_preserved=True, warning_parents_hidden=False, native_material_masks=8,
        mask_coordinates="gl_FragCoord in unchanged 1920x720 HMI framebuffer; not texture UV orientation",
        shader_compiled_on_target=False, physical_layout_tested=False)


class FusionSource:
    def read_text(self, encoding):
        base = HMI_SOURCE.read_text(encoding)
        edits = {
            "movw r0, #0\nstr r0, [r6, #120]": "",
            "ldr r0, [r4, #32]\nstr r0, [r4, #36]":
                "ldr r0, [r4, #32]\ncmp r0, #0\nit eq\nstreq r0, [r4, #104]\n"
                "ldr r1, [r4, #104]\nstr r1, [r6, #120]\nstr r0, [r4, #36]",
            "cmp r0, #2\nbhi bg_bad_request": "cmp r0, #3\nbhi bg_bad_request",
            "cmp r0, #1\nbeq bg_busy_request": "cmp r0, #1\nbeq bg_busy_request\ncmp r0, #3\nbeq bg_busy_request",
            "cmp r0, #2\nit eq\nmoveq r0, #1\nstr r0, [r4, #32]":
                "cmp r0, #2\nbeq dc_renew\ncmp r0, #3\nite eq\nmoveq r2, #1\nmovne r2, #0\n"
                "str r2, [r4, #104]\nstr r2, [r6, #120]\ndc_renew:\ncmp r0, #0\nit ne\nmovne r0, #1\nstr r0, [r4, #32]",
            "bg_done:\n": "bg_done:\nbl nav_tick\n",
        }
        for old, new in edits.items():
            if base.count(old) != 1:
                raise ValueError("Fusion bridge source precondition: " + old)
            base = base.replace(old, new)
        return base + "\n" + SOURCE.read_text(encoding)


def build_fusion_hmi(original):
    result, report = apply_hmi_overlay(original, source_path=FusionSource())
    report.update(mode_property=PROPERTY, native_theme_property=THEME, center_mode_supported=True,
                  lease_ms=5000, source_sha256=digest(FusionSource().read_text("utf-8").encode()),
                  physical_layout_tested=False)
    return result, report
