"""Projection-only positions for native indicator nodes on the reviewed RWVI pair.

One geometry group is appended to the indicators' own content manager; original
state groups, event/binding tails and textures survive. The group reads its control
through the local-only optional getter, so the HMI writes a private local value on
the current instance's content node, in full screen only. Video, USB and capability are unchanged.
"""
import io
import re
import struct
from pathlib import Path

from PIL import Image

from analyze_kzb_hmi import KzbDirectory, Cursor, STANDARD_NODE_TYPES, custom_types, node_head
from navigation_layout import digest, words, dictionary, repack, srt
from theme_geometry import variant

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'instrument/native-hmi/warning-control.thumb'
INPUT_SHA = '70ae4e79c722e98cc393552fe32fd3b1b71babdeee25bc592881b83e6f1e9b68'
HMI_SHA = 'd910e76aee04113299a5febadab99e4de6ce96196cd8ce23a20b14aa7080e90c'
NATIVE_MANAGER = '/State Managers/Fashion_Indicator_Theme'
SAFETY_URL = 'kzb://bic_byd_stha/State Managers/DCSafetyRail'
STYLES = ('Fashion', 'Classic', 'ACC')
CONTROL = 'DCWarningLayout'
# First free byte of the owned code slot after the retained 1.25.1 gauge helper.
HELPER_END = 0x08047880 + 1080
TRANSFORM = 'Node2D.RenderTransformation'
LAYOUT = 'Node2D.LayoutTransformation'
ORIGIN = 'Node2D.RenderTransformationOrigin'
GEOMETRY = (TRANSFORM, LAYOUT, ORIGIN, 'Node.Width', 'Node.Height',
            'Node.HorizontalAlignment', 'Node.VerticalAlignment')
