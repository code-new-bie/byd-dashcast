"""Read back native warning resources, run the SRT matrix routine and the emitted control.

Requires an explicitly supplied reviewed KZB/HMI pair. The original SRT routine, the
direct-child lookup, the int getter wrapper and the new Thumb execute; the Kanzi tree,
property storage and state application are controlled doubles. Not a complete Kanzi,
QNX Screen or vehicle test.
"""
import argparse
import io
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'analysis'))
from analyze_kzb_hmi import KzbDirectory, custom_types, node_head
from navigation_layout import digest
from theme_geometry import first_group
from warning_perimeter import (build, build_hmi, heads, native_image_size, SLOTS, ALIASES, LIGHTS, FITTED, TEXT_SLOTS,
                               STYLES, NATIVE_MANAGER, CONTROL, GEOMETRY, TRANSFORM, LAYOUT, ORIGIN, HMI_SHA,
                               ADAS_MANAGER, SMALL_ADAS_MANAGER, ADAS_FULL, SMALL_ADAS_FULL)

# What the OEM draws around the video in full screen (panel pixels: x, y, width, height), measured from the
# node positions and the cluster font: the clock with its am/pm word, the EV/HEV lamp, the gear, the drive and
# terrain mode lamps, the compass, the outside temperature (right-aligned, three characters at most), and the
# bottom row of power, battery/fuel/range and speed/mileage. No slot may overlap any of them.
PROTECTED = [(456, 68, 200, 58), (800, 84, 82, 28), (915, 64, 90, 66), (1040, 73, 154, 46), (1221, 70, 96, 42),
             (1356, 80, 110, 34), (150, 555, 400, 115), (550, 555, 810, 97), (1360, 550, 415, 145)]


def decoded(raw, kind):
    return struct.unpack('<' + ('f' if kind == 'float' else 'i'), raw)[0]


def read_groups(d):
    raw = d.read(NATIVE_MANAGER)
    props, strings = d.dictionary('/$property_dictionary'), d.dictionary('/$strings')
    groups, at = [], 16
    for _ in range(struct.unpack_from('<I', raw, 12)[0]):
        g = first_group(d, raw, at)
        groups.append(g)
        at = g['end']
        transitions = struct.unpack_from('<I', raw, at)[0]
        at += 4
        for _ in range(transitions):
            header = struct.unpack_from('<6I', raw, at)
            at += 24 + 32 * header[5]
        assert at <= len(raw)
    assert at == len(raw)
    owned = groups[-1]
    assert strings[owned['name']] == 'DCWarningPositions' and props[owned['control']] == CONTROL
    assert len(groups) == 4
    result = {}
    for _, value, nodes in owned['states']:
        values = {}
        for path, properties, tail in nodes:
            name = strings[path]
            assert name not in values and tail == bytes(12)
            values[name] = {props[k]: v for k, v in properties}
        result[value] = values
    assert sorted(result) == [0, 1]
    assert result[0] == {} and len(result[1]) == len(set(SLOTS) | set(ALIASES) | set(TEXT_SLOTS))
    return result, raw


