"""RWV6: OEM-outline native image feathers and reversible information details.

Uses ordinary Kanzi.Image2D/PNG textures already used by the five OEM glass masks.
No custom shader, new native ABI, vehicle bitmap/data replacement or image AI.
The generated assets are code-native alpha geometry, colored from original brushes.
"""
import io
import math
import struct
from functools import lru_cache
from PIL import Image

from analyze_kzb_hmi import KzbDirectory, node_head, Cursor, custom_types, STANDARD_NODE_TYPES
from background_poc_native import apply_hmi_overlay
from compact_info_layout import RULES as COMPACT_RULES, MILEAGE, TRANSFORM
from display_fusion import encode_node, encode_manager, text, MANAGER, PROPERTY
from integrated_layout import CENTER_OUTER, CENTER_INNER, CENTER_SOURCE
from navigation_layout import words, srt, dictionary, repack, digest, KZB_SHA
from navigation_layout_refine import PREFIX
from ref2_noauth_native import Patch
from soft_edge_layout import (build_soft_edge_kzb, frame_nodes, rectangles as prior_rectangles,
                              SoftEdgeSource, FRAME_TOP as PRIOR_TOP, FRAME_BOTTOM as PRIOR_BOTTOM)

CAPABILITY = 0x52575636
FULL_INNER = (256, 144, 1408, 368)
FULL_SOURCE = (1408, 368)
FRAME_TOP, FRAME_BOTTOM = 'DXTop', 'DXBottom'
BOARD = 'Cluster/content/Prefab_Cluster_Board'
POWER = PREFIX + 'Power/PowerShow'
POWER_DIGITS = POWER + '/PowerShowText'
POWER_STACK = POWER_DIGITS + '/PowerShow'
POWER_UNIT = POWER + '/Power_Unit'
ALIGN = 'Node.HorizontalAlignment'
FULL_RULES = [(p, k, v) for p, k, v in COMPACT_RULES if p != BOARD and
              (p, k) not in {(POWER_DIGITS, TRANSFORM), (POWER_UNIT, TRANSFORM)}]
FULL_RULES += [(POWER_DIGITS, TRANSFORM, srt(174, 284, .74)), (POWER_DIGITS, ALIGN, words(0)),
               (POWER_STACK, ALIGN, words(1)), (POWER_UNIT, TRANSFORM, srt(482, 316, .85714286)),
               (POWER_UNIT, ALIGN, words(0)), (POWER_UNIT, 'TextBlockConcept.TextHorizontalAlignment', words(0))]


def png_payload(raw):
    if len(raw) < 28 or raw[:16] != bytes(16) or struct.unpack_from('<I', raw, 16)[0] != len(raw)-20:
        raise ValueError('Original PNG resource header drift')
    data = raw[20:]
    with Image.open(io.BytesIO(data)) as image:
        image.verify()
    return data


def encoded_inner(center):
    if not center:
        return FULL_INNER
    x, y, w, h = CENTER_OUTER
    a, b, c, d = CENTER_INNER
    return ((a-x)*1920/w, (b-y)*720/h, c*1920/w, d*720/h)


def contour(original):
    """Read the actual five left glass-mask alpha images/known original placements."""
    d = KzbDirectory(original)
    samples = []
    for index, x, y in ((1, 278, 0), (2, 0, 0), (3, 0, 136), (4, 0, 495), (5, 191, 641)):
        resource = '/Resource Files/Images/mask_%02d.png' % index
        data = png_payload(d.read(resource))
        image = Image.open(io.BytesIO(data)).convert('RGBA')
        samples.append((x, y, image))
    edge = []
    for y in range(720):
        for x in range(960):
            alpha = max((im.getpixel((x-left, y-top))[3] for left, top, im in samples
                         if left <= x < left+im.width and top <= y < top+im.height), default=0)
            if alpha < 128:
                edge.append(x); break
        else:
            edge.append(960)
    return tuple(edge)


def smooth(value):
    value = max(0., min(1., value))
    return value * value * (3 - 2*value)


def alpha_at(x, y, center, outline):
    if center:
        # Rounded outer-window feather; readable inner rectangle never includes the fade.
        ox, oy, ow, oh = CENTER_OUTER
        radius, feather = 24., 24.
        dx = abs(x-(ox+ow/2))-(ow/2-radius)
        dy = abs(y-(oy+oh/2))-(oh/2-radius)
        distance = math.hypot(max(dx, 0), max(dy, 0)) + min(max(dx, dy), 0) - radius
        return smooth((distance+feather)/feather)
    # Curved side contours derive from the OEM glass mask, not a guessed ellipse.
    # At the largest central cutout the matte is transparent before FULL_INNER.
    boundary = min(240., outline[max(0, min(719, int(y)))]+12.)
    left = 1-smooth((x-boundary)/(256-boundary))
    right = 1-smooth((1920-x-boundary)/(256-boundary))
    top = 1-smooth((y-80)/64)
    bottom = smooth((y-512)/32)
    return max(left, right, top, bottom)


