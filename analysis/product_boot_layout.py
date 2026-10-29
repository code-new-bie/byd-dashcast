"""RWVF boot correction: two original LOADs, mapped prefix RX page, fixed VAs.

The original business code/data/imports retain their virtual addresses. A single
mapped page before the first RX segment contains the main ELF header/PHDR and
the runtime hooks. All original file contents move by one page; file offsets in
program/section headers follow that move. No third LOAD or relocated tail PHDR.
"""
import io
import struct

from elftools.elf.elffile import ELFFile
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from axes_rim_layout import AxesSource, build_axes_hmi
from background_poc_native import compile_thumb
from navigation_layout import digest
from product_runtime_layout import (CAPABILITY, SOURCE, RECURSIVE_2D, RENDER_MESH,
    _assemble, require_qnx_header_anchor, build_runtime_kzb, build_plain_scene,
    build_runtime_receiver)
from ref2_noauth_native import Patch

PAGE = 4096
ORIGINAL_BASE = 0x08048000
EXTENSION = ORIGINAL_BASE - PAGE
CODE = EXTENSION + 0x400


def build_boot_hmi(original):
    base, prior = build_axes_hmi(original)
    elf = ELFFile(io.BytesIO(base))
    assert elf['e_phoff'] == 52 and elf['e_phnum'] == 9
    assert elf['e_ehsize'] == 52 and elf['e_phentsize'] == 32 and elf['e_shentsize'] == 40
    headers = [dict(s.header) for s in elf.iter_segments()]
    loads = [h for h in headers if h['p_type'] == 'PT_LOAD']
    assert len(loads) == 2
    assert loads[0]['p_offset'] == 0 and loads[0]['p_vaddr'] == ORIGINAL_BASE and loads[0]['p_flags'] == 5
    assert loads[1]['p_flags'] == 6 and loads[1]['p_vaddr'] > loads[0]['p_vaddr'] + loads[0]['p_memsz']
    anchor = require_qnx_header_anchor(base)
    assert anchor['elf_header'] == ORIGINAL_BASE
    # Exact same runtime scope checks as withdrawn RWVF; only its placement changes.
    pinned = Patch(base, digest(base))
    assert struct.unpack_from('<II', base, pinned.offset(0x086a8a00, 8)) == (0x08525284, 95)
    assert struct.unpack_from('<II', base, pinned.offset(0x086a8a14, 8)) == (0x0852529c, 97)
    assert base[pinned.offset(0x085222e0, 6):pinned.offset(0x085222e0, 6) + 6] == b'NN%u\0\0'
    assert base[pinned.offset(0x08104800, 12):pinned.offset(0x08104800, 12) + 12] == bytes.fromhex('024b034a7b449b5818687047')
    code, entries = _assemble(SOURCE.read_text('utf-8'), address=CODE)
    assert 0x400 + len(code) <= PAGE
    _, labels, _ = compile_thumb(AxesSource())
    for name, address, expected, destination in (
        ('runtime_2d', RECURSIVE_2D, bytes.fromhex('2de9f04f'), entries['runtime_2d']),
        ('runtime_mesh', RENDER_MESH, bytes.fromhex('30b583b0'), entries['runtime_mesh']),
        ('runtime_brush', labels['bg_brush'], base[pinned.offset(labels['bg_brush'], 4):pinned.offset(labels['bg_brush'], 4)+4], entries['runtime_brush'])):
        raw, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm('b.w #' + hex(destination), addr=address)
        pinned.put(name, address, bytes(raw), expected=expected)
    source = AxesSource().read_text('utf-8')
    replacement = source.replace('movw r0, #0x563e', 'movw r0, #0x563f')
    assert source.count('movw r0, #0x563e') == 1

    class PairedSource:
        def read_text(self, encoding):
            return replacement

    old_code, _, _ = compile_thumb(AxesSource())
    new_code, _, _ = compile_thumb(PairedSource())
    assert len(old_code) == len(new_code)
    for index, (a, b) in enumerate(zip(old_code, new_code)):
        if a != b:
            pinned.put('paired_capability_' + str(index), 0x086a2850 + index, bytes([b]), expected=bytes([a]))
    body, patch_report = pinned.finish()
    page = bytearray(PAGE)
    page[:52] = body[:52]
    for index, header in enumerate(headers):
        values = list(struct.unpack_from('<8I', body, elf['e_phoff'] + index * 32))
        if header['p_type'] == 'PT_PHDR':
            assert values[1] == 52 and values[2] == ORIGINAL_BASE + 52
            values[2] -= PAGE
            values[3] -= PAGE
        elif header['p_type'] == 'PT_LOAD' and header['p_offset'] == 0:
            values[2] -= PAGE
            values[3] -= PAGE
            values[4] += PAGE
            values[5] += PAGE
        else:
            values[1] += PAGE
        struct.pack_into('<8I', page, elf['e_phoff'] + index * 32, *values)
    page[0x400:0x400 + len(code)] = code
    struct.pack_into('<I', page, 32, elf['e_shoff'] + PAGE)
    final = bytearray(page + body)
    # Original section data, VAs, indices and relocation contents remain intact.
    for index, section in enumerate(elf.iter_sections()):
        if index != 0:
            struct.pack_into('<I', final, elf['e_shoff'] + PAGE + index * 40 + 16,
                section['sh_offset'] + PAGE)
    final = bytes(final)
    check = ELFFile(io.BytesIO(final))
    assert check['e_phoff'] == elf['e_phoff'] and check['e_phnum'] == elf['e_phnum']
    assert check['e_entry'] == elf['e_entry'] and check['e_flags'] == elf['e_flags']
    assert len(final) == len(base) + PAGE
    for old, new in zip(elf.iter_sections(), check.iter_sections()):
        assert old.name == new.name and old['sh_addr'] == new['sh_addr']
        if old['sh_type'] != 'SHT_NOBITS' and old['sh_type'] != 'SHT_NULL':
            # Existing .text changes are only the explicitly pinned jumps/capability.
            start, size = old['sh_offset'], old['sh_size']
            assert new.data() == body[start:start + size]
    fixed_anchor = require_qnx_header_anchor(final)
    assert fixed_anchor['elf_header'] == EXTENSION
    return final, dict(input_sha256=digest(original), output_sha256=digest(final), prior=prior,
        capability=CAPABILITY, extension_address=EXTENSION, code_address=CODE,
        extension_bytes=PAGE, entries=entries, patch=patch_report,
        load_count=2, program_header_file_offset=52, program_header_count=9,
        business_code_and_data_virtual_addresses_unchanged=True,
        all_original_program_header_semantics_preserved=True,
        original_notes_interpreter_unwind_relro_preserved=True,
        qnx_header_anchor=fixed_anchor, qnx_runtime_tested=False,
        source_sha256=digest(SOURCE.read_bytes()),
        feedback_words={'scene_traversal':16,'root_background_draw':17,'suppressed_2d':27,'suppressed_3d':28},
        runtime_root_ids={'full_2d':95,'small_2d':96,'scene_3d':97},
        current_registry_identity_required_for_generated_names=True)


def build_runtime_hmi(original):
    """Compatibility entry for the paired regression harness, using fixed layout."""
    return build_boot_hmi(original)