class NativeMatrices:
    """Original 122-byte SRT routine; only QNX's external sin/cos thunk is a double."""
    def __init__(self, hmi):
        from elftools.elf.elffile import ELFFile
        from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_CODE
        from unicorn import arm_const as a
        if digest(hmi) != HMI_SHA:
            raise ValueError('Exact retained 1.25.1 HMI required')
        self.a = a
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        self.uc.mem_map(0x08047000, 0x700000)
        for s in ELFFile(io.BytesIO(hmi)).iter_segments():
            if s['p_type'] == 'PT_LOAD':
                self.uc.mem_write(s['p_vaddr'], hmi[s['p_offset']:s['p_offset'] + s['p_filesz']])
        self.uc.mem_map(0x200000, 0x20000)
        self.uc.mem_map(0x300000, 0x1000)
        self.uc.reg_write(a.UC_ARM_REG_C1_C0_2, 0xf00000)
        self.uc.reg_write(a.UC_ARM_REG_FPEXC, 0x40000000)
        self.calls = 0
        def hook(uc, at, size, _):
            if at == 0x300000:
                uc.emu_stop()
            elif at == 0x080d78cc:
                # All shipped geometry is unrotated; don't pretend to test libm.
                bits = uc.reg_read(a.UC_ARM_REG_R0) | (uc.reg_read(a.UC_ARM_REG_R1) << 32)
                angle = struct.unpack('<d', struct.pack('<Q', bits))[0]
                assert angle == 0.
                phase = uc.reg_read(a.UC_ARM_REG_R2)
                assert phase in (0, 1)
                lo, hi = struct.unpack('<2I', struct.pack('<d', float(phase)))
                uc.reg_write(a.UC_ARM_REG_R0, lo)
                uc.reg_write(a.UC_ARM_REG_R1, hi)
                uc.reg_write(a.UC_ARM_REG_PC, uc.reg_read(a.UC_ARM_REG_LR))
        self.uc.hook_add(UC_HOOK_CODE, hook)

    def matrix(self, raw):
        a, u = self.a, self.uc
        sx, sy, rotation, x, y = struct.unpack('<5I', raw)
        u.reg_write(a.UC_ARM_REG_R0, 0x200000)
        u.reg_write(a.UC_ARM_REG_R1, sx)
        u.reg_write(a.UC_ARM_REG_R2, sy)
        u.reg_write(a.UC_ARM_REG_R3, rotation)
        u.reg_write(a.UC_ARM_REG_SP, 0x21f000)
        u.mem_write(0x21f000, struct.pack('<2I', x, y))
        u.reg_write(a.UC_ARM_REG_LR, 0x300001)
        u.emu_start(0x0839fc31, 0x300000, count=1000)
        assert u.reg_read(a.UC_ARM_REG_SP) == 0x21f000
        assert u.reg_read(a.UC_ARM_REG_PC) == 0x300000
        self.calls += 1
        return np.array(struct.unpack('<9f', u.mem_read(0x200000, 36))).reshape((3, 3), order='F')


def geometry(values, native):
    assert set(values) == set(GEOMETRY)
    assert values[LAYOUT] == struct.pack('<5f', 1., 1., 0., 0., 0.)
    assert values[ORIGIN] == bytes(8)
    assert decoded(values['Node.HorizontalAlignment'], 'int') == 0
    # Top. Zero is bottom in Kanzi and would put the icon below the panel; see engine_alignment().
    assert decoded(values['Node.VerticalAlignment'], 'int') == 1
    width, height = decoded(values['Node.Width'], 'float'), decoded(values['Node.Height'], 'float')
    # Native SRT conversion proves field order, scale, translation and aspect ratio.
    layout, render = native.matrix(values[LAYOUT]), native.matrix(values[TRANSFORM])
    m = layout @ render
    vertices = m @ np.array([[0, width, 0, width], [0, 0, height, height], [1, 1, 1, 1]])
    return tuple(float(v) for v in (vertices[0].min(), vertices[1].min(), vertices[0].max(), vertices[1].max()))


def engine_alignment(hmi):
    """Pin the rule the slots depend on to the engine's own code and the OEM's own data."""
    from elftools.elf.elffile import ELFFile
    elf = ELFFile(io.BytesIO(hmi))
    # Node2D::arrange: `cmp r7, #0` on VerticalAlignment, then the zero path adds available - height.
    at = next(elf.address_offsets(0x081d1d14))
    assert hmi[at:at + 10] == bytes.fromhex('002f40f0d6803aee888a')


def masks(d):
    startup = d.read('/StartupPrefab')
    order = heads(d, startup, ['BG_New', 'IETop', 'IEBottom', 'Indicator', 'Mask_Left', 'Mask_Right'])
    assert order['BG_New']['offset'] < order['IETop']['offset'] < order['IEBottom']['offset'] < order['Indicator']['offset']
    assert order['Mask_Left']['properties'] == {}
    assert order['Mask_Right']['properties'][TRANSFORM] == [-1., 1., 0., 1920., 0.]
    alpha = np.zeros((720, 1920), dtype=np.uint8)
    for i, x, y in ((1, 278, 0), (2, 0, 0), (3, 0, 136), (4, 0, 495), (5, 191, 641)):
        z = np.asarray(Image.open(io.BytesIO(d.read(f'/Resource Files/Images/mask_{i:02}.png')[20:])).convert('RGBA'))[..., 3]
        h, w = z.shape
        alpha[y:y+h, x:x+w] = np.maximum(alpha[y:y+h, x:x+w], z)
    visible = np.maximum(alpha, alpha[:, ::-1]) < 128
    apertures = {mode: np.asarray(Image.open(io.BytesIO(d.read('/Resource Files/Images/DX' + mode + 'Dark.png')[20:])).convert('RGBA'))[..., 3] == 0
                 for mode in ('Full', 'Center')}
    return visible, apertures