# User-approved panel-pixel destinations. They are not local SRT constants.
SLOTS = {
    'TurnLeft': (131, 130, 45, 43), 'TurnRight': (1743, 130, 45, 44),
    'SeatBelt': (396, 76, 40, 48), 'PowerBatteryTempHigh': (336, 84, 43, 44),
    'DriveMotorFault': (284, 92, 44, 44), 'PabState': (230, 102, 48, 41),
    'DriveMotorOverHeat': (182, 112, 44, 40), 'SRSError': (82, 142, 44, 40),
    'MainWarningLamp': (36, 186, 44, 38), 'DrivePowerLimit': (84, 186, 38, 38),
    'PowerBatteryFault': (36, 228, 42, 38),
    # ceil(53*.8), not round(): retain the OEM coolant icon's .8 render scale.
    'CoolantTempTooHigh': (82, 228, 43, 37),
    'DriveMotorCoolantHigh': (44, 270, 46, 36), 'MILLED': (94, 308, 49, 33),
    'Battery': (64, 346, 49, 32), 'PowerSysFault': (74, 382, 61, 28),
    'EngineOilPressureLow': (84, 414, 57, 23), 'ESP OFF': (1691, 110, 39, 53),
    'ExternalLightingError': (1642, 112, 44, 40), 'ABSError': (1795, 146, 51, 39),
    'ESP ERROR': (1845, 190, 39, 39), 'BrakeSystemFault': (1790, 190, 50, 39),
    'ElectPBSysIndReq': (1834, 234, 50, 38), 'BrakePadWear': (1823, 276, 51, 38),
    'EPSErr': (1816, 318, 46, 37), 'EBSystemError': (1800, 360, 50, 37),
    'EBSystemWarn': (1786, 402, 50, 37), 'PCWSafetyDistanceWarn': (1732, 442, 50, 37),
    'CheckTyre': (1796, 234, 34, 36), 'SmartKeySysWarn': (1762, 484, 46, 34),
    'TaiLlight': (560, 112, 43, 24), 'HighBeam': (630, 112, 40, 24),
    'FrontFogLED': (700, 112, 34, 24), 'RearFogLED': (1220, 112, 24, 24),
    'SmartHeadLightsWhite': (1290, 112, 40, 24), 'ACCModeErr': (1708, 494, 50, 47),
    'SteepSlopeError': (100, 442, 54, 42), 'GPF RequireMaintain': (158, 482, 48, 34),
    'ChargingConnectStatus': (1589, 112, 49, 32), 'Discharge': (126, 520, 44, 28),
    'BrakeSystemFault_1': (1380, 114, 84, 17),
    # 1.33.0: driver-assist and status icons that were still over the video.
    'ACCModeGreen': (438, 76, 52, 45), 'Cruise': (492, 112, 64, 24), 'TJAWork0': (738, 112, 55, 25),
    'EngineSimulationOff': (798, 112, 40, 25), 'LedInDoorShowMode': (1466, 112, 60, 25),
    'AVHModeGreen': (1772, 320, 42, 36), 'ReservationCharging': (1782, 272, 39, 44),
    'LEDGuidedBlindSpot': (115, 343, 40, 37), 'LEDInternshipCareGreen': (172, 520, 46, 34),
    'LEDGameMode': (110, 486, 46, 32),
}
ALIASES = {
    'EngineOilPressureLow_Yellow': 'EngineOilPressureLow', 'ABS': 'ABSError',
    'GPF RequireHighSpeed': 'GPF RequireMaintain', 'AllWeatherLED': 'FrontFogLED',
    'LightSwitch': 'TaiLlight', 'SmartHeadLightsYellow': 'SmartHeadLightsWhite',
    'SmartHeadLowBeamLights': 'SmartHeadLightsWhite', 'LEDHMAWhite': 'SmartHeadLightsWhite',
    'ACCModeWhite': 'ACCModeGreen', 'MainlyCruiseKey': 'ACCModeGreen', 'MainlyCruiseKeyWhite': 'ACCModeGreen',
    'ACCGreen100': 'Cruise', 'ACCWhite100': 'Cruise', 'ACCGreen60': 'Cruise', 'ACCWhite60': 'Cruise',
    'TJAWork1': 'TJAWork0', 'TJAWork2': 'TJAWork0', 'AVHModeWhite': 'AVHModeGreen',
    'SteepSlope': 'SteepSlopeError', 'SteepSlopeSetSpeed': 'SteepSlopeError',
    'ReservationCharging_1': 'ReservationCharging', 'ReservationChargingOff': 'ReservationCharging',
    'ReservationChargingOff_1': 'ReservationCharging', 'LedOutDoorShowMode': 'LedInDoorShowMode',
    'LEDGuidedBlindGreen': 'LEDGuidedBlindSpot', 'LEDGuidedBlindWhite': 'LEDGuidedBlindSpot',
    'LEDInternshipCareWhite': 'LEDInternshipCareGreen',
}
LIGHTS = frozenset(('TaiLlight', 'HighBeam', 'FrontFogLED', 'RearFogLED', 'SmartHeadLightsWhite'))
# The perimeter is tighter than these icons' OEM rows: they shrink to fit and never grow.
FITTED = frozenset(('ACCModeGreen', 'Cruise', 'TJAWork0', 'EngineSimulationOff', 'LedInDoorShowMode', 'AVHModeGreen',
                    'ReservationCharging', 'LEDGuidedBlindSpot', 'LEDInternshipCareGreen', 'LEDGameMode', 'SteepSlopeError'))
# The cruise set speed is text: left edge, top and uniform scale of its 100-wide box, right-aligned by the OEM.
# It shares the Cruise slot, as it shares the OEM position with the SET image.
TEXT_SLOTS = {'ACCGreen': (492, 104, .64), 'ACCWhite': (492, 104, .64)}


def heads(directory, raw, wanted):
    """Find exact named headers without decoding/re-encoding native binding tails."""
    strings = directory.dictionary('/$strings')
    result = {}
    for name in wanted:
        pattern, begin, found = words(strings.index(name)), 0, []
        while True:
            at = raw.find(pattern, begin)
            if at < 0:
                break
            begin = at + 1
            try:
                h = node_head(directory, raw, at)
                if h['name'] == name:
                    found.append(h)
            except (ValueError, KeyError, IndexError, struct.error, UnicodeError):
                continue
        if len(found) != 1:
            raise ValueError('Expected unique node header: ' + name)
        result[name] = found[0]
    return result


