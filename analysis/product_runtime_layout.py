"""RWVF: projection-only native render filtering, with real scene-draw feedback.

Existing OEM LOAD segments, relocations, interpreter, unwind tables and RELRO
remain intact. A separate RX LOAD carries bounded, hash-pinned Thumb hooks.
The program-header table is relocated into that LOAD; no old header/data slot
is stolen. This is a binary adaptation, not a QNX SDK build or a vehicle test.
"""
import io
import re
import struct
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from elftools.elf.elffile import ELFFile
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from axes_rim_layout import build_axes_hmi, build_axes_kzb, build_plain_scene
from background_poc_native import ROOT, STATE, compile_thumb
from axes_rim_layout import AxesSource
from navigation_layout import digest
from ref2_noauth_native import Patch

CAPABILITY = 0x5257563f
EXTENSION = 0x086f0000
CODE = EXTENSION + 0x400
SOURCE = ROOT / 'phase1/qnx/native-hmi/product-runtime.thumb'
ENTRIES = ('runtime_2d', 'runtime_mesh', 'runtime_brush', 'runtime_hide_2d', 'runtime_hide_mesh', 'runtime_name', 'runtime_registered')
RECURSIVE_2D = 0x081d15fc
RENDER_MESH = 0x08216184


def _assemble(source):
    external = []
    def defer(match):
        name = 'runtime_external_' + str(len(external))
        external.append((name, match[1], int(match[2], 16)))
        return name + ':\nnop.w'
    source = re.sub(r'(?m)^(b\.w|bl) #0x([0-9a-fA-F]+)\s*$', defer, source)
    names = ENTRIES + tuple(v[0] for v in external)
    assembled, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(source + '\n.balign 4\n' +
        '\n'.join('.word ' + n for n in names), addr=CODE)
    if assembled is None:
        raise ValueError('Runtime Thumb assembly failed')
    raw = bytes(assembled)
    addresses = dict(zip(names, struct.unpack('<' + 'I' * len(names), raw[-4*len(names):])))
    output = bytearray(raw[:-4*len(names)])
    cs = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    for name, op, destination in external:
        address = addresses[name]
        code, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(op + ' #' + hex(destination), addr=address)
        decoded = list(cs.disasm(bytes(code), address))
        if len(code) != 4 or len(decoded) != 1 or int(decoded[0].op_str.lstrip('#'), 16) != destination:
            raise ValueError('Runtime external branch drift')
        output[address-CODE:address-CODE+4] = bytes(code)
    if len(output) > 0xc00:
        raise ValueError('Runtime RX budget exceeded')
    return bytes(output), {n: addresses[n] for n in ENTRIES}