def run(original, hmi):
    output, report = build(original)
    before, after = KzbDirectory(original), KzbDirectory(output)
    states, raw = read_groups(after)
    native_group_bytes = before.read(NATIVE_MANAGER)
    assert raw[:12] == native_group_bytes[:12]
    assert raw[16:16 + len(native_group_bytes) - 16] == native_group_bytes[16:]
    native = NativeMatrices(hmi)
    visible, clear = masks(after)
    names = sorted(set(SLOTS) | set(ALIASES))
    text_names = sorted(TEXT_SLOTS)
    engine_alignment(hmi)
    check_tyre = heads(before, before.read('/Prefabs/Prefab_Indicator_Fashion'), ['CheckTyre'])['CheckTyre']['properties']
    assert (check_tyre['Node.HorizontalAlignment'], check_tyre['Node.VerticalAlignment']) == (0, 1)
    cases, all_bounds = [], {}
    strings = after.dictionary('/$strings')
    forbidden = {'Node.Visible', 'Node.Opacity', 'Image2D.Image', 'FontColorInherit', 'Node.StateManager'}
    for style in STYLES:
        resource = '/Prefabs/Prefab_Indicator_' + style
        old, new = before.read(resource), after.read(resource)
        old_heads = heads(before, old, names + text_names + ['content'])
        new_heads = heads(after, new, names + text_names + ['content'])
        # Prove the only node header edit is the removed old belt controller, including every opaque tail.
        belt = new_heads['SeatBelt']
        restored = new[:belt['offset']] + old[old_heads['SeatBelt']['offset']:old_heads['SeatBelt']['end_properties']] + new[belt['end_properties']:]
        assert restored == old
        assert 'Node.StateManager' not in new_heads['SeatBelt']['properties']
        # The instance keeps the OEM manager; the HMI writes the control on this very node.
        assert new_heads['content']['properties']['Node.StateManager'] == 'kzb://bic_byd_stha' + NATIVE_MANAGER
        cases.append(style + ': original native nodes/binding/action tails preserved')
        for mode in (1,):
            for name in names:
                values = states[mode][name]
                assert not forbidden.intersection(values)
                family = ALIASES.get(name, name)
                bounds = geometry(values, native)
                all_bounds[style, mode, name] = bounds
                x, y, w, h = SLOTS[family]
                eps = 2e-4
                assert bounds[0] >= x - eps and bounds[1] >= y - eps
                assert bounds[2] <= x + w + eps and bounds[3] <= y + h + eps, (style, name, bounds)
                p = old_heads[name]['properties']
                sx, sy, angle, _, _ = struct.unpack('<5f', values[TRANSFORM])
                assert sx == sy and angle == 0.
                if family in FITTED:
                    assert 0 < sx <= p.get(TRANSFORM, [1.])[0] + 1e-7
                elif family not in LIGHTS:
                    assert abs(sx - p.get(TRANSFORM, [1.])[0]) < 1e-7
                else:
                    assert abs(bounds[3] - bounds[1] - 24) < eps
                assert native_image_size(before, old_heads[name]) == (decoded(values['Node.Width'], 'float'), decoded(values['Node.Height'], 'float'))
                l, t, right, bottom = math.floor(bounds[0] + eps), math.floor(bounds[1] + eps), math.ceil(bounds[2] - eps), math.ceil(bounds[3] - eps)
                assert visible[t:bottom, l:right].all(), (style, name, 'glass')
                for aperture in clear.values():
                    assert not aperture[t:bottom, l:right].any(), (style, name, 'clear video')
                cases.append(f'{style}/{mode}/{name}: native SRT, original aspect/size, visible outside both apertures')
            for name in text_names:
                values = states[mode][name]
                assert set(values) == {LAYOUT, TRANSFORM} and not forbidden.intersection(values)
                assert values[TRANSFORM] == struct.pack('<5f', 1., 1., 0., 0., 0.)
                layout = native.matrix(values[LAYOUT])
                box_left, box_top = layout[0, 2], layout[1, 2]
                box_right = box_left + old_heads[name]['properties']['Node.Width'] * layout[0, 0]
                x, y, w, h = SLOTS['Cruise']
                assert box_left >= x - 1e-3 and box_right <= x + w + 1e-3, (style, name)
                assert (box_left, box_top) == TEXT_SLOTS[name][:2]
                cases.append(f'{style}/{mode}/{name}: text box stays inside the cruise slot')
        # Idle has no geometry modifiers to replay.
        assert states[0] == {}
        for state in (0, 1, 0):
            effective = {n: dict(old_heads[n]['properties']) for n in names + text_names}
            for n, values in states[state].items():
                effective[n].update(values)
            if state == 0:
                assert all(effective[n] == old_heads[n]['properties'] for n in names + text_names)
        cases.append(style + ': idle/projection/idle leaves latest native properties intact')
    # Different native states in an existing family keep their old shared position.
    # Different families have independent complete rectangles, including US text.
    for name, (x, y, w, h) in SLOTS.items():
        for other, (a, b, c, d) in SLOTS.items():
            if name >= other:
                continue
            assert not (x < a+c and x+w > a and y < b+d and y+h > b), (name, other)
    for name, (x, y, w, h) in SLOTS.items():
        for a, b, c, d in PROTECTED:
            assert not (x < a+c and x+w > a and y < b+d and y+h > b), (name, 'protected information')
    cases.append(f'{len(SLOTS)} complete family rectangles: no cross-family/protected-area overlap')
    # The cruise set speed is drawn over its slot's top edge: the text box must clear the clock as well.
    for name, (x, y, scale) in TEXT_SLOTS.items():
        for a, b, c, d in PROTECTED:
            assert not (x < a+c and x+100*scale > a and y < b+d and y+50*scale > b), (name, 'protected information')
    cases.append('cruise set-speed text clears the clock and the other top-bar information')
    cases.extend(unmoved_images(before, clear['Full']))
    cases.extend(adas_scene(before, after))
    assert before.read('/StartupPrefab') == after.read('/StartupPrefab')
    assert all(after.read(n) == before.read(n) for n in before.records if n not in report['changed_resources'])
    assert after.read('/State Managers/DCSafetyRail') == before.read('/State Managers/DCSafetyRail')
    types = custom_types(after)
    assert types[CONTROL] == dict(kind=1, flags=0, inherited=0, default=0) == types['state']
    assert set(after.records) == set(before.records)
    cases.append('backgrounds, masks, all original native managers and unrelated resources byte exact')
    return output, dict(status='PASS_RESOURCE_AND_NATIVE_MATRIX_NOT_KANZI_OR_VEHICLE', tests=len(cases),
                        native_matrix_calls=native.calls, cases=cases, build=report,
                        original_layer_order_verified=True, right_glass_mirror_verified=True,
                        real_layout_engine=False, vehicle=False), all_bounds


