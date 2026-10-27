"""RWVC: retain live OEM background; punch only the video opening into its alpha.

This exact engine has legacy blend modes 0..4. A hash-bound dispatch extension
adds private mode5 (destination-in) for our two mask images only. It never changes
an original blend case, background texture, theme manager or foreground draw.
"""
import io
import struct
from functools import lru_cache
from PIL import Image
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES, node_head
from background_poc_native import apply_hmi_overlay, CAVE
from display_fusion import MANAGER, encode_node
from navigation_layout import dictionary, repack, digest, words, srt
from edge_rim_layout import (build_edge_kzb, EdgeSource, build_plain_scene,
    FULL_INNER, FULL_SAFE, FULL_SOURCE, CURVES, CENTER_INNER, CENTER_OUTER, CENTER_SOURCE,
    alpha_grid, aperture_alpha, inner_contour, TOP, BOARD_TOP, encoded_inner)
from ref2_noauth_native import Patch

CAPABILITY = 0x5257563c
FRAME_TOP, FRAME_BOTTOM = 'ICTop', 'ICBottom'
PRIVATE_DESTINATION_IN = 5
BLEND_GATE = 0x083129c6

def _owned_frame(directory, strings, name, mode):
    leaf = encode_node(directory, strings, 'Contour', [
        ('Node.Width', struct.pack('<f', 960)), ('Node.Height', struct.pack('<f', 360)),
        ('Node2D.RenderTransformation', srt(0, 0, 2)),
        ('BlendMode', words(PRIVATE_DESTINATION_IN)),
        ('Image2D.Image', _text('kzb://bic_byd_stha/Textures/DX'+mode+'Dark'))])
    leaf = leaf[:8]+words(directory.dictionary('/$metaclass_dictionary').index('Kanzi.Image2D'))+leaf[12:]
    return encode_node(directory, strings, name, [
        ('Node.Width', struct.pack('<f', 1920)), ('Node.Height', struct.pack('<f', 720)),
        ('Node.Opacity', struct.pack('<f', 0)), ('Node.Visible', words(0))], [leaf])

def _text(value):
    raw = value.encode()
    return words(len(raw))+raw

def _owned_end(directory, raw, offset, expected):
    root = node_head(directory, raw, offset)
    if root['name'] != expected or struct.unpack_from('<I', raw, root['end_properties'])[0] != 1:
        raise ValueError('Exact owned frame required')
    child = node_head(directory, raw, root['end_properties']+4)
    if child['name'] != 'Contour' or child['node_class'] != 'Kanzi.Image2D':
        raise ValueError('Exact owned mask required')
    empty = directory.dictionary('/$strings').index('')
    expected_tail = words(0, 0, 0, empty, 0, 0, empty)
    if raw[child['end_properties']:child['end_properties']+28] != expected_tail:
        raise ValueError('Owned zero-child/tail layout changed')
    return child['end_properties']+28

def _visible_states(directory, raw, strings):
    """Preserve all existing modifier records and add active mask visibility."""
    cursor = Cursor(raw)
    if [cursor.word(), cursor.word(), cursor.word(), cursor.word()] != [0, 0, 0, 1]:
        raise ValueError('Exact mode group required')
    cursor.word(); cursor.word(); cursor.word()
    if cursor.word() != 3:
        raise ValueError('Three mode states required')
    output = bytearray(raw[:cursor.offset])
    props = directory.dictionary('/$property_dictionary')
    types = custom_types(directory)
    for expected in range(3):
        state, value, count = cursor.word(), cursor.word(), cursor.word()
        if value != expected:
            raise ValueError('Mode state order changed')
        start = cursor.offset
        for _ in range(count):
            cursor.word()
            for _ in range(cursor.word()):
                index = cursor.word(); key = props[index]
                cursor.variant(types[key]['kind'] if key in types else STANDARD_NODE_TYPES[index])
            if [cursor.word(), cursor.word(), cursor.word()] != [0, 0, 0]:
                raise ValueError('Mode action layout changed')
        output += words(state, value, count+(value != 0))+raw[start:cursor.offset]
        if value:
            root = FRAME_TOP if value == 1 else FRAME_BOTTOM
            output += words(strings.index(root), 1, props.index('Node.Visible'), 1, 0, 0, 0)
    if raw[cursor.offset:] != words(0):
        raise ValueError('Mode footer changed')
    return bytes(output)+words(0)

@lru_cache(maxsize=2)
def mask_frames(base):
    directory = KzbDirectory(base)
    result = {}
    for mode in ('Full', 'Center'):
        for theme in ('Dark', 'Light'):
            name = '/Resource Files/Images/DX'+mode+theme+'.png'
            image = Image.open(io.BytesIO(directory.read(name)[20:])).convert('RGBA')
            mask = Image.new('RGBA', image.size, (255, 255, 255, 255))
            mask.putalpha(image.getchannel('A'))
            stream = io.BytesIO(); mask.save(stream, 'PNG', compress_level=9)
            raw = stream.getvalue()
            result[name] = words(0, 0, 0, 0, len(raw))+raw
    return result