@lru_cache(maxsize=2)
def frame_assets(original):
    if digest(original) != KZB_SHA:
        raise ValueError('Exact original KZB required')
    d = KzbDirectory(original)
    outline = contour(original)
    result = {}
    for center in (False, True):
        for theme in ('Dark', 'Light'):
            color = struct.unpack_from('<4f', d.read('/Brushes/Color Brush_BgBottom_'+theme), 16)
            rgb = tuple(round(v*255) for v in color[:3])
            # Full logical-resolution alpha avoids quantized row/rectangle joins.
            # Two images at 960x360 per mode: 5.3MiB decoded for all four combined.
            image = Image.new('RGBA', (960, 360))
            pixels = image.load()
            for py in range(360):
                for px in range(960):
                    a = round(alpha_at(px*2+1, py*2+1, center, outline)*255)
                    pixels[px, py] = (*rgb, a)
            buffer = io.BytesIO()
            image.save(buffer, 'PNG', optimize=False, compress_level=9)
            name = 'DX' + ('Center' if center else 'Full') + theme
            result[name] = buffer.getvalue()
    return result


def image_node(d, strings, texture):
    raw = encode_node(d, strings, 'Contour', [('Node.Width', struct.pack('<f', 960)),
        ('Node.Height', struct.pack('<f', 360)), ('Node2D.RenderTransformation', srt(0, 0, 2)),
        ('Image2D.Image', text('kzb://bic_byd_stha/Textures/'+texture))])
    return raw[:8] + words(d.dictionary('/$metaclass_dictionary').index('Kanzi.Image2D')) + raw[12:]


def frames(d, strings):
    return b''.join(encode_node(d, strings, root, [('Node.Width', struct.pack('<f', 1920)),
        ('Node.Height', struct.pack('<f', 720)), ('Node.Opacity', struct.pack('<f', 0))],
        [image_node(d, strings, 'DX'+mode+'Dark')])
        for root, mode in ((FRAME_TOP, 'Full'), (FRAME_BOTTOM, 'Center')))


def theme_manager(original, strings, props):
    """Preserve every original theme target/transition and add exactly two image targets."""
    raw = original.read('/State Managers/Bg_RootPage State Manager')
    c = Cursor(raw)
    if [c.word() for _ in range(4)] != [0, 0, 0, 2]:
        raise ValueError('Original theme groups drift')
    c.word()
    if original.dictionary('/$property_dictionary')[c.word()] != 'state2':
        raise ValueError('Original native theme driver drift')
    c.word()
    if c.word() != 3:
        raise ValueError('Original theme state count drift')
    result = bytearray(raw[:c.offset])
    types, names = custom_types(original), original.dictionary('/$property_dictionary')
    for expected in (0, 1, 2):
        name, value, count = c.word(), c.word(), c.word()
        if value != expected or count > 16:
            raise ValueError('Original theme state drift')
        begin = c.offset
        for _ in range(count):
            c.word()
            for _ in range(c.word()):
                key = c.word()
                c.variant(types[names[key]]['kind'] if names[key] in types else STANDARD_NODE_TYPES[key])
            if [c.word(), c.word(), c.word()] != [0, 0, 0]:
                raise ValueError('Original theme actions drift')
        result += words(name, value, count+2) + raw[begin:c.offset]
        for root, mode in ((FRAME_TOP, 'Full'), (FRAME_BOTTOM, 'Center')):
            result += words(strings.index('Content/'+root+'/Contour'), 1, props.index('Image2D.Image'))
            result += text('kzb://bic_byd_stha/Textures/DX'+mode+('Light' if value == 1 else 'Dark'))+words(0, 0, 0)
    if c.offset != 236:
        raise ValueError('Original theme boundary drift')
    return bytes(result) + raw[c.offset:]