def all_heads(d, raw):
    """Every node header of a prefab, in file order (headers with the empty base name only)."""
    empty = words_of(d.dictionary('/$strings').index(''))
    at, found = 0, []
    while at + 16 <= len(raw):
        if raw[at + 4:at + 8] == empty:
            try:
                h = node_head(d, raw, at)
                if h['name'] and h['node_class'].startswith(('Kanzi.', 'Rc.')):
                    found.append(h)
                    at = h['end_properties']
                    continue
            except (ValueError, KeyError, IndexError, struct.error, UnicodeError):
                pass
        at += 1
    return found


def words_of(value):
    return struct.pack('<I', value)


def unmoved_images(d, clear):
    """An indicator image that keeps its OEM place in full screen must not be inside the video opening.

    This is the check that was missing when the speed-limit sign stayed in the video. Indicator images are
    direct children of the identity `content` node, so their own transforms are panel positions.
    """
    moved, cases = set(SLOTS) | set(ALIASES), []
    for style in STYLES:
        kept = 0
        for h in all_heads(d, d.read('/Prefabs/Prefab_Indicator_' + style)):
            if h['node_class'] != 'Kanzi.Image2D' or h['name'] in moved or 'Image2D.Image' not in h['properties']:
                continue
            try:
                width, height = native_image_size(d, h)
            except (KeyError, ValueError, OSError, struct.error):
                continue
            p = h['properties']
            render, layout = p.get(TRANSFORM, [1., 1., 0., 0., 0.]), p.get(LAYOUT, [1., 1., 0., 0., 0.])
            left, top = layout[3] + render[3] * layout[0], layout[4] + render[4] * layout[1]
            scale = render[0] * layout[0]
            box = clear[max(0, math.floor(top)):math.ceil(top + height * scale), max(0, math.floor(left)):math.ceil(left + width * scale)]
            assert not box.any(), (style, h['name'], 'keeps its OEM place inside the full-screen video')
            kept += 1
        assert kept >= 10
        cases.append(f'{style}: {kept} images keep their OEM place, none inside the full-screen video')
    return cases