def build_runtime_hmi(original):
    base, prior = build_axes_hmi(original)
    elf = ELFFile(io.BytesIO(base))
    # Layout offsets below are derived from these exact, disassembled methods.
    assert base[0x081c7fec-0x08048000:0x081c7ff0-0x08048000] == bytes.fromhex('c06a7047')
    name_check = base[0x081c8d2a-0x08048000:0x081c8d34-0x08048000]
    assert name_check == bytes.fromhex('d0f8b4101c4691f80c20') or [(i.mnemonic, i.op_str) for i in
        Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(name_check, 0x081c8d2a)][:1] == [('ldr.w', 'r1, [r0, #0xb4]')]
    _, labels, _ = compile_thumb(AxesSource())
    code, entries = _assemble(SOURCE.read_text('utf-8'))
    patch = Patch(base, digest(base))
    # Exact OEM loader format and source table: these become the runtime root names.
    assert base[0x085222e0-0x08048000:0x085222e0-0x08048000+6] == b'NN%u\0\0'
    assert struct.unpack_from('<II',base,patch.offset(0x086a8a00,8)) == (0x08525284,95)
    assert struct.unpack_from('<II',base,patch.offset(0x086a8a14,8)) == (0x0852529c,97)
    assert base[0x0810824a-0x08048000:0x0810824c-0x08048000] == bytes.fromhex('6023')
    assert base[0x08104800-0x08048000:0x0810480c-0x08048000] == bytes.fromhex('024b034a7b449b5818687047')
    for label, address, expected in (
            ('scene_2d_render_filter', RECURSIVE_2D, bytes.fromhex('2de9f04f')),
            ('scene_mesh_render_filter', RENDER_MESH, bytes.fromhex('30b583b0')),
            ('native_background_draw_feedback', labels['bg_brush'], base[labels['bg_brush']-0x08048000:labels['bg_brush']-0x08048000+4])):
        destination = entries['runtime_2d' if address == RECURSIVE_2D else
                              'runtime_mesh' if address == RENDER_MESH else 'runtime_brush']
        raw, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm('b.w #' + hex(destination), addr=address)
        patch.put(label, address, bytes(raw), expected=expected)
    # Both capability copies are exact MOVW in the existing render-thread bridge.
    source = AxesSource().read_text('utf-8')
    replacement = source.replace('movw r0, #0x563e', 'movw r0, #0x563f')
    assert replacement != source
    class RuntimeSource:
        def read_text(self, encoding):
            return replacement
    old_code, _, _ = compile_thumb(AxesSource())
    new_code, _, _ = compile_thumb(RuntimeSource())
    assert len(new_code) == len(old_code)
    for i, (a, b) in enumerate(zip(old_code, new_code)):
        if a != b:
            va = 0x086a2850 + i
            patch.put('paired_runtime_capability_' + str(i), va, bytes([b]), expected=bytes([a]))
    output, ranges = patch.finish()
    output = bytearray(output)
    offset = (len(output) + 4095) & ~4095
    headers = [dict(s.header) for s in elf.iter_segments()]
    assert len(headers) == 9 and elf['e_phentsize'] == 32
    assert all(s['p_type'] != 'PT_LOAD' or s['p_vaddr'] + s['p_memsz'] <= EXTENSION for s in headers)
    header_size = (len(headers) + 1) * 32
    load_size = 0x400 + len(code)
    table = bytearray()
    original_table = base[elf['e_phoff']:elf['e_phoff']+len(headers)*32]
    for i, header in enumerate(headers):
        values = list(struct.unpack_from('<8I', original_table, i*32))
        if header['p_type'] == 'PT_PHDR':
            values = [6, offset, EXTENSION, EXTENSION, header_size, header_size, 5, 4]
        table.extend(struct.pack('<8I', *values))
    table.extend(struct.pack('<8I', 1, offset, EXTENSION, EXTENSION, load_size, load_size, 5, 4096))
    assert len(table) == header_size and header_size <= 0x400
    output.extend(bytes(offset-len(output)))
    output.extend(table + bytes(0x400-len(table)) + code)
    struct.pack_into('<I', output, 28, offset)
    struct.pack_into('<H', output, 44, len(headers)+1)
    final = bytes(output)
    parsed = ELFFile(io.BytesIO(final))
    for old, new in zip(headers, parsed.iter_segments()):
        if old['p_type'] != 'PT_PHDR':
            assert old == dict(new.header)
    assert parsed['e_entry'] == elf['e_entry'] and parsed['e_shoff'] == elf['e_shoff']
    return final, dict(input_sha256=digest(original), output_sha256=digest(final), prior=prior,
        capability=CAPABILITY, extension_address=EXTENSION, code_address=CODE,
        extension_file_offset=offset, extension_bytes=load_size, entries=entries,
        original_loads_unchanged=True, original_interpreter_unwind_relro_preserved=True,
        patch=ranges, source_sha256=digest(SOURCE.read_bytes()),
        filtering='exact leaf/direct-parent/ancestor; active lease only; current-frame native render',
        feedback_words={'scene_traversal':16,'root_background_draw':17,'suppressed_2d':27,'suppressed_3d':28},
        runtime_root_ids={'full_2d':95,'small_2d':96,'scene_3d':97},runtime_root_format='NN%u',
        current_registry_identity_required_for_generated_names=True,
        property_overwrites_cannot_restore_filtered_draw=True, qnx_runtime_tested=False)


def build_runtime_kzb(original, toc):
    output, report = build_axes_kzb(original, toc)
    return output, dict(report, runtime_filter_owns_plain_scene=True)


def build_runtime_receiver(previous):
    patch = Patch(previous, '10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    patch.arm('paired_RWVF_product_runtime', 0x108b14, 4, 'movw r3, #0x563f')
    output, report = patch.finish()
    return output, dict(report, capability=CAPABILITY, media_guard_role_unchanged=True)