def build_contour_kzb(original, toc):
    base, prior = build_soft_edge_kzb(original, toc)
    d, o = KzbDirectory(base), KzbDirectory(original)
    strings, props = d.dictionary('/$strings'), d.dictionary('/$property_dictionary')
    renamed = [v.replace(PRIOR_TOP, FRAME_TOP).replace(PRIOR_BOTTOM, FRAME_BOTTOM) for v in strings]
    for value in ['Contour'] + ['Content/'+root+'/Contour' for root in (FRAME_TOP, FRAME_BOTTOM)] + [p for p, _, _ in FULL_RULES]:
        if value not in renamed:
            renamed.append(value)
    additions = {}
    for name, png in frame_assets(original).items():
        resource = '/Resource Files/Images/'+name+'.png'
        resource_url = 'kzb://bic_byd_stha'+resource
        if resource_url not in renamed:
            renamed.append(resource_url)
        texture = o.read('/Textures/mask_01')
        if len(texture) != 36:
            raise ValueError('Original PNG texture descriptor drift')
        additions['/Textures/'+name] = (o.records['/Textures/mask_01']['class_id'],
                                       texture[:32]+words(renamed.index(resource_url)))
        additions[resource] = (0xffffffff, words(0, 0, 0, 0, len(png))+png)
    if len(set(renamed)) != len(renamed):
        raise ValueError('Contour name collision')
    raw = d.read('/StartupPrefab')
    start = 1855+node_head(d, raw, 405)['end_properties']-476
    old = frame_nodes(d, strings, prior_rectangles, PRIOR_TOP, PRIOR_BOTTOM)
    if raw[start:start+len(old)] != old:
        raise ValueError('Exact previous owned frame nodes required')
    rules = FULL_RULES + [(FRAME_TOP, 'Node.Opacity', struct.pack('<f', 1)),
                         (FRAME_BOTTOM, 'Node.Opacity', struct.pack('<f', 0))]
    center_rules = [(FRAME_TOP, 'Node.Opacity', struct.pack('<f', 0)),
                    (FRAME_BOTTOM, 'Node.Opacity', struct.pack('<f', 1))]
    changes = {'/$strings': dictionary(renamed), '/StartupPrefab': raw[:start]+frames(d, renamed)+raw[start+len(old):],
        MANAGER: encode_manager(renamed, props, PROPERTY, [('DCIdle', 0, []), ('DCFull', 1, rules), ('DCCenter', 2, center_rules)]),
        '/State Managers/Bg_RootPage State Manager': theme_manager(o, renamed, props)}
    output = repack(d, changes, additions)
    current = KzbDirectory(output)
    for n in d.records:
        if n not in changes and d.read(n) != current.read(n):
            raise ValueError('Unrelated original resource drift: '+n)
    return output, dict(input_sha256=digest(original), prior_sha256=digest(base), output_sha256=digest(output),
        changed_resources=list(changes), added_resources=list(additions), full_inner=list(FULL_INNER),
        native_glass_contour='exact OEM mask_01..05 alpha/placements', original_masks_unchanged=True,
        full_power_unit_same_row=True, original_range_groundcover_restored=True, original_adas_and_mileage_unchanged=True,
        new_native_drawables=2, decoded_texture_bytes=4*960*360*4, custom_shaders=0, real_kanzi_executed=False, prior=prior)


class ContourSource:
    def read_text(self, encoding):
        base = SoftEdgeSource().read_text(encoding)
        for old, new in (('movw r0, #0x5635', 'movw r0, #0x5636'),
                         ('.asciz "DWTop"', '.asciz "DXTop"'), ('.asciz "DWBottom"', '.asciz "DXBottom"')):
            if base.count(old) != 1:
                raise ValueError('Exact RWV5 bridge required')
            base = base.replace(old, new)
        return base


def build_contour_hmi(original):
    result, report = apply_hmi_overlay(original, source_path=ContourSource())
    report.update(capability=CAPABILITY, source_sha256=digest(ContourSource().read_text('utf-8').encode()),
                  lease_and_safety_paths_unchanged=True)
    return result, report


def build_contour_receiver(previous):
    patch = Patch(previous, '9a17b72f7586e58d4ba8129437a5bbc5b3bf9c8279c356a3c35a76d2d7cc16a4')
    from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM
    assembler = Ks(KS_ARCH_ARM, KS_MODE_ARM)
    start, size = 0x108898, 3232
    offset = patch.offset(start, size)
    pattern = bytes(assembler.asm('ldr r2, [r6, #124]; movw r3, #0x5635; movt r3, #0x5257')[0])
    slot = previous[offset:offset+size]
    if slot.count(pattern) != 1:
        raise ValueError('Exact guarded RWV5 receiver required')
    patch.arm('paired_RWV6_contour', start+slot.index(pattern)+4, 4, 'movw r3, #0x5636')
    result, report = patch.finish()
    report.update(capability=CAPABILITY, media_guard_query_role_byte_exact=True)
    return result, report
