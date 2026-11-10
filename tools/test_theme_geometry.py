"""Exercise emitted Thumb against a bounded property-engine double, not target Kanzi.

Requires explicit reviewed RWVG inputs; no original files or vehicle data in the repo.
Executes the actual int getter and parent getter from the HMI, and the new helper.
Engine tree lookup / property storage / state application are controlled doubles.
"""
import argparse
import io
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'analysis'))
from theme_geometry import (build_pair, first_group, KzbDirectory, KEY, TARGETS, MANAGERS,
                            TRANSFORM, ALIGN, CODE, CAPACITY, digest)


def run(hmi, original, toc, baseline=None):
    from elftools.elf.elffile import ELFFile
    from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_CODE
    from unicorn.arm_const import (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3,
        UC_ARM_REG_R4, UC_ARM_REG_R5, UC_ARM_REG_R6, UC_ARM_REG_R7, UC_ARM_REG_R8,
        UC_ARM_REG_R9, UC_ARM_REG_R10, UC_ARM_REG_R11, UC_ARM_REG_LR, UC_ARM_REG_SP,
        UC_ARM_REG_PC)
    binary, patched, report = build_pair(hmi, original)
    d = KzbDirectory(patched); strings = d.dictionary('/$strings'); props = d.dictionary('/$property_dictionary')
    rules = {}
    for side, name in MANAGERS.items():
        g = first_group(d, d.read(name))
        assert strings[g['name']] == 'DCGeometry' and props[g['control']] == KEY
        rules[side] = {value: {(strings[p], props[k]): raw for p, values, _ in targets for k, raw in values}
                       for _, value, targets in g['states']}
        assert sorted(rules[side]) == [0, 1, 9, 100]
    u32 = lambda n: struct.pack('<I', n & 0xffffffff)
    root, power, speed = 0x200000, 0x200200, 0x200400
    leaves = {'PowerShowText': 0x200800, 'Power_Unit': 0x200a00,
              'SpeedShowText': 0x200c00, 'Km_Per_Hour': 0x200e00}
    owners = {'Power': power, 'Speed': speed}
    stype, gtype, trtype, atype, vtable, type_method = 0x210000, 0x210100, 0x210200, 0x210300, 0x210400, 0x210500
    storage = {node: 0x220000 + i * 0x100 for i, node in enumerate(leaves.values())}
    stop = 0x300000
    outcomes = []
    # Join the actual Screen instance to its original expanded prefab TOC paths.
    # The native bg_find routine is direct-only: never flatten these levels.
    toc_paths = set(toc.splitlines())
    instance = 'Screens/Screen/RootPage/Content/Cluster/content/Prefab_Cluster_Power_Speed'
    prefab = 'Prefabs/Prefab_Cluster_Power_Speed/Prefab_Cluster_Power_Speed/'
    assert instance in toc_paths
    nodes = {('Screen',): root}
    names, children = {root: 'Screen'}, {}
    for side in TARGETS:
        for path, _ in TARGETS[side]:
            assert prefab + 'content/' + side + '/' + path in toc_paths
            parts = (instance + '/content/' + side + '/' + path).split('/')[1:]
            for depth in range(2, len(parts) + 1):
                key = tuple(parts[:depth])
                if key in nodes:
                    continue
                name = key[-1]
                address = leaves.get(name, owners.get(name, 0x204000 + len(nodes) * 0x100))
                nodes[key] = address
                names[address] = name
                children.setdefault(nodes[key[:-1]], []).append(address)
    assert all(owners[s] not in children.get(nodes[('Screen','RootPage','Content','Cluster')], []) for s in owners)
    # field/ABI and lifecycle branches, including independent left/right themes.
    scenarios = [('full-fashion', 1, 1, 1, None), ('full-classic', 1, 9, 1, None),
        ('full-independent', 1, 1, 9, None), ('full-null', 1, 0, 0, None),
        ('restore-latest-classic', 0, 9, 9, None), ('center-fashion', 2, 1, 1, None),
        ('center-classic', 2, 9, 9, None), ('idle-no-tree', 0, 1, 1, 'missing'),
        ('full-no-tree', 1, 1, 1, 'missing'), ('foreign-leaf-parent', 1, 1, 1, 'parent'),
        ('missing-descriptor', 1, 1, 1, 'descriptor'), ('wrong-descriptor-type', 1, 1, 1, 'type'),
        ('missing-effective-value', 1, 1, 1, 'no-value'), ('wrong-alignment', 1, 1, 1, 'alignment')]
    scenarios += [(f'changed-field-{i}', 1, 1, 1, ('field', i)) for i in range(20)]
    scenarios += [(f'nonfinite-{bits:x}', 1, 1, 1, ('nonfinite', bits)) for bits in (0x7fc00000, 0x7f800000, 0xff800000)]
    scenarios += [('eight-ulp', 1, 1, 1, ('ulp', 8)), ('nine-ulp', 1, 1, 1, ('ulp', 9))]
    scenarios += [('missing-prefab', 1, 1, 1, 'prefab'), ('missing-show', 1, 1, 1, 'show'),
                  ('duplicate-owner', 1, 1, 1, 'duplicate')]
    if baseline is not None:
        scenarios += [('legacy-full-real-tree', 1, 1, 1, 'legacy'), ('legacy-center-real-tree', 2, 1, 1, 'legacy')]
    for name, mode, left_theme, right_theme, fault in scenarios:
        uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        image, labels = (baseline[0], baseline[1]) if fault == 'legacy' else (binary, report['helper_labels'])
        elf = ELFFile(io.BytesIO(image))
        uc.mem_map(0x08047000, 0x700000)
        for s in elf.iter_segments():
            if s['p_type'] == 'PT_LOAD':
                uc.mem_write(s['p_vaddr'], image[s['p_offset']:s['p_offset']+s['p_filesz']])
        uc.mem_map(0x200000, 0x60000); uc.mem_map(stop, 0x1000)
        uc.mem_map(0x400000, 0x10000)
        for t in (stype, gtype, trtype, atype): uc.mem_write(t, u32(vtable))
        uc.mem_write(vtable+8, u32(type_method | 1))
        uc.mem_write(type_method, bytes.fromhex('7047'))
        uc.mem_write(0x086cf6bc, u32(trtype)); uc.mem_write(0x086cf63c, u32(atype))
        current = {power: left_theme, speed: right_theme}; keys = {}; align = {}
        vectors = {}
        for i, (key, address) in enumerate(nodes.items()):
            if len(key) > 1:
                uc.mem_write(address+0x2c, u32(nodes[key[:-1]]))
            direct = list(children.get(address, []))
            if fault == 'missing' and address == root: direct = []
            if fault == 'prefab': direct = [n for n in direct if names[n] != 'Prefab_Cluster_Power_Speed']
            if fault == 'show': direct = [n for n in direct if names[n] != 'PowerShow']
            if fault == 'duplicate' and power in direct: direct.append(power)
            begin = 0x250000+i*0x100
            uc.mem_write(begin, b''.join(u32(n)+u32(0) for n in direct))
            vectors[address] = (begin, begin+len(direct)*8)
        if fault == 'parent': uc.mem_write(leaves['PowerShowText']+0x2c, u32(root))
        calls = []
        shared, bg = 0x232000, 0x233000
        base_status = 0x4e4c4301 if mode else 0x4e4c4300
        uc.mem_write(bg, u32(shared)); uc.mem_write(bg+36, u32(int(mode != 0)))
        uc.mem_write(bg+104, u32(int(mode == 2))); uc.mem_write(bg+88, u32(1))
        def ret(value):
            uc.reg_write(UC_ARM_REG_R0, value)
            uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
        def hook(uc, at, size, _):
            if at == stop: uc.emu_stop(); return
            r0, r1, r2 = (uc.reg_read(x) for x in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2))
            external = at in (0x083edafc, report['bg_find'], 0x0814c210, 0x08138af4,
                             0x083eefe4, type_method, 0x081d0410, 0x081d0418, 0x081c8d1c)
            if external:
                assert uc.reg_read(UC_ARM_REG_SP) % 8 == 0, (name, hex(at), 'stack alignment')
                calls.append(hex(at))
            if at == report['original_nav_tick']:
                assert uc.reg_read(UC_ARM_REG_R4) == bg and uc.reg_read(UC_ARM_REG_R5) == root
                uc.mem_write(shared+116, u32(base_status)); ret(0)
            elif at == 0x083edafc:
                text = bytes(uc.mem_read(r0, r1)).decode()
                ret(0 if fault == 'descriptor' else {'state': stype, KEY: gtype}[text])
            elif at == type_method: ret(2 if fault == 'type' else 1)
            elif at in (0x081d0410, 0x081d0418):
                ret(vectors[r0][int(at == 0x081d0418)])
            elif at == 0x081c8d1c:
                text = bytes(uc.mem_read(r1, r2)).decode()
                ret(int(names[r0] == text))
            elif at == 0x08138af4:  # Real Node::getProperty<int> wrapper calls this engine lookup.
                pointer = 0x230000
                if r1 == stype: uc.mem_write(pointer, u32(current[r0]))
                elif r1 == atype: uc.mem_write(pointer, u32(align.get(r0, 0)))
                else: raise AssertionError(('unexpected int type', r1))
                ret(pointer)
            elif at == 0x0814c210:
                owner = r0-4; assert int.from_bytes(uc.mem_read(r1,4),'little') == gtype
                side = 'Power' if owner == power else 'Speed'
                if keys.get(side) == r2:
                    ret(0); return  # The real Kanzi equal-state path does not reapply rules.
                keys[side] = r2
                for (path, prop), value in rules[side][r2].items():
                    node = leaves[path.rsplit('/',1)[-1]]
                    if prop == TRANSFORM:
                        value = bytearray(value)
                        slot = list(leaves).index(path.rsplit('/',1)[-1])
                        if isinstance(fault, tuple) and fault[0] == 'field' and fault[1]//5 == slot:
                            struct.pack_into('<f', value, fault[1]%5*4, 99999.)
                        if slot == 0 and isinstance(fault, tuple) and fault[0] in ('nonfinite','ulp'):
                            bits = fault[1] if fault[0] == 'nonfinite' else struct.unpack_from('<I',value)[0]+fault[1]
                            struct.pack_into('<I',value,0,bits)
                        uc.mem_write(storage[node],bytes(value))
                    else: align[node] = 2 if fault == 'alignment' else int.from_bytes(value,'little')
                ret(0)
            elif at == 0x083eefe4:
                assert r1 == trtype
                ret(0 if fault == 'no-value' else storage[r0-4])
        uc.hook_add(UC_HOOK_CODE, hook)
        saved_regs = (UC_ARM_REG_R4, UC_ARM_REG_R5, UC_ARM_REG_R6, UC_ARM_REG_R7, UC_ARM_REG_R8, UC_ARM_REG_R9, UC_ARM_REG_R10, UC_ARM_REG_R11)
        for i, reg in enumerate(saved_regs): uc.reg_write(reg, 0x123000+i)
        uc.reg_write(UC_ARM_REG_SP, 0x40f000); uc.reg_write(UC_ARM_REG_LR, stop|1)
        uc.reg_write(UC_ARM_REG_R0, root); uc.reg_write(UC_ARM_REG_R1, mode)
        uc.emu_start(labels['gauge_tick']|1, stop, count=20000)
        result = uc.reg_read(UC_ARM_REG_R0)
        expected = fault is None or (fault == 'missing' and mode == 0) or fault == ('ulp',8)
        assert result == int(expected), (name,result,expected,calls[-12:])
        assert uc.reg_read(UC_ARM_REG_PC) == stop, (name,'did not return')
        assert uc.reg_read(UC_ARM_REG_SP) == 0x40f000
        assert [uc.reg_read(reg) for reg in saved_regs] == [0x123000+i for i in range(8)]
        if expected and fault != 'missing':
            assert keys == {'Power':100 if mode==1 else left_theme,'Speed':100 if mode==1 else right_theme}
        outcomes.append({'case':name,'result':result,'expected':int(expected),'engine_calls':len(calls)})
        # Exercise the actual new call-site wrapper with the legacy controller as
        # a double; only the final shared-memory status may change on mismatch.
        for i, reg in enumerate(saved_regs): uc.reg_write(reg, 0x123000+i)
        uc.reg_write(UC_ARM_REG_R4, bg); uc.reg_write(UC_ARM_REG_R5, root)
        uc.reg_write(UC_ARM_REG_SP, 0x40f000); uc.reg_write(UC_ARM_REG_LR, stop|1)
        uc.emu_start(CODE|1, stop, count=20000)
        status = int.from_bytes(uc.mem_read(shared+116,4),'little')
        assert status == (base_status if expected else 0x4e4c4302), (name,'wrapper status',hex(status))
        assert uc.reg_read(UC_ARM_REG_SP) == 0x40f000
        expected_regs = [0x123000+i for i in range(8)]; expected_regs[:2] = [bg, root]
        assert [uc.reg_read(reg) for reg in saved_regs] == expected_regs
        outcomes.append({'case':name+'-wrapper','status':hex(status),'expected_match':expected})
        if mode == 1 and fault is None:
            # The reported failure sequence, without recreating the emulator/scene:
            # Full -> another OEM theme -> same Full key -> stop -> latest OEM theme.
            current[power] = 9 if left_theme != 9 else 1
            current[speed] = 1 if right_theme == 9 else 9
            for phase in (1, 0):
                uc.reg_write(UC_ARM_REG_R0, root); uc.reg_write(UC_ARM_REG_R1, phase)
                uc.reg_write(UC_ARM_REG_SP, 0x40f000); uc.reg_write(UC_ARM_REG_LR, stop|1)
                uc.emu_start(report['helper_labels']['gauge_tick']|1,stop,count=20000)
                assert uc.reg_read(UC_ARM_REG_R0) == 1
                for side, owner in owners.items():
                    key = 100 if phase else current[owner]
                    assert keys[side] == key
                    for (path, prop), value in rules[side][key].items():
                        node=leaves[path.rsplit('/',1)[-1]]
                        actual=bytes(uc.mem_read(storage[node],20)) if prop==TRANSFORM else u32(align[node])
                        assert actual==value,(name,phase,side,path)
            outcomes.append({'case':name+'-theme-switch-and-stop','same_state_short_circuit':True,'restored_latest':True})
    return dict(status='PASS_THUMB_WITH_ENGINE_DOUBLE_NOT_TARGET_KANZI', tests=len(outcomes), cases=outcomes,
                build=report, actual_getter_and_parent_instructions_executed=True,
                native_direct_child_lookup_executed=True, real_toc_hierarchy=True, qnx_or_vehicle=False)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('hmi','kzb','toc','output'):p.add_argument('--'+key,required=True,type=Path)
    p.add_argument('--baseline-hmi', type=Path); p.add_argument('--baseline-report', type=Path)
    a=p.parse_args()
    baseline = None
    if a.baseline_hmi or a.baseline_report:
        assert a.baseline_hmi and a.baseline_report
        legacy = a.baseline_hmi.read_bytes()
        assert digest(legacy) == 'a094be8555b6d8b7e4c24fc0b51daccd015b1cd6fa3dde6e38517dbd3f7b3102'
        baseline = legacy, json.loads(a.baseline_report.read_text())['build']['helper_labels']
    report=run(a.hmi.read_bytes(),a.kzb.read_bytes(),a.toc.read_text('utf-8'),baseline)
    a.output.mkdir(parents=True,exist_ok=False)
    (a.output/'REPORT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(report['status'],report['tests'])


if __name__=='__main__':main()