def rewrite_head(directory, raw, head, replace):
    props, kinds = directory.dictionary('/$property_dictionary'), custom_types(directory)
    at = head['offset']
    c = Cursor(raw, at + 12)
    count, entries, consumed = c.word(), [], set()
    for _ in range(count):
        start = c.offset
        key = c.word()
        name = props[key]
        variant(c, kinds[name]['kind'] if name in kinds else STANDARD_NODE_TYPES[key])
        if name in replace:
            consumed.add(name)
            if replace[name] is not None:
                entries.append(words(key) + replace[name])
        else:
            entries.append(raw[start:c.offset])
    if consumed != set(replace):
        raise ValueError('Refuse to invent an original node property')
    header = raw[at:at + 12] + words(len(entries)) + b''.join(entries)
    # Localization/children/bindings/actions after the property list are untouched.
    header += raw[c.offset:head['end_properties']]
    return raw[:at] + header + raw[head['end_properties']:]


def native_image_size(directory, head):
    p = head['properties']
    texture = directory.read(p['Image2D.Image'].removeprefix('kzb://bic_byd_stha'))
    if len(texture) != 36:
        raise ValueError('Unexpected indicator texture descriptor')
    url = directory.dictionary('/$strings')[struct.unpack_from('<I', texture, 32)[0]]
    raw = directory.read(url.removeprefix('kzb://bic_byd_stha'))
    if raw[:16] != bytes(16) or struct.unpack_from('<I', raw, 16)[0] != len(raw) - 20:
        raise ValueError('Unexpected PNG resource header')
    with Image.open(io.BytesIO(raw[20:])) as image:
        width, height = image.size
    return p.get('Node.Width', width), p.get('Node.Height', height)


def position_properties(directory, head, family):
    """Neutralize layout offset/alignment/origin before an absolute leaf render SRT.

    The four original ancestors are identity; no ancestor geometry is modified.
    Width/height retain the original image layout extent, uniform render scale
    preserves aspect ratio, and the glyph is centered in its family rectangle.
    """
    if head['node_class'] != 'Kanzi.Image2D':
        raise ValueError('Only native image indicators may move')
    width, height = native_image_size(directory, head)
    old = head['properties'].get(TRANSFORM, [1., 1., 0., 0., 0.])
    if old[0] <= 0 or old[0] != old[1] or old[2] != 0:
        raise ValueError('Unexpected OEM indicator scale/rotation')
    x, y, w, h = SLOTS[family]
    scale = min(w / width, h / height) if family in LIGHTS else min(old[0], w / width, h / height) if family in FITTED else old[0]
    if width * scale > w + 1e-4 or height * scale > h + 1e-4:
        raise ValueError('Original native glyph does not fit its family: ' + head['name'])
    x += (w - width * scale) / 2
    y += (h - height * scale) / 2
    f = lambda value: struct.pack('<f', value)
    # Kanzi: horizontal 0 is left, vertical 1 is top. Vertical 0 is bottom (Node2D::arrange)
    # and would push every icon below the panel.
    values = {TRANSFORM: srt(x, y, scale), LAYOUT: srt(0, 0),
              ORIGIN: struct.pack('<2f', 0., 0.), 'Node.Width': f(width), 'Node.Height': f(height),
              'Node.HorizontalAlignment': words(0), 'Node.VerticalAlignment': words(1)}
    if set(values) != set(GEOMETRY):
        raise ValueError('Geometry contract changed')
    return values


def text_properties(name):
    """Text nodes are placed by their layout transform; size and alignment stay the OEM's."""
    x, y, scale = TEXT_SLOTS[name]
    return {LAYOUT: srt(x, y, scale), TRANSFORM: srt(0, 0)}


