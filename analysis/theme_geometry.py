"""Single-owner gauge geometry on the exact RWVG pair; no OS/MCU patch.

The OEM theme groups retain every non-geometry rule. Their five conflicting
properties move into an additional local state group, controlled on the render
thread, and disappear from DCFull. Other groups/actions/bindings stay byte-exact.
"""
import io
import struct
import re
from pathlib import Path

from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES
from navigation_layout import words, dictionary, repack, digest
from display_fusion import encode_manager

ROOT = Path(__file__).resolve().parents[1]
HMI_SHA = '723129a7a544eef3370c431ef7c3f931d0f73cb1f9e4b92132c7241f36156a3c'
KZB_SHA = '75a4e82138c28933ae50dcc71637bab490e3779de50e2e44967056dcae592dc7'
KEY = 'DCGaugeLayout'
TRANSFORM = 'Node2D.RenderTransformation'
ALIGN = 'Node.HorizontalAlignment'
PREFIX = 'Cluster/content/Prefab_Cluster_Power_Speed/content/'
TARGETS = {
    'Power': [('PowerShow/PowerShowText', TRANSFORM), ('PowerShow/Power_Unit', TRANSFORM)],
    'Speed': [('SpeedShow/SpeedShowText', TRANSFORM), ('SpeedShow/Km_Per_Hour', TRANSFORM),
              ('SpeedShow/SpeedShowText', ALIGN)],
}
MANAGERS = {side: '/State Managers/BYD_Module_' + lr + '_Guage_Switch'
            for side, lr in (('Power', 'Left'), ('Speed', 'Right'))}
SOURCE = ROOT / 'instrument/native-hmi/theme-geometry.thumb'
CODE = 0x08047880
CAPACITY = 0x780
CAPABILITY = 0x52575641  # RWVH: RWVG plus single-owner, read-back gauge geometry.


def variant(c, kind):
    # SRTValue3D is scale(3), rotation quaternion(4), translation(3).
    if kind == 13:
        return c.read(40)
    begin = c.offset
    c.variant(kind)
    return c.data[begin:c.offset]


def first_group(d, raw, offset=16):
    """Parse only the bounded first group; preserve subsequent groups/transitions."""
    strings, props, kinds = d.dictionary('/$strings'), d.dictionary('/$property_dictionary'), custom_types(d)
    c = Cursor(raw)
    if [c.word(), c.word(), c.word()] != [0, 0, 0]:
        raise ValueError('Unexpected state-manager object header')
    groups = c.word()
    c.offset = offset
    name, control, initial, count = c.word(), c.word(), c.word(), c.word()
    if not 1 <= groups <= 8 or not 1 <= count <= 16:
        raise ValueError('Unbounded state manager')
    states = []
    for _ in range(count):
        state_name, value, targets = c.word(), c.word(), c.word()
        if targets > 256:
            raise ValueError('Unbounded state rules')
        nodes = []
        for _ in range(targets):
            path, size = c.word(), c.word()
            if size > 128:
                raise ValueError('Unbounded target properties')
            values = []
            for _ in range(size):
                key = c.word()
                kind = kinds[props[key]]['kind'] if props[key] in kinds else (13 if props[key] == 'Node3D.RenderTransformation' else STANDARD_NODE_TYPES[key])
                values.append((key, variant(c, kind)))
            begin = c.offset
            bindings = c.word()
            if bindings > 32:
                raise ValueError('Unbounded target bindings')
            for _ in range(bindings):
                c.word(); c.read(c.word())
            if c.word() != 0 or c.word() != 0:
                raise ValueError('Unexpected target tail')
            nodes.append((path, values, raw[begin:c.offset]))
        states.append((state_name, value, nodes))
    return dict(groups=groups, name=name, control=control, initial=initial, states=states, begin=offset, end=c.offset)


def encode_group(group):
    result = words(group['name'], group['control'], group['initial'], len(group['states']))
    for name, value, targets in group['states']:
        result += words(name, value, len(targets))
        for path, props, tail in targets:
            result += words(path, len(props)) + b''.join(words(k) + v for k, v in props) + tail
    return result


