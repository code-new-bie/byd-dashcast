"""RWVB: unchanged inner curve, narrower side fade and OEM background pixels.

The old DCADASPanel is a sibling backdrop, never the parent of Car or Lane.
Only the exact ordinary pictures are hidden; native signal/warning groups stay.
"""
import io
import math
import struct
from functools import lru_cache
from PIL import Image

from analyze_kzb_hmi import KzbDirectory, node_head
from background_poc_native import apply_hmi_overlay
from display_fusion import PROPERTY, MANAGER, encode_manager
from integrated_layout import ADAS_MANAGER, SMALL_MANAGER, adas_rules, ADAS_RESOURCE, SMALL_RESOURCE
from navigation_layout import KZB_SHA, dictionary, digest, repack, words, srt
from inner_ring_refinement import inner_contour, _edt_line, alpha_grid as old_alpha_grid
from sharp_inner_ring_layout import (FULL_INNER, FULL_SOURCE, FULL_SAFE, CURVES,
    CENTER_INNER, CENTER_OUTER, CENTER_SOURCE, BOARD_TOP, TOP,
    build_sharp_kzb, SharpSource, build_plain_scene, encoded_inner)
from ref2_noauth_native import Patch

CAPABILITY = 0x5257563b
FRAME_TOP, FRAME_BOTTOM = 'IBTop', 'IBBottom'
SIDE_FEATHER, VERTICAL_FEATHER = 12., 24.
FEATHER = SIDE_FEATHER
F = lambda value: struct.pack('<f', value)

def smooth(value):
    value = max(0., min(1., value))
    return value * value * (3. - 2. * value)

@lru_cache(maxsize=1)
def alpha_grid():
    """Anisotropic exact EDT; only side fade shrinks. Curve and anchors unchanged."""
    outline = inner_contour()
    width, height, far = 960, 360, 1e9
    rows = []
    # Distance is measured in units of each axis' feather width. Complete
    # 2x2 native texel footprints retain the former conservative curve support.
    sx, sy = 2. / SIDE_FEATHER, 2. / VERTICAL_FEATHER
    for y in range(height):
        lo, hi = 2*y, 2*y+2
        left = max(outline[lo-TOP][0], outline[hi-TOP][0]) if TOP <= lo and hi < BOARD_TOP else 960.
        f = [far if left < 2*x+1 < 1920-left else 0. for x in range(width)]
        rows.append([v*sx*sx for v in _edt_line([v/(sx*sx) for v in f])])
    for x in range(width):
        column = [v*sy*sy for v in _edt_line([rows[y][x]/(sy*sy) for y in range(height)])]
        for y in range(height):
            # Match the old half-texel conservative allowance on the y axis.
            rows[y][x] = 1.-smooth(max(0., math.sqrt(column[y])-1./VERTICAL_FEATHER))
    return tuple(tuple(row) for row in rows)