def adas_scene(before, after):
    """Full screen hides the driver-assistance scene; idle and the small window keep every rule byte."""
    strings, props = after.dictionary('/$strings'), after.dictionary('/$property_dictionary')
    cases = []
    for manager, index, rules in ((ADAS_MANAGER, 0, ADAS_FULL), (SMALL_ADAS_MANAGER, 1, SMALL_ADAS_FULL)):
        old, new = before.read(manager), after.read(manager)
        at_old = at_new = 16
        for group in range(index + 1):
            a, b = first_group(before, old, at_old), first_group(after, new, at_new)
            if group < index:
                assert old[at_old:a['end'] + 4] == new[at_new:b['end'] + 4]
                at_old, at_new = a['end'] + 4, b['end'] + 4
        assert old[:at_old] == new[:at_new] and old[a['end']:] == new[b['end']:]
        assert [s[:2] for s in a['states']] == [s[:2] for s in b['states']]
        assert a['states'][0] == b['states'][0] and a['states'][2] == b['states'][2]
        full = [(strings[path], props[values[0][0]], values[0][1]) for path, values, tail in b['states'][1][2]
                if len(values) == 1 and tail == bytes(12)]
        assert full == rules
        hidden = {path for path, key, value in full if key == 'Node.Visible' and value == bytes(4)}
        assert hidden >= ({'Lane', 'ACCSys', 'LaneDeparture'} if index == 0 else {'ACCSys', 'LaneDistance', 'ADAS_SmallBG'})
        assert not any(key == TRANSFORM for _, key, _ in full)
        cases.append(manager.rsplit('/', 1)[1] + ': full screen hides the scene, idle and small window byte exact')
    return cases