def migrate_transitions(d, raw, offset, side, strings):
    """Move only geometry animations; retain every other original animation byte."""
    c = Cursor(raw, offset); count = c.word()
    if count not in (5, 6):
        raise ValueError('OEM theme transition count changed')
    old_strings, props = d.dictionary('/$strings'), d.dictionary('/$property_dictionary')
    keep, moved = [], []
    for _ in range(count):
        head = [c.word() for _ in range(6)]
        if any(x >= len(old_strings) and x != 0xffffffff for x in head[:2]) or head[5] > 128:
            raise ValueError('OEM transition header changed')
        remaining, owned = [], []
        for _ in range(head[5]):
            record = c.read(32); fields = struct.unpack('<8I', record)
            if fields[2] >= len(old_strings) or fields[3] >= len(props):
                raise ValueError('OEM animation target changed')
            (owned if (old_strings[fields[2]], props[fields[3]]) in TARGETS[side] else remaining).append(record)
        original_head = head.copy(); head[5] = len(remaining)
        keep.append(words(*head) + b''.join(remaining))
        if owned:
            if [old_strings[x] for x in original_head[:2]] not in (['Fashion', 'Classic'], ['Classic', 'Fashion']):
                raise ValueError('Unexpected geometry animation lifecycle')
            original_head[:2] = [strings.index('Native' + old_strings[x]) for x in original_head[:2]]
            original_head[5] = len(owned)
            moved.append(words(*original_head) + b''.join(owned))
    return words(count) + b''.join(keep), words(len(moved)) + b''.join(moved), c.offset


def build_kzb(original):
    if digest(original) != KZB_SHA:
        raise ValueError('Exact reviewed RWVG KZB required')
    d = KzbDirectory(original)
    strings, props = d.dictionary('/$strings'), d.dictionary('/$property_dictionary')
    changes, native, full = {}, {}, {}
    raw = d.read('/State Managers/DCLayout'); group = first_group(d, raw)
    assert raw[:16] + encode_group(group) + raw[group['end']:] == raw
    wanted = {(PREFIX + side + '/' + p, k) for side in TARGETS for p, k in TARGETS[side]}
    removed = []
    for _, value, nodes in group['states']:
        for path, values, _ in nodes:
            keep = []
            for k, v in values:
                identity = strings[path], props[k]
                if value == 1 and identity in wanted:
                    full[identity] = v; removed.append(identity)
                else:
                    keep.append((k, v))
            values[:] = keep
    if len(removed) != 5 or set(full) != wanted:
        raise ValueError('DCFull conflict set changed')
    changes['/State Managers/DCLayout'] = raw[:16] + encode_group(group) + raw[group['end']:]
    for name in (KEY, 'DCGeometry', 'NativeNull', 'NativeFashion', 'NativeClassic', 'Full'):
        if name not in strings:
            strings.append(name)
    if KEY in props:
        raise ValueError('Gauge key already registered')
    props.append(KEY)
    changes['/$strings'], changes['/$property_dictionary'] = dictionary(strings), dictionary(props)
    custom = d.read('/$property_types')
    changes['/$property_types'] = words(struct.unpack_from('<I', custom)[0] + 1) + custom[4:] + words(strings.index(KEY), 1, 0, 1, 0)
    for side, name in MANAGERS.items():
        raw = d.read(name); g = first_group(d, raw)
        if d.dictionary('/$property_dictionary')[g['control']] != 'state':
            if side != 'Power' or struct.unpack_from('<I', raw, g['end'])[0] != 0:
                raise ValueError('Unexpected preceding OEM group/transition')
            g = first_group(d, raw, g['end'] + 4)
        assert raw[:g['begin']] + encode_group(g) + raw[g['end']:] == raw
        assert [value for _, value, _ in g['states']] == [0, 1, 9]
        assert d.dictionary('/$property_dictionary')[g['control']] == 'state'
        moved = {0: [], 1: [], 9: []}
        for _, value, nodes in g['states']:
            for path, values, _ in nodes:
                keep = []
                for k, v in values:
                    if (strings[path], props[k]) in TARGETS[side]:
                        moved[value].append((strings[path], props[k], v))
                    else:
                        keep.append((k, v))
                values[:] = keep
        if moved[0] or any(len(moved[v]) != len(TARGETS[side]) for v in (1, 9)):
            raise ValueError('OEM geometry variants changed')
        native[side] = moved
        rules = [(p, k, full[(PREFIX + side + '/' + p, k)]) for p, k in TARGETS[side]]
        transitions, geometry_transitions, end = migrate_transitions(d, raw, g['end'], side, strings)
        geometry = encode_manager(strings, props, KEY,
            [('NativeNull', 0, []), ('NativeFashion', 1, moved[1]), ('NativeClassic', 9, moved[9]), ('Full', 100, rules)])[16:-4] + geometry_transitions
        geometry = words(strings.index('DCGeometry')) + geometry[4:]
        changes[name] = raw[:12] + words(g['groups'] + 1) + geometry + raw[16:g['begin']] + encode_group(g) + transitions + raw[end:]
    output = repack(d, changes, {})
    after = KzbDirectory(output)
    for name in d.records:
        if name not in changes and after.read(name) != d.read(name):
            raise ValueError('Unrelated KZB resource changed: ' + name)
    return output, dict(input_sha256=digest(original), output_sha256=digest(output),
        changed_resources=list(changes), single_owner_properties=5, theme_states=[0, 1, 9],
        original_later_groups_preserved=True, non_geometry_animations_byte_exact=True,
        geometry_animations_migrated_to_native_states=True), native, full