def position_group(strings, props, rows):
    # Only full screen moves the indicators (the HMI writes 0 in the small window), so one state is enough.
    result = words(strings.index('DCWarningPositions'), props.index(CONTROL), 0xffffffff, 2)
    result += words(strings.index('DCWIdle'), 0, 0) + words(strings.index('DCWShown'), 1, len(rows))
    for name, values in rows.items():
        result += words(strings.index(name), len(values))
        result += b''.join(words(props.index(k)) + v for k, v in values.items()) + words(0, 0, 0)
    return result + words(0)  # no geometry transition animations


def build(original):
    if digest(original) != INPUT_SHA:
        raise ValueError('Exact reviewed 1.25.1 KZB required')
    d = KzbDirectory(original)
    strings, props = d.dictionary('/$strings'), d.dictionary('/$property_dictionary')
    if props[127] != 'Node.VisibleAmountInParent' or CONTROL in props:
        raise ValueError('Original property dictionary changed')
    # The same local, non-inherited integer form as the OEM theme control.
    if custom_types(d)['state'] != dict(kind=1, flags=0, inherited=0, default=0):
        raise ValueError('Original control property registration changed')
    names = sorted(set(SLOTS) | set(ALIASES) | set(TEXT_SLOTS))
    for text in ('DCWarningPositions', 'DCWIdle', 'DCWShown', CONTROL):
        if text in strings:
            raise ValueError('Already patched warning layout')
        strings.append(text)
    props.append(CONTROL)
    changes, style_reports = {}, {}
    native = d.read(NATIVE_MANAGER)
    if struct.unpack_from('<4I', native) != (0, 0, 0, 3):
        raise ValueError('Original indicator manager groups changed')
    # Only the three indicator prefabs may gain the appended group.
    users = [n for n in d.records if not n.startswith('/$') and NATIVE_MANAGER.encode() in d.read(n)]
    if sorted(users) != sorted('/Prefabs/Prefab_Indicator_' + s for s in STYLES):
        raise ValueError('Indicator manager is referenced elsewhere')
    reference_rows = None
    for style in STYLES:
        resource = '/Prefabs/Prefab_Indicator_' + style
        raw = d.read(resource)
        nodes = heads(d, raw, names + ['content', 'Prefab_Indicator_' + style])
        for parent in ('content', 'Prefab_Indicator_' + style):
            if any(k in nodes[parent]['properties'] for k in GEOMETRY):
                raise ValueError('Indicator ancestor is not the reviewed identity parent')
        if nodes['content']['properties'].get('Node.StateManager') != 'kzb://bic_byd_stha' + NATIVE_MANAGER:
            raise ValueError('Unrecognized original indicator manager')
        if nodes['SeatBelt']['properties'].get('Node.StateManager') != SAFETY_URL:
            raise ValueError('Expected owned, removable safety position controller')
        rows = {name: text_properties(name) if name in TEXT_SLOTS else position_properties(d, nodes[name], ALIASES.get(name, name))
                for name in names}
        if reference_rows is None:
            reference_rows = rows
        if rows != reference_rows:
            raise ValueError('Indicator image geometry differs across OEM styles')
        changes[resource] = rewrite_head(d, raw, nodes['SeatBelt'], {'Node.StateManager': None})
        style_reports[style] = dict(moved_nodes=len(rows), original_binding_and_action_bytes_preserved=True)
    # Keep every original group/transition byte; append exactly one geometry group.
    changes[NATIVE_MANAGER] = native[:12] + words(4) + native[16:] + position_group(strings, props, reference_rows)
    custom = d.read('/$property_types')
    changes['/$strings'], changes['/$property_dictionary'] = dictionary(strings), dictionary(props)
    changes['/$property_types'] = words(struct.unpack_from('<I', custom)[0] + 1) + custom[4:] + words(strings.index(CONTROL), 1, 0, 0, 0)
    output = repack(d, changes, {})
    after = KzbDirectory(output)
    if any(after.read(n) != d.read(n) for n in d.records if n not in changes):
        raise ValueError('Unrelated native resource changed')
    if set(after.records) != set(d.records):
        raise ValueError('Resource set changed')
    return output, dict(input_sha256=digest(original), output_sha256=digest(output),
        changed_resources=list(changes), styles=style_reports,
        panel_slots=len(SLOTS), native_images_moved=len(names), native_logic_preserved=True,
        clear_apertures_unchanged=True, runtime_kanzi_or_vehicle=False)


