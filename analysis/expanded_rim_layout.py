"""RWVD: a wider full aperture; live native BG and center geometry retained."""
import io
import math
from functools import lru_cache
from PIL import Image
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from analyze_kzb_hmi import KzbDirectory
from navigation_layout import dictionary, repack, digest, words
from background_poc_native import apply_hmi_overlay, CAVE
from ref2_noauth_native import Patch
from inner_ring_refinement import _edt_line
import native_background_layout as previous
from expanded_rim_geometry import (FULL_INNER,FULL_SOURCE,FULL_SAFE,CURVES,
    SIDE_OUTSET,TOP,BOARD_TOP,inner_contour,validate_expanded_carrier)

CAPABILITY = 0x5257563d
FRAME_TOP,FRAME_BOTTOM = 'IDTop','IDBottom'
CENTER_INNER,CENTER_OUTER,CENTER_SOURCE = previous.CENTER_INNER,previous.CENTER_OUTER,previous.CENTER_SOURCE
SIDE_FEATHER,VERTICAL_FEATHER = 12.,24.
BLEND_GATE = previous.BLEND_GATE
build_plain_scene = previous.build_plain_scene

def encoded_inner(center):
    return previous.encoded_inner(True) if center else FULL_INNER

@lru_cache(maxsize=1)
def alpha_grid():
    outline = inner_contour()
    width,height,far = 960,360,1e9
    sx,sy = 2./SIDE_FEATHER,2./VERTICAL_FEATHER
    rows = []
    for y in range(height):
        lo,hi = 2*y,2*y+2
        left = max(outline[lo-TOP][0],outline[hi-TOP][0]) if TOP <= lo and hi < BOARD_TOP else 960.
        f = [far if left < 2*x+1 < 1920-left else 0. for x in range(width)]
        rows.append([v*sx*sx for v in _edt_line([v/(sx*sx) for v in f])])
    for x in range(width):
        column = [v*sy*sy for v in _edt_line([rows[y][x]/(sy*sy) for y in range(height)])]
        for y in range(height):
            value = max(0.,min(1.,math.sqrt(column[y])-1./VERTICAL_FEATHER))
            rows[y][x] = 1-value*value*(3-2*value)
    return tuple(tuple(row) for row in rows)

def aperture_alpha(x,y,outline=None):
    return alpha_grid()[int(y)//2][int(x)//2] if 0 <= x < 1920 and 0 <= y < 720 else 1.

def build_expanded_kzb(original,toc):
    validate_expanded_carrier()
    base,prior = previous.build_native_background_kzb(original,toc)
    d = KzbDirectory(base)
    strings = [s.replace(previous.FRAME_TOP,FRAME_TOP).replace(previous.FRAME_BOTTOM,FRAME_BOTTOM)
               for s in d.dictionary('/$strings')]
    image = Image.new('RGBA',(960,360),(255,255,255,255))
    alpha = Image.new('L',image.size)
    alpha.putdata([round(255*a) for row in alpha_grid() for a in row]); image.putalpha(alpha)
    stream = io.BytesIO(); image.save(stream,'PNG',compress_level=9); raw = stream.getvalue()
    changes = {'/$strings':dictionary(strings)}
    changes.update({f'/Resource Files/Images/DXFull{theme}.png':words(0,0,0,0,len(raw))+raw for theme in ('Dark','Light')})
    output = repack(d,changes,{})
    old = previous.alpha_grid(); new = alpha_grid()
    clear_gain = 4*sum(b==0 and a>0 for ra,rb in zip(old,new) for a,b in zip(ra,rb))
    visible_gain = 4*sum(b<1 and a==1 for ra,rb in zip(old,new) for a,b in zip(ra,rb))
    return output,dict(input_sha256=digest(original),output_sha256=digest(output),prior=prior,
        changed_resources=list(changes),full_video_bounds=FULL_INNER,full_safe=FULL_SAFE,
        curve_control_points=CURVES,side_outset_native_pixels=SIDE_OUTSET,
        original_curve_position_unchanged=False,curve_shape_preserved_by_translation=True,
        top_anchor=TOP,footer_anchor=BOARD_TOP,side_feather_native_pixels=12,vertical_feather_native_pixels=24,
        additional_clear_native_pixels=clear_gain,additional_visible_design_pixels=visible_gain,
        native_background_rendered=True,palette_replacement=False,small_mode_geometry_unchanged=True,
        real_kanzi_executed=False)

class ExpandedSource:
    def read_text(self,encoding):
        source = previous.NativeBackgroundSource().read_text(encoding)
        for a,b in (('movw r0, #0x563c','movw r0, #0x563d'),
                    ('.asciz "ICTop"','.asciz "IDTop"'),('.asciz "ICBottom"','.asciz "IDBottom"')):
            if source.count(a) != 1:
                raise ValueError('Exact RWVC source required')
            source = source.replace(a,b)
        return source

def build_expanded_hmi(original):
    base,report = apply_hmi_overlay(original,source_path=ExpandedSource())
    patch = Patch(base,digest(base)); gate = previous.BLEND_GATE
    before = base[patch.offset(gate,4):patch.offset(gate,4)+4]
    instructions = [(i.mnemonic,i.op_str) for i in Cs(CS_ARCH_ARM,CS_MODE_THUMB).disasm(before,gate)]
    if instructions != [('cmp','r2, #4'),('bhi','#0x8312a54')]:
        raise ValueError('Exact original blend dispatch required')
    code,_ = Ks(KS_ARCH_ARM,KS_MODE_THUMB).asm('b.w #'+hex(CAVE+16),addr=gate)
    patch.put('private_destination_in_gate',gate,bytes(code),expected=before)
    output,extension = patch.finish()
    report.update(output_sha256=digest(output),private_blend_extension=extension,capability=CAPABILITY,
        registered_prefab_slots=106,original_background_and_root_brush_draw=True,
        legacy_blend_cases_unchanged=True,side_outset_native_pixels=SIDE_OUTSET)
    return output,report

def build_expanded_receiver(previous_binary):
    patch = Patch(previous_binary,'10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    patch.arm('paired_RWVD_expanded_rim',0x108b14,4,'movw r3, #0x563d')
    output,report = patch.finish()
    report.update(capability=CAPABILITY,media_guard_role_unchanged=True)
    return output,report
