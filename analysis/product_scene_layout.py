"""RWV8 rounded aperture and explicit registered-prefab mode propagation.

The OEM ADAS updater calls its 2D path. A patch only in secondary 3dscreen
does not reach that path. DCLayout is private; only our presentation managers
consume it. Propagate it to each currently registered Node2D on the render
thread, including late-loaded prefabs, rather than assuming prefab inheritance.
"""
import io
import math
import struct
from functools import lru_cache
from PIL import Image

from analyze_kzb_hmi import KzbDirectory
from background_poc_native import apply_hmi_overlay
from curved_scene_layout import (build_curved_kzb, build_plain_scene, CurvedSceneSource,
    FRAME_TOP as OLD_TOP, FRAME_BOTTOM as OLD_BOTTOM, contour, smooth)
from contour_layout import FULL_RULES, POWER_DIGITS, POWER_UNIT
from compact_info_layout import DIGITS, UNIT, MILEAGE, TRANSFORM
from integrated_layout import ADAS_MANAGER, SMALL_MANAGER, adas_rules, CENTER_INNER, CENTER_OUTER, CENTER_SOURCE
from display_fusion import PROPERTY, MANAGER, encode_manager
from navigation_layout import dictionary, repack, digest, srt, words
from ref2_noauth_native import Patch

CAPABILITY=0x52575638
FRAME_TOP,FRAME_BOTTOM='DZTop','DZBottom'
FULL_INNER=(64,112,1792,448)
FULL_SOURCE=FULL_INNER[2:]
FULL_SAFE=(256,152,1408,352)
BOARD_TOP=553
F=lambda x:struct.pack('<f',x)


def encoded_inner(center):
    if not center:return FULL_INNER
    x,y,w,h=CENTER_OUTER;a,b,c,d=CENTER_INNER
    return ((a-x)*1920/w,(b-y)*720/h,c*1920/w,d*720/h)


def aperture_alpha(x,y,outline):
    """Rounded signed-distance trapezoid; the lower opaque edge meets OEM board."""
    t=max(0.,min(1.,(y-112)/441))
    nx=abs(x-960)/960
    side=max(outline[max(0,min(719,int(y)))]+16.,64+40*t+56*t*t)
    top=108+10*nx*nx
    bottom=BOARD_TOP
    radius=62.
    qx=abs(x-960)-(960-side-radius)
    qy=abs(y-(top+bottom)/2)-((bottom-top)/2-radius)
    distance=math.hypot(max(qx,0),max(qy,0))+min(max(qx,qy),0)-radius
    return smooth((distance+32)/32)


@lru_cache(maxsize=2)
def product_frames(original):
    d=KzbDirectory(original);outline=contour(original);changes={}
    for theme in ('Dark','Light'):
        rgba=struct.unpack_from('<4f',d.read('/Brushes/Color Brush_BgBottom_'+theme),16)
        rgb=tuple(round(v*255) for v in rgba[:3]);img=Image.new('RGBA',(960,360));px=img.load()
        for y in range(360):
            for x in range(960):px[x,y]=(*rgb,round(aperture_alpha(x*2+1,y*2+1,outline)*255))
        stream=io.BytesIO();img.save(stream,'PNG',compress_level=9);data=stream.getvalue()
        changes['/Resource Files/Images/DXFull'+theme+'.png']=words(0,0,0,0,len(data))+data
    return changes