class ControlBench:
    """Runs the emitted Thumb and the HMI's own direct-child lookup on a controlled tree."""
    STOP, TYPE_METHOD, VTABLE, DESCRIPTOR, BG, SHARED = 0x300000, 0x210500, 0x210400, 0x210000, 0x233000, 0x232000

    def __init__(self, binary, control):
        from elftools.elf.elffile import ELFFile
        from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_CODE
        from unicorn import arm_const as a
        self.a, self.control, self.u32 = a, control, lambda n: struct.pack('<I', n & 0xffffffff)
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        self.uc.mem_map(0x08047000, 0x700000)
        for s in ELFFile(io.BytesIO(binary)).iter_segments():
            if s['p_type'] == 'PT_LOAD':
                self.uc.mem_write(s['p_vaddr'], binary[s['p_offset']:s['p_offset'] + s['p_filesz']])
        self.uc.mem_map(0x200000, 0x60000); self.uc.mem_map(self.STOP, 0x1000); self.uc.mem_map(0x400000, 0x10000)
        self.uc.mem_write(self.DESCRIPTOR, self.u32(self.VTABLE))
        self.uc.mem_write(self.VTABLE + 8, self.u32(self.TYPE_METHOD | 1))
        self.uc.mem_write(self.TYPE_METHOD, bytes.fromhex('7047'))
        self.uc.mem_write(self.BG, self.u32(self.SHARED))
        self.names, self.children, self.values, self.writes, self.navs, self.next = {}, {}, {}, [], 0, 0x201000
        self.descriptor, self.int_type, self.ranges = 'ok', 1, {}
        self.uc.hook_add(UC_HOOK_CODE, self.hook)

    def node(self, name):
        self.next += 0x100
        self.names[self.next], self.children[self.next] = name, []
        return self.next

    def tree(self, style='Fashion'):
        self.root = self.node('Screen')
        parent = self.root
        self.path = {}
        for name in ('RootPage', 'Content', 'Indicator', 'Prefab View 2D'):
            child = self.node(name)
            self.children[parent].append(child)
            self.path[name] = child
            parent = child
        self.view = parent
        return self.instance(style)

    def instance(self, style):
        root = self.node('Prefab_Indicator_' + style)
        content = self.node('content')
        self.children[root].append(content)
        self.children[self.view][:] = [root]
        return content

    def ret(self, value):
        self.uc.reg_write(self.a.UC_ARM_REG_R0, value)
        self.uc.reg_write(self.a.UC_ARM_REG_PC, self.uc.reg_read(self.a.UC_ARM_REG_LR))

    def hook(self, uc, at, size, _):
        a = self.a
        if at == self.STOP:
            uc.emu_stop(); return
        r0, r1, r2 = (uc.reg_read(x) for x in (a.UC_ARM_REG_R0, a.UC_ARM_REG_R1, a.UC_ARM_REG_R2))
        if at in (0x083edafc, self.control['bg_find'], 0x0814c210, 0x08138af4, self.control['original_nav_tick']):
            assert uc.reg_read(a.UC_ARM_REG_SP) % 8 == 0, ('stack alignment', hex(at))
        if at == self.control['original_nav_tick']:
            assert uc.reg_read(a.UC_ARM_REG_R4) == self.BG
            self.navs += 1
            # The real nav_tick is free to clobber every caller-saved register.
            for reg in (a.UC_ARM_REG_R1, a.UC_ARM_REG_R2, a.UC_ARM_REG_R3, a.UC_ARM_REG_R12):
                uc.reg_write(reg, 0xdeadbee0)
            self.ret(0)
        elif at == 0x083edafc:
            assert bytes(uc.mem_read(r0, r1)).decode() == CONTROL
            self.ret(0 if self.descriptor == 'missing' else self.DESCRIPTOR)
        elif at == self.TYPE_METHOD:
            self.ret(self.int_type)
        elif at in (0x081d0410, 0x081d0418):
            self.ret(self.vector(r0)[int(at == 0x081d0418)])
        elif at == 0x081c8d1c:
            self.ret(int(self.names[r0] == bytes(uc.mem_read(r1, r2)).decode()))
        elif at == 0x08138af4:
            assert r1 == self.DESCRIPTOR
            uc.mem_write(0x230000, self.u32(self.values.get(r0, 0)))
            self.ret(0x230000)
        elif at == 0x0814c210:
            assert int.from_bytes(uc.mem_read(r1, 4), 'little') == self.DESCRIPTOR
            self.writes.append((r0 - 4, r2))
            self.order.append('write')
            self.values[r0 - 4] = r2
            self.ret(0)

    def vector(self, node):
        if node in self.ranges:
            return self.ranges[node]
        index = list(self.children).index(node)
        begin = 0x250000 + index * 0x100
        self.uc.mem_write(begin, b''.join(self.u32(n) + self.u32(0) for n in self.children[node]))
        return begin, begin + len(self.children[node]) * 8

    def frame(self, active):
        a, u = self.a, self.uc
        saved = (a.UC_ARM_REG_R4, a.UC_ARM_REG_R5, a.UC_ARM_REG_R6, a.UC_ARM_REG_R7, a.UC_ARM_REG_R8,
                 a.UC_ARM_REG_R9, a.UC_ARM_REG_R10, a.UC_ARM_REG_R11)
        for i, reg in enumerate(saved):
            u.reg_write(reg, 0x123000 + i)
        before = len(self.writes)
        self.order = []
        u.reg_write(a.UC_ARM_REG_SP, 0x40f000); u.reg_write(a.UC_ARM_REG_LR, self.STOP | 1)
        u.reg_write(a.UC_ARM_REG_R0, self.root); u.reg_write(a.UC_ARM_REG_R1, active)
        u.emu_start(self.control['helper_labels']['warning_tick'] | 1, self.STOP, count=200000)
        assert u.reg_read(a.UC_ARM_REG_PC) == self.STOP and u.reg_read(a.UC_ARM_REG_SP) == 0x40f000
        assert [u.reg_read(reg) for reg in saved] == [0x123000 + i for i in range(8)]
        return self.writes[before:]

    def entry(self, active, profile=0):
        """The real call chain: gauge_entry -> warning_entry -> original nav_tick -> control."""
        a, u = self.a, self.uc
        u.mem_write(self.BG + 36, self.u32(active))
        u.mem_write(self.BG + 104, self.u32(profile))
        u.mem_write(self.BG + 88, self.u32(0))  # gauge publication is covered by test_theme_geometry
        before, navs = len(self.writes), self.navs
        self.order = []
        u.reg_write(a.UC_ARM_REG_R4, self.BG); u.reg_write(a.UC_ARM_REG_R5, self.root); u.reg_write(a.UC_ARM_REG_R6, 0x777)
        u.reg_write(a.UC_ARM_REG_SP, 0x40f000); u.reg_write(a.UC_ARM_REG_LR, self.STOP | 1)
        u.emu_start(0x08047880 | 1, self.STOP, count=200000)
        assert self.navs == navs + 1
        assert u.reg_read(a.UC_ARM_REG_PC) == self.STOP and u.reg_read(a.UC_ARM_REG_SP) == 0x40f000
        assert (u.reg_read(a.UC_ARM_REG_R4), u.reg_read(a.UC_ARM_REG_R5), u.reg_read(a.UC_ARM_REG_R6)) == (self.BG, self.root, 0x777)
        return self.writes[before:]