def aperture_alpha(x, y, outline=None):
    if not 0 <= x < 1920 or not 0 <= y < 720:
        return 1.
    return alpha_grid()[int(y)//2][int(x)//2]

def background_image(original, theme):
    """OEM normal dark gradient; OEM light brush. No video-derived blur/tint."""
    if digest(original) != KZB_SHA:
        raise ValueError('Exact original background required')
    directory = KzbDirectory(original)
    color = tuple(round(v*255) for v in struct.unpack_from('<4f',
        directory.read('/Brushes/Color Brush_BgBottom_'+theme), 16)[:3])
    image = Image.new('RGBA', (960, 360), (*color, 255))
    if theme == 'Dark':
        raw = directory.read('/Resource Files/Images/BG_Mode/BG_NoSkyLine.png')
        if raw[:16] != bytes(16) or struct.unpack_from('<I', raw, 16)[0] != len(raw)-20:
            raise ValueError('Original background PNG header drift')
        native = Image.open(io.BytesIO(raw[20:])).convert('RGBA')
        if native.size != (128, 48):
            raise ValueError('Original normal background dimensions drift')
        image.alpha_composite(native.resize(image.size, Image.Resampling.BILINEAR))
    return image

@lru_cache(maxsize=2)
def edge_frames(original):
    result = {}
    alpha = Image.new('L', (960, 360))
    alpha.putdata([round(255*v) for row in alpha_grid() for v in row])
    for theme in ('Dark', 'Light'):
        image = background_image(original, theme)
        image.putalpha(alpha)
        stream = io.BytesIO()
        image.save(stream, 'PNG', compress_level=9)
        raw = stream.getvalue()
        result['/Resource Files/Images/DXFull'+theme+'.png'] = words(0, 0, 0, 0, len(raw))+raw
    return result

def _assert_plain_targets(original):
    directory = KzbDirectory(original)
    # Exact heads/TOC paths, not a search which mistakes a sibling for a parent.
    for resource, offset, name in ((ADAS_RESOURCE, 267, 'Lane'), (ADAS_RESOURCE, 2789, 'Car'),
                                   (SMALL_RESOURCE, 350, 'ADAS_SmallBG'),
                                   (SMALL_RESOURCE, 3109, 'CarSmall')):
        head = node_head(directory, directory.read(resource), offset)
        if head['name'] != name or head['node_class'] != 'Kanzi.Image2D' or head['bindings']:
            raise ValueError('Unproven plain scene leaf: '+name)

def build_edge_kzb(original, toc):
    _assert_plain_targets(original)
    base, prior = build_sharp_kzb(original, toc)
    directory = KzbDirectory(base)
    strings = [s.replace('IATop', FRAME_TOP).replace('IABottom', FRAME_BOTTOM)
               for s in directory.dictionary('/$strings')]
    props = directory.dictionary('/$property_dictionary')
    rules = [(p, k, v) for p, k, v in adas_rules() if p != 'LaneDeparture/Car']
    rules += [('Lane', 'Node.Visible', words(0)), ('LaneDeparture/Car', 'Node.Visible', words(0)),
              ('LaneDeparture/Car', 'Node.Opacity', F(0))]
    small = [('.', 'Node2D.RenderTransformation', srt(1450, 156)),
             ('ADAS_SmallBG', 'Node.Opacity', F(0)), ('ADAS_SmallBG', 'Node.Visible', words(0)),
             ('LaneDistance/CarSmall', 'Node.Opacity', F(0)), ('LaneDistance/CarSmall', 'Node.Visible', words(0))]
    states = lambda active: [('DCIdle', 0, []), ('DCFull', 1, active), ('DCCenter', 2, active)]
    native = KzbDirectory(original).read('/State Managers/01_ThemeLightMode_EffectOnRoot')
    group = encode_manager(strings, props, PROPERTY, states(small))
    changes = {'/$strings': dictionary(strings),
               ADAS_MANAGER: encode_manager(strings, props, PROPERTY, states(rules)),
               SMALL_MANAGER: native[:12]+words(2)+native[16:]+group[16:], **edge_frames(original)}
    output = repack(directory, changes, {})
    old_clear = sum(v == 0 for row in old_alpha_grid() for v in row)
    new_clear = sum(v == 0 for row in alpha_grid() for v in row)
    return output, dict(input_sha256=digest(original), output_sha256=digest(output), prior=prior,
        changed_resources=list(changes), curve_control_points=CURVES, full_video_bounds=FULL_INNER,
        full_safe=FULL_SAFE, side_feather_native_pixels=SIDE_FEATHER,
        vertical_feather_native_pixels=VERTICAL_FEATHER, curve_unchanged=True,
        additional_clear_native_pixels=4*(new_clear-old_clear),
        edge_background='OEM BG_NoSkyLine normal dark gradient / OEM light background brush',
        live_drive_mode_background_claimed=False, decorative_circles_restored=False,
        corrected_sibling_panel_target=True, plain_car_and_road_direct_visibility=True,
        original_warning_managers_preserved=True, real_kanzi_executed=False)

class EdgeSource:
    def read_text(self, encoding):
        source = SharpSource().read_text(encoding)
        for before, after in (('movw r0, #0x563a', 'movw r0, #0x563b'),
                              ('.asciz "IATop"', '.asciz "IBTop"'),
                              ('.asciz "IABottom"', '.asciz "IBBottom"')):
            if source.count(before) != 1:
                raise ValueError('Exact previous paired source required')
            source = source.replace(before, after)
        return source

def build_edge_hmi(original):
    output, report = apply_hmi_overlay(original, source_path=EdgeSource())
    report.update(capability=CAPABILITY, registered_prefab_slots=106)
    return output, report

def build_edge_receiver(previous):
    patch = Patch(previous, '10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    patch.arm('paired_RWVB_edge_rim', 0x108b14, 4, 'movw r3, #0x563b')
    output, report = patch.finish()
    report.update(capability=CAPABILITY, media_guard_role_unchanged=True)
    return output, report