def build_native_background_kzb(original, toc):
    base, prior = build_edge_kzb(original, toc)
    directory = KzbDirectory(base)
    strings = [value.replace('IBTop', FRAME_TOP).replace('IBBottom', FRAME_BOTTOM)
               for value in directory.dictionary('/$strings')]
    raw = directory.read('/StartupPrefab')
    start = 1855+node_head(directory, raw, 405)['end_properties']-476
    middle = _owned_end(directory, raw, start, 'IBTop')
    end = _owned_end(directory, raw, middle, 'IBBottom')
    frames = _owned_frame(directory, strings, FRAME_TOP, 'Full')+_owned_frame(directory, strings, FRAME_BOTTOM, 'Center')
    changes = {'/$strings': dictionary(strings), '/StartupPrefab': raw[:start]+frames+raw[end:],
               MANAGER: _visible_states(directory, directory.read(MANAGER), strings), **mask_frames(base)}
    output = repack(directory, changes, {})
    return output, dict(input_sha256=digest(original), output_sha256=digest(output), prior=prior,
        changed_resources=list(changes), native_background_rendered=True, palette_replacement=False,
        private_blend_mode=PRIVATE_DESTINATION_IN, inactive_masks_visible=False,
        curve_control_points=CURVES, full_video_bounds=FULL_INNER, full_safe=FULL_SAFE,
        side_feather_native_pixels=12, vertical_feather_native_pixels=24,
        additional_clear_native_pixels=prior['additional_clear_native_pixels'],
        original_background_resources_and_state_managers_preserved=True,
        real_kanzi_executed=False)

class NativeBackgroundSource:
    def read_text(self, encoding):
        source = EdgeSource().read_text(encoding)
        for before, after in (('movw r0, #0x563b', 'movw r0, #0x563c'),
                              ('.asciz "IBTop"', '.asciz "ICTop"'),
                              ('.asciz "IBBottom"', '.asciz "ICBottom"')):
            if source.count(before) != 1:
                raise ValueError('Exact RWVB source required')
            source = source.replace(before, after)
        source = source.replace('b.w bg_brush\n\nbg_getter:', 'b.w bg_brush\nb.w native_mask_blend\n\nbg_getter:')
        # Keep original draw trampolines. Reclaim only obsolete skip branches;
        # the background/brush now draw normally before the owned alpha mask.
        begin, end = source.index('bg_render:\n'), source.index('// r0 borrowed Screen,')
        source = source[:begin]+'''bg_render:
push {r3, lr}
ldr r3, [r0]
b.w #0x081d09f0
bg_brush:
ldr r2, bg_original_literal
push {r4, r5, r6, lr}
b.w #0x081d09fc
'''+source[end:]
        source += '''
native_mask_blend:
cmp r2, #5
bne native_mask_legacy
movs r1, #2
mov r0, r4
bl #0x083116b4
ldr.w r3, [r4, #0x338]
cmp r3, #5
beq native_mask_done
movs r3, #5
str.w r3, [r4, #0x338]
movs r0, #0
movw r1, #0x302
movs r2, #0
mov r3, r1
blx #0x080d74d0
native_mask_done:
b.w #0x08312970
native_mask_legacy:
cmp r2, #4
bls native_mask_table
b.w #0x08312a54
native_mask_table:
b.w #0x083129ca
'''
        return source

def build_native_background_hmi(original):
    base, report = apply_hmi_overlay(original, source_path=NativeBackgroundSource())
    patch = Patch(base, digest(base))
    before = base[patch.offset(BLEND_GATE, 4):patch.offset(BLEND_GATE, 4)+4]
    old = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(before, BLEND_GATE))
    if [(i.mnemonic, i.op_str) for i in old] != [('cmp', 'r2, #4'), ('bhi', '#0x8312a54')]:
        raise ValueError('Exact legacy blend dispatch required')
    code, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm('b.w #'+hex(CAVE+16), addr=BLEND_GATE)
    patch.put('private_destination_in_gate', BLEND_GATE, bytes(code), expected=before)
    output, extension = patch.finish()
    report.update(output_sha256=digest(output), private_blend_extension=extension,
                  capability=CAPABILITY, registered_prefab_slots=106,
                  original_background_and_root_brush_draw=True, legacy_blend_cases_unchanged=True)
    return output, report

def build_native_background_receiver(previous):
    patch = Patch(previous, '10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    patch.arm('paired_RWVC_native_background', 0x108b14, 4, 'movw r3, #0x563c')
    output, report = patch.finish()
    report.update(capability=CAPABILITY, media_guard_role_unchanged=True)
    return output, report
