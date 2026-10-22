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
from ref2_noauth_native import Patch

PROPERTY = "DCLayout"
THEME = "DCTheme"
MANAGER = "/State Managers/DCLayout"
TOP, BOTTOM = "DCTop", "DCBottom"
CENTER_RECT = (576, 128, 768, 432)
SOURCE = ROOT / "phase1/qnx/native-hmi/display-fusion.thumb"
RECEIVER_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-display-fusion.arm"
RECEIVER_SHA = "7c5e36bfaedafb963dba9ff42619c50254fb0c4a45da378402a560dc4bad08fe"
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


def build_fusion_receiver(original):
    import re
    patch = Patch(original, RECEIVER_SHA)
    source = RECEIVER_SOURCE.read_text("utf-8")
    blocks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    expected = {"composition_rect": (0x1041ac, 316), "composition_window_order": (0x102c80, 72),
                "owned_layer_format": (0x1029ec, 56)}
    for i in range(1, len(blocks), 4):
        name, address, capacity, code = blocks[i:i + 4]
        if expected.pop(name, None) != (int(address, 16), int(capacity)):
            raise ValueError("Unknown fusion receiver slot")
        patch.arm(name, int(address, 16), int(capacity), re.sub(r"(?m)//.*$", "", code).strip())
    if expected:
        raise ValueError("Missing fusion receiver slots")
    # Rebuild ONLY the original mixed control slot with its existing branch rebinder.
    # 5001 is an explicit center selector, never a longer lease.
    from unlimited_native import RECEIVER_SOURCE as LEGACY_SOURCE
    class ReceiverSource:
        def read_text(self, encoding):
            text_ = LEGACY_SOURCE.read_text(encoding)
            before = "cmp r1, r3\nbne bg_control_cleanup"
            after = ("cmp r1, r3\nbeq dc_hold_ok\ncmp r0, #1\nbne bg_control_cleanup\n"
                     "add r3, r2, #1\ncmp r1, r3\nbne bg_control_cleanup\ndc_hold_ok:")
            if text_.count(before) != 1:
                raise ValueError("Native hold selector guard drift")
            text_ = text_.replace(before, after)
            before = "str r2, [r6, #16]"
            after = ("cmp r0, #1\nbne dc_mode_store\nldr r3, [r5, #12]\nrev r3, r3\n"
                     "movw ip, #5001\ncmp r3, ip\nmoveq r2, #3\ndc_mode_store:\nstr r2, [r6, #16]")
            if text_.count(before) != 1:
                raise ValueError("Native selector commit drift")
            return text_.replace(before, after)
        def read_bytes(self):
            return self.read_text("utf-8").encode()
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
    from ref2_noauth_native import NOP
    control_source = ReceiverSource().read_text("utf-8").split(
        "// SLOT background_control 0x108898 3232\n", 1)[1]
    control_source = re.sub(r"(?m)//.*$", "", control_source).strip()
    external = []
    def defer(match):
        label = "dc_external_" + str(len(external))
        external.append((label, match[1], int(match[2], 16)))
        return label + ":\nnop"
    control_source = re.sub(r"(?m)^(b(?:l|lx|eq|ne|lo|hi|hs|ls|ge|gt|le|lt|mi|pl|cs|cc)?) #0x([0-9a-f]+)\s*$",
                            defer, control_source)
    names = ("unlimited_header_gate", "unlimited_frame_idle") + tuple(e[0] for e in external)
    code = control_source + "\n.ltorg\n.balign 4\n" + "\n".join(".word " + n for n in names)
    raw, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM).asm(code, addr=0x108898)
    raw = bytes(raw)
    labels = dict(zip(names, struct.unpack("<" + "I" * len(names), raw[-4 * len(names):])))
    raw = bytearray(raw[:-4 * len(names)])
    for label, instruction, target in external:
        branch, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM).asm(f"{instruction} #{target}", addr=labels[label])
        decoded = list(Cs(CS_ARCH_ARM, CS_MODE_ARM).disasm(bytes(branch), labels[label]))
        if len(branch) != 4 or len(decoded) != 1 or int(decoded[0].op_str.removeprefix("#"), 16) != target:
            raise ValueError("Fusion control external branch binding mismatch")
        off = labels[label] - 0x108898
        raw[off:off + 4] = bytes(branch)
    if len(raw) > 3232 or len(raw) % 4:
        raise ValueError("Fusion control exceeds the existing slot")
    patch.put("center_control_selector", 0x108898, bytes(raw) + NOP * ((3232 - len(raw)) // 4))
    patch.arm("center_header_gate_rebound", 0x101970, 4, f"bl #{labels['unlimited_header_gate']}")
    patch.arm("center_heartbeat_gate_rebound", 0x1029d4, 24, f"b #{labels['unlimited_frame_idle']}")
    binary, report = patch.finish()
    report.update(center_rect=CENTER_RECT, encoded_size_unchanged=[1920, 720],
                  allowed_flags=[0, 1, 2], center_parent_z=-3, lease_ms=5000,
                  source_sha256=digest(source.encode()), control_source_sha256=digest(ReceiverSource().read_bytes()),
                  labels={k: labels[k] for k in names[:2]}, physical_composition_tested=False)
    return binary, report