def assemble_helper(native, full, find, nav):
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    source = SOURCE.read_text('utf-8').replace('BG_FIND', hex(find)).replace('NAV_TICK', hex(nav))
    for side in ('Power', 'Speed'):
        source += '\ngauge_' + side.lower() + '_values:\n'
        rows = [native[side][1], native[side][9],
                [(p, k, full[(PREFIX + side + '/' + p, k)]) for p, k in TARGETS[side]]]
        for row in rows:
            values = {(p, k): v for p, k, v in row}
            raw = b''.join(values[(p, k)] for p, k in TARGETS[side] if k == TRANSFORM)
            raw += values.get(('SpeedShow/SpeedShowText', ALIGN), words(0))
            assert len(raw) == 44
            source += '.word ' + ','.join(hex(x) for x in struct.unpack('<11I', raw)) + '\n'
    source = re.sub(r'(?m)//.*$', '', source)
    externals = []
    def defer(m):
        label = 'gauge_external_' + str(len(externals)); externals.append((label, m[1], int(m[2], 16)))
        return label + ':\nnop.w'
    source = re.sub(r'(?m)^(bl|blx) #0x([0-9a-f]+)\s*$', defer, source)
    names = ('gauge_entry', 'gauge_tick', 'gauge_side', 'gauge_srt_matches', 'gauge_power_values', 'gauge_speed_values') + tuple(x[0] for x in externals)
    assembly = source + '\n.balign 4\n' + '\n'.join('.word ' + n for n in names)
    encoded, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(assembly, addr=CODE)
    if encoded is None:
        raise ValueError('Gauge assembly failed')
    encoded = bytes(encoded); labels = dict(zip(names, struct.unpack('<' + 'I' * len(names), encoded[-4*len(names):])))
    raw = bytearray(encoded[:-4*len(names)])
    for name, instruction, target in externals:
        at = labels[name]
        b, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f'{instruction} #{target}', addr=at)
        ins = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(bytes(b), at))
        if len(b) != 4 or len(ins) != 1 or int(ins[0].op_str.removeprefix('#'), 16) != target:
            raise ValueError('Gauge branch relaxation mismatch')
        raw[at-CODE:at-CODE+4] = bytes(b)
    if len(raw) > CAPACITY:
        raise ValueError('Gauge helper exceeds owned slot')
    return bytes(raw), labels


def build_pair(hmi, kzb, receiver):
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB
    from elftools.elf.elffile import ELFFile
    from axes_rim_layout import AxesSource
    from background_poc_native import compile_thumb
    from ref2_noauth_native import Patch
    if digest(hmi) != HMI_SHA:
        raise ValueError('Exact RWVG HMI required')
    output, report, native, full = build_kzb(kzb)
    _, old_labels, _ = compile_thumb(AxesSource())
    patch = Patch(hmi, HMI_SHA)
    site = old_labels['bg_done']; old = hmi[patch.offset(site, 4):patch.offset(site, 4)+4]
    ins = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(old, site))
    if len(ins) != 1 or ins[0].mnemonic != 'bl':
        raise ValueError('Original nav call changed')
    nav = int(ins[0].op_str.removeprefix('#'), 16)
    code, labels = assemble_helper(native, full, old_labels['bg_find'], nav)
    if hmi[patch.offset(CODE, CAPACITY):patch.offset(CODE, CAPACITY)+CAPACITY] != bytes(CAPACITY):
        raise ValueError('Owned prefix slot occupied')
    patch.put('single_owner_gauge_helper', CODE, code, expected=bytes(len(code)))
    branch, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f'bl #{CODE}', addr=site)
    patch.put('render_seqlock_gauge_call', site, bytes(branch), expected=old)
    patch.put('RWVH_geometry_capability', 0x086a2a3c, bytes.fromhex('45f24160'), expected=bytes.fromhex('45f24060'))
    hmi_out, ranges = patch.finish()
    a, b = ELFFile(io.BytesIO(hmi)), ELFFile(io.BytesIO(hmi_out))
    assert a.header == b.header and [dict(s.header) for s in a.iter_segments()] == [dict(s.header) for s in b.iter_segments()]
    rp = Patch(receiver, '450f9fdd7af816d7de90cfbb99767252f940fe3100d64e88b3f447bdfe424d4b')
    rp.arm('RWVH_geometry_pair', 0x108b14, 4, 'movw r3, #0x5641')
    rx, rx_ranges = rp.finish()
    report.update(hmi_input_sha256=digest(hmi), hmi_output_sha256=digest(hmi_out), receiver_output_sha256=digest(rx),
        helper_bytes=len(code), helper_capacity=CAPACITY, helper_labels=labels, hook=site,
        original_nav_tick=nav, bg_find=old_labels['bg_find'], hmi_patches=ranges, receiver_patches=rx_ranges,
        capability=CAPABILITY, elf_headers_unchanged=True, real_kanzi_executed=False)
    return hmi_out, output, rx, report