def assemble_control(base, find, nav):
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    source = re.sub(r'(?m)//.*$', '', SOURCE.read_text('utf-8').replace('BG_FIND', hex(find)).replace('NAV_TICK', hex(nav)))
    externals = []
    def defer(m):
        label = 'warning_external_' + str(len(externals)); externals.append((label, m[1], int(m[2], 16)))
        return label + ':\nnop.w'
    source = re.sub(r'(?m)^(bl|blx) #0x([0-9a-f]+)\s*$', defer, source)
    names = ('warning_entry', 'warning_tick') + tuple(x[0] for x in externals)
    encoded, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(source + '\n' + '\n'.join('.word ' + n for n in names), addr=base)
    if encoded is None:
        raise ValueError('Warning control assembly failed')
    encoded = bytes(encoded)
    labels = dict(zip(names, struct.unpack('<' + 'I' * len(names), encoded[-4 * len(names):])))
    raw = bytearray(encoded[:-4 * len(names)])
    for name, instruction, target in externals:
        at = labels[name]
        b, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f'{instruction} #{target}', addr=at)
        ins = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(bytes(b), at))
        if len(b) != 4 or len(ins) != 1 or int(ins[0].op_str.removeprefix('#'), 16) != target:
            raise ValueError('Warning control branch relaxation mismatch')
        raw[at - base:at - base + 4] = bytes(b)
    return bytes(raw), {k: v for k, v in labels.items() if not k.startswith('warning_external_')}


def build_hmi(hmi):
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB
    from elftools.elf.elffile import ELFFile
    from axes_rim_layout import AxesSource
    from background_poc_native import compile_thumb
    from elf_patch import Patch
    from theme_geometry import CODE, CAPACITY
    if digest(hmi) != HMI_SHA:
        raise ValueError('Exact reviewed 1.25.1 HMI required')
    patch = Patch(hmi, HMI_SHA)
    site = CODE + 2  # gauge_entry's call to the original nav_tick
    old = hmi[patch.offset(site, 4):patch.offset(site, 4) + 4]
    ins = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(old, site))
    if len(ins) != 1 or ins[0].mnemonic != 'bl':
        raise ValueError('Gauge entry call changed')
    nav = int(ins[0].op_str.removeprefix('#'), 16)
    _, old_labels, _ = compile_thumb(AxesSource())
    base = (HELPER_END + 3) & ~3
    code, labels = assemble_control(base, old_labels['bg_find'], nav)
    if base + len(code) > CODE + CAPACITY:
        raise ValueError('Warning control exceeds owned slot')
    tail = patch.offset(HELPER_END, CODE + CAPACITY - HELPER_END)
    free = hmi[tail:tail + CODE + CAPACITY - HELPER_END]
    if free != bytes(len(free)):
        raise ValueError('Owned slot tail occupied')
    patch.put('warning_control_helper', base, code, expected=bytes(len(code)))
    branch, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f"bl #{labels['warning_entry']}", addr=site)
    patch.put('render_warning_control_call', site, bytes(branch), expected=old)
    output, ranges = patch.finish()
    a, b = ELFFile(io.BytesIO(hmi)), ELFFile(io.BytesIO(output))
    if a.header != b.header or [dict(s.header) for s in a.iter_segments()] != [dict(s.header) for s in b.iter_segments()]:
        raise ValueError('ELF structure changed')
    return output, dict(hmi_input_sha256=digest(hmi), hmi_output_sha256=digest(output), helper_labels=labels,
        helper_base=base, helper_bytes=len(code), slot_end=CODE + CAPACITY, hook=site, original_nav_tick=nav,
        bg_find=old_labels['bg_find'], hmi_patches=ranges, elf_headers_unchanged=True)


def build_pair(hmi, kzb):
    output_hmi, control = build_hmi(hmi)
    output_kzb, report = build(kzb)
    report.update(control=control, capability_unchanged='RWVI', real_kanzi_executed=False)
    return output_hmi, output_kzb, report