def build_product_kzb(original,toc):
    base,prior=build_curved_kzb(original,toc);d=KzbDirectory(base)
    strings=[s.replace(OLD_TOP,FRAME_TOP).replace(OLD_BOTTOM,FRAME_BOTTOM) for s in d.dictionary('/$strings')]
    props=d.dictionary('/$property_dictionary')
    # These are relative transforms composed with unchanged OEM child offsets.
    overrides={(POWER_DIGITS,TRANSFORM):srt(174,298,.74),
        (POWER_UNIT,TRANSFORM):srt(482,330,.85714286),
        (DIGITS,TRANSFORM):srt(1364,349,.72),
        (UNIT,TRANSFORM):srt(1592,381,.85714286),
        (MILEAGE,TRANSFORM):srt(1440,640,.70)}
    full=[(p,k,overrides.get((p,k),v)) for p,k,v in FULL_RULES]
    full += [(FRAME_TOP,'Node.Opacity',F(1)),(FRAME_BOTTOM,'Node.Opacity',F(0))]
    center=[(FRAME_TOP,'Node.Opacity',F(0)),(FRAME_BOTTOM,'Node.Opacity',F(1))]
    # Keep all lane-signal / target / takeover states; omit only the ego picture.
    adas=[(p,k,v) for p,k,v in adas_rules() if p!='LaneDeparture/Car']
    adas.append(('LaneDeparture/Car','Node.Opacity',F(0)))
    small=[('.', 'Node2D.RenderTransformation',srt(1450,156)),
        ('ADAS_SmallBG','Node.Opacity',F(0)),('LaneDistance/CarSmall','Node.Opacity',F(0))]
    for path,_,_ in adas+small:
        if path not in strings:strings.append(path)
    native=KzbDirectory(original).read('/State Managers/01_ThemeLightMode_EffectOnRoot')
    group=encode_manager(strings,props,PROPERTY,[('DCIdle',0,[]),('DCFull',1,small),('DCCenter',2,small)])
    changes={'/$strings':dictionary(strings),
        MANAGER:encode_manager(strings,props,PROPERTY,[('DCIdle',0,[]),('DCFull',1,full),('DCCenter',2,center)]),
        ADAS_MANAGER:encode_manager(strings,props,PROPERTY,[('DCIdle',0,[]),('DCFull',1,adas),('DCCenter',2,adas)]),
        SMALL_MANAGER:native[:12]+words(2)+native[16:]+group[16:]}
    changes.update(product_frames(original))
    output=repack(d,changes,{})
    return output,dict(input_sha256=digest(original),output_sha256=digest(output),prior=prior,
        changed_resources=list(changes),full_video_bounds=FULL_INNER,full_safe=FULL_SAFE,
        board_top=BOARD_TOP,corner_radius=62,ego_2d_image_hidden=True,
        original_alert_states_unchanged=True,real_kanzi_executed=False)


class ProductSceneSource:
    def read_text(self,encoding):
        source=CurvedSceneSource().read_text(encoding)
        for a,b in (('movw r0, #0x5637','movw r0, #0x5638'),
                    ('.asciz "DYTop"','.asciz "DZTop"'),('.asciz "DYBottom"','.asciz "DZBottom"')):
            if source.count(a)!=1:raise ValueError('Exact RWV7 source required')
            source=source.replace(a,b)
        start=source.index('nav_check_cached:')
        end=source.index('nav_publish:',start)
        source=source[:start]+'''nav_check_cached:
str r6, [r4, #100]
str r7, [sp]
add r0, r5, #4
mov r1, sp
mov r2, r6
bl #0x0814c210
bl #0x08104800
addw r8, r0, #0x178
movs r5, #106
nav_prefab_loop:
ldr r0, [r8], #16
cmp r0, #0
beq nav_prefab_next
adds r0, #4
mov r1, sp
mov r2, r6
bl #0x0814c210
nav_prefab_next:
subs r5, #1
bne nav_prefab_loop
ldr r8, [sp, #4]
'''+source[end:]
        # Reclaim only redundant cache and instruction encodings to stay within
        # the ORIGINAL verified 1572-byte RX gap. Keep every type/readback guard.
        substitutions={
            'ldr r7, [r4, #96]\ncmp r7, #0\nbne nav_descriptor_ready\n':'',
            'str r7, [r4, #96]\n':'',
            'movw r1, #0\nmovt r1, #0x3f80':'mov.w r1, #0x3f800000',
            'movs r8, #1':'movs r0, #1\nstr r0, [sp, #4]',
            'movs r8, #0':'movs r0, #0\nstr r0, [sp, #4]',
            'ldr r8, [sp, #4]\nnav_publish:\ncmp r8, #0':'ldr r0, [sp, #4]\nnav_publish:\ncmp r0, #0',
            'orrne r0, r0, #1':'addne r0, #1',
            'movw r0, #0x4300\nmovt r0, #0x4e4c\ncmp r6, #0\nit ne\naddne r0, #1\nb nav_status\nnav_wait:\nmovw r0, #0x4302\nmovt r0, #0x4e4c\nb nav_status':
                'movs r0, #0\ncmp r6, #0\nit ne\nmovne r0, #1\nb nav_result\nnav_wait:\nmovs r0, #2\nnav_result:\nmovw r1, #0x4300\nmovt r1, #0x4e4c\norrs r0, r1\nb nav_status',
        }
        for a,b in substitutions.items():
            if a not in source:raise ValueError('Compact bridge precondition')
            source=source.replace(a,b)
        return source


def build_product_hmi(original):
    data,report=apply_hmi_overlay(original,source_path=ProductSceneSource())
    report.update(capability=CAPABILITY,registered_prefab_slots=106,mode_written_each_render=True,
        data_sources_and_vehicle_signals_unchanged=True)
    return data,report


def build_product_receiver(previous):
    p=Patch(previous,'7f467fd3128f4f3ad8e2356637498cf2b857cdece29e246fbaa7064592626c83')
    p.arm('paired_RWV8_product_scene',0x108b14,4,'movw r3, #0x5638')
    data,report=p.finish();report.update(capability=CAPABILITY,media_guard_role_unchanged=True)
    return data,report