def run_control(hmi, toc):
    binary, control = build_hmi(hmi)
    toc_paths = set(toc.splitlines())
    assert 'Screens/Screen/RootPage/Content/Indicator/Prefab View 2D' in toc_paths
    for style in STYLES:
        assert f'Prefabs/Prefab_Indicator_{style}/Prefab_Indicator_{style}/content' in toc_paths
    # Only the new call site and the previously empty tail of the owned slot differ.
    from elftools.elf.elffile import ELFFile
    allowed = [(row['offset'], row['offset'] + row['bytes']) for row in control['hmi_patches']['patches']]
    assert len(binary) == len(hmi) and len(allowed) == 2
    assert all(any(a <= i < b for a, b in allowed) for i in range(len(hmi)) if hmi[i] != binary[i])
    cases = []
    bench = lambda: ControlBench(binary, control)
    for style in STYLES:
        b = bench(); content = b.tree(style)
        assert b.frame(1) == [(content, 1)]
        assert b.frame(1) == [] and b.frame(1) == [], 'an equal value is not written again'
        assert b.frame(0) == [(content, 0)]
        assert b.frame(0) == []
        cases.append(style + ': session on writes 1 on the instance content once; off writes 0 once')
    b = bench(); content = b.tree()
    assert b.frame(0) == [] and content not in b.values
    cases.append('idle never touches an instance that has no local value')
    b = bench(); content = b.tree()
    assert b.frame(5) == [(content, 1)]
    cases.append('any non-zero session flag is normalised to the single projection state')
    b = bench(); first = b.tree('Fashion')
    assert b.frame(1) == [(first, 1)]
    second = b.instance('Classic')
    assert b.frame(1) == [(second, 1)], 'a style change gets its value on the new instance only'
    third = b.instance('ACC')
    assert b.frame(1) == [(third, 1)] and b.frame(1) == []
    assert b.frame(0) == [(third, 0)] and b.values[first] == 1 and b.values[second] == 1
    cases.append('Fashion->Classic->ACC during a session, then stop: no stale instance is touched')
    b = bench(); content = b.tree()
    assert b.frame(1) == [(content, 1)]
    b.children[b.view][:] = []
    assert b.frame(1) == []
    fresh = b.instance('Classic')
    assert b.frame(1) == [(fresh, 1)]
    cases.append('frame between the old instance leaving and the new one arriving is harmless')
    b = bench(); content = b.tree()
    old = b.node('Prefab_Indicator_ACC'); old_content = b.node('content')
    b.children[old].append(old_content); b.children[b.view].append(old)
    assert sorted(b.frame(1)) == sorted([(content, 1), (old_content, 1)])
    cases.append('two transient children are both kept in step')
    def fault(name, change):
        b = bench(); content = b.tree()
        change(b, content)
        assert b.frame(1) == [], name
        assert b.navs == 0
        cases.append('no write, clean return: ' + name)
    fault('Indicator absent', lambda b, c: b.children[b.path['Content']].clear())
    fault('Prefab View absent', lambda b, c: b.children[b.path['Indicator']].clear())
    fault('RootPage absent', lambda b, c: b.children[b.root].clear())
    fault('instance without content', lambda b, c: b.children[b.children[b.view][0]].clear())
    fault('duplicate content child', lambda b, c: b.children[b.children[b.view][0]].append(c))
    fault('descriptor missing', lambda b, c: setattr(b, 'descriptor', 'missing'))
    fault('descriptor of another type', lambda b, c: setattr(b, 'int_type', 2))
    fault('null child entry', lambda b, c: b.children[b.view].__setitem__(0, 0))
    fault('misaligned child vector', lambda b, c: b.ranges.__setitem__(b.view, (0x260000, 0x260003)))
    fault('implausibly long child vector', lambda b, c: b.ranges.__setitem__(b.view, (0x260000, 0x260000 + 2056)))
    b = bench(); content = b.tree()
    assert b.entry(1) == [(content, 1)]
    assert b.entry(1) == []
    assert b.entry(0) == [(content, 0)]
    cases.append('real gauge_entry -> warning_entry -> nav_tick chain: nav runs once per frame, r4-r6 preserved')
    b = bench(); content = b.tree()
    assert b.entry(1, profile=1) == [] and content not in b.values
    assert b.entry(1, profile=0) == [(content, 1)]
    assert b.entry(1, profile=1) == [(content, 0)]
    assert b.entry(1, profile=1) == []
    assert b.entry(1, profile=0) == [(content, 1)]
    assert b.entry(0, profile=0) == [(content, 0)]
    assert b.navs == 6
    cases.append('small window keeps the OEM positions; full <-> small switches in place, once per change')
    return binary, dict(status='PASS_THUMB_WITH_ENGINE_DOUBLE_NOT_TARGET_KANZI', tests=len(cases), cases=cases,
                        control=control, real_toc_hierarchy=True, native_direct_child_lookup_executed=True,
                        native_int_getter_wrapper_executed=True, qnx_or_vehicle=False)


def preview(d, states, bounds, output):
    """Native-asset placement preview, with synthetic grid instead of fake vehicle data."""
    visible, clear = masks(d)
    font_path = Path('C:/Windows/Fonts/msyh.ttc')
    font = ImageFont.truetype(str(font_path), 19) if font_path.exists() else ImageFont.load_default()
    names = sorted(set(SLOTS) | set(ALIASES))
    native_heads = heads(d, d.read('/Prefabs/Prefab_Indicator_Fashion'), names)
    for mode, state in (('Full', 1),):
        for theme, color in (('Dark', (25, 28, 38)), ('Light', (205, 219, 229))):
            canvas = Image.new('RGBA', (1920, 780), color + (255,))
            draw = ImageDraw.Draw(canvas)
            # Map-like grid is expressly synthetic, not an application screenshot.
            grid = Image.new('RGBA', (1920, 720), (65, 92, 110, 255) if theme == 'Dark' else (219, 234, 227, 255))
            gd = ImageDraw.Draw(grid)
            for x in range(0, 1920, 96): gd.line((x, 0, x, 720), fill=(130, 152, 160), width=1)
            for y in range(0, 720, 72): gd.line((0, y, 1920, y), fill=(130, 152, 160), width=1)
            # Both themes use the same contour; Light is an 8x3 backing swatch.
            alpha = np.asarray(Image.open(io.BytesIO(d.read('/Resource Files/Images/DX' + mode + 'Dark.png')[20:])).convert('RGBA'))[..., 3]
            grid.putalpha(Image.fromarray(255-alpha))
            canvas.alpha_composite(grid)
            # Show all unique slots as a placement stress case. Same-family variants
            # aren't drawn on top of each other and no live warning is fabricated.
            for name in SLOTS:
                head = native_heads[name]
                props = head['properties']
                texture = d.read(props['Image2D.Image'].removeprefix('kzb://bic_byd_stha'))
                url = d.dictionary('/$strings')[struct.unpack_from('<I', texture, 32)[0]]
                im = Image.open(io.BytesIO(d.read(url.removeprefix('kzb://bic_byd_stha'))[20:])).convert('RGBA')
                # OEM PNGs are premultiplied; Pillow alpha_composite needs straight.
                px = np.asarray(im, dtype=np.float32).copy(); a = px[..., 3:4]
                px[..., :3] = np.minimum(255, np.where(a > 0, px[..., :3] * 255 / np.maximum(a, 1), 0))
                im = Image.fromarray(px.astype(np.uint8), 'RGBA')
                left, top, right, bottom = bounds['Fashion', state, name]
                im = im.resize((max(1, round(right-left)), max(1, round(bottom-top))), Image.Resampling.LANCZOS)
                canvas.alpha_composite(im, (round(left), round(top)))
            dark = Image.new('RGBA', (1920, 720), (0, 0, 0, 255))
            dark.putalpha(Image.fromarray(np.where(visible, 0, 255).astype(np.uint8)))
            canvas.alpha_composite(dark)
            ImageDraw.Draw(canvas).text((30, 740), '方案位置核对：原厂图标＋成品坐标＋合成网格；非Kanzi/实车画面；行车数字未绘制', font=font, fill=(255, 160, 40))
            canvas.convert('RGB').save(output / f'placement-{mode.lower()}-{theme.lower()}.png')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('kzb', 'hmi', 'toc', 'output'):
        p.add_argument('--' + name, required=True, type=Path)
    args = p.parse_args()
    binary, report, bounds = run(args.kzb.read_bytes(), args.hmi.read_bytes())
    patched_hmi, control = run_control(args.hmi.read_bytes(), args.toc.read_text('utf-8'))
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'REPORT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (args.output / 'CONTROL_REPORT.json').write_text(json.dumps(control, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (args.output / 'bic_byd_stha.kzb').write_bytes(binary)
    (args.output / 'rc_hmi.exe').write_bytes(patched_hmi)
    preview(KzbDirectory(binary), read_groups(KzbDirectory(binary))[0], bounds, args.output)
    print(report['status'], report['tests'], 'native_matrix_calls', report['native_matrix_calls'])
    print(control['status'], control['tests'])


if __name__ == '__main__':
    main()
