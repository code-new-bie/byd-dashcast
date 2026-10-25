"""RWV7: curved video aperture and projection-only plain-scene suppression.

The video bounding rectangle is not the curved aperture or its safe UI box.
Only the ADAS scene's static Plane and ordinary Car are suppressed; warning,
target-vehicle, lane-signal, radar and door/safety resources remain untouched.
"""
import io
import math
import struct
from functools import lru_cache

from PIL import Image
from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES
from contour_layout import (build_contour_kzb, ContourSource, contour, smooth,
    FRAME_TOP as PRIOR_TOP, FRAME_BOTTOM as PRIOR_BOTTOM)
from background_poc_native import apply_hmi_overlay
from display_fusion import PROPERTY, encode_manager, text
from integrated_layout import CENTER_INNER, CENTER_OUTER, CENTER_SOURCE, SMALL_MANAGER
from navigation_layout import dictionary, repack, digest, words, srt
from ref2_noauth_native import Patch

CAPABILITY=0x52575637
FRAME_TOP,FRAME_BOTTOM='DYTop','DYBottom'
# Full sharp source/encoded bounding rectangle; matte clips its curved outline.
FULL_INNER=(64,112,1792,432)
FULL_SOURCE=(1792,432)
FULL_SAFE=(256,144,1408,352)
SCENE_MANAGER='/State Managers/DCPlainScene'
SCENE_RESOURCE='/Prefabs/Prefab_ADAS'
SCENE_SHA='18d817c89f07e7171a26e67ec4a1f567d76e8ae7b61c6c3383ee92882be8323c'
PLAIN_TARGETS=('Viewport 2D/Car','Viewport 2D/Scene/RoadRoot/Plane')
F=lambda v:struct.pack('<f',v)


def encoded_inner(center):
    if not center:
        return FULL_INNER
    x,y,w,h=CENTER_OUTER;a,b,c,d=CENTER_INNER
    return ((a-x)*1920/w,(b-y)*720/h,c*1920/w,d*720/h)


def aperture_alpha(x,y,outline):
    """Curved taper follows OEM glass, not the rectangular safe-UI boundary."""
    t=max(0.,min(1.,(y-112)/432))
    nx=abs(x-960)/960
    side=max(outline[max(0,min(719,int(y)))]+12.,64+52*t+64*t*t)
    left=1-smooth((x-side)/32)
    right=1-smooth((1920-x-side)/32)
    top=1-smooth((y-(104+8*nx*nx))/32)
    bottom=smooth((y-(512-14*(1-nx*nx)))/32)
    return max(left,right,top,bottom)


@lru_cache(maxsize=2)
def curved_frames(original):
    d=KzbDirectory(original);outline=contour(original);result={}
    for theme in ('Dark','Light'):
        rgba=struct.unpack_from('<4f',d.read('/Brushes/Color Brush_BgBottom_'+theme),16)
        rgb=tuple(round(v*255) for v in rgba[:3]);image=Image.new('RGBA',(960,360));pixels=image.load()
        for y in range(360):
            for x in range(960):
                pixels[x,y]=(*rgb,round(aperture_alpha(x*2+1,y*2+1,outline)*255))
        stream=io.BytesIO();image.save(stream,'PNG',compress_level=9)
        result['/Resource Files/Images/DXFull'+theme+'.png']=words(0,0,0,0,len(stream.getvalue()))+stream.getvalue()
    return result


def build_curved_kzb(original,toc):
    base,prior=build_contour_kzb(original,toc)
    d=KzbDirectory(base);o=KzbDirectory(original)
    strings=[s.replace(PRIOR_TOP,FRAME_TOP).replace(PRIOR_BOTTOM,FRAME_BOTTOM) for s in d.dictionary('/$strings')]
    props=d.dictionary('/$property_dictionary')
    mode_rules=[('.', 'Node2D.RenderTransformation',srt(1450,156)),('ADAS_SmallBG','Node.Opacity',F(0))]
    for p,_,_ in mode_rules:
        if p not in strings:strings.append(p)
    native=o.read('/State Managers/01_ThemeLightMode_EffectOnRoot')
    assert struct.unpack_from('<4I',native)==(0,0,0,1) and native[-4:]==words(0)
    group=encode_manager(strings,props,PROPERTY,[('DCIdle',0,[]),('DCFull',1,mode_rules),('DCCenter',2,mode_rules)])
    changes={'/$strings':dictionary(strings),SMALL_MANAGER:native[:12]+words(2)+native[16:]+group[16:]}
    changes.update(curved_frames(original))
    output=repack(d,changes,{})
    current=KzbDirectory(output)
    for n in d.records:
        if n not in changes and d.read(n)!=current.read(n):raise ValueError('Unrelated primary resource changed: '+n)
    return output,dict(input_sha256=digest(original),prior_sha256=digest(base),output_sha256=digest(output),
        changed_resources=list(changes),full_video_bounds=FULL_INNER,full_safe=FULL_SAFE,
        center_inner=CENTER_INNER,center_outer=CENTER_OUTER,plain_small_background_hidden=True,
        geometry='OEM-glass constrained curved trapezoid; sharp picture beyond safe rectangle',
        original_alert_states_unchanged=True,real_kanzi_executed=False,prior=prior)


def append_scene_manager(d,raw,offset,strings,props,kind_by_name):
    """Insert only the root manager link; original children/bindings stay exact."""
    c=Cursor(raw,offset);name,base,cls,count=c.word(),c.word(),c.word(),c.word()
    if d.dictionary('/$strings')[name]!='Prefab_ADAS':raise ValueError('Scene root drift')
    kinds=custom_types(d)
    for _ in range(count):
        key=c.word();prop=d.dictionary('/$property_dictionary')[key]
        if prop=='Node.StateManager':raise ValueError('Existing scene root manager cannot be replaced')
        c.variant(kinds[prop]['kind'] if prop in kinds else kind_by_name[prop])
    insertion=words(props.index('Node.StateManager'))+text('kzb://3dscreen'+SCENE_MANAGER)
    return raw[:offset+12]+words(count+1)+raw[offset+16:c.offset]+insertion+raw[c.offset:]


def build_plain_scene(original,primary):
    if digest(original)!=SCENE_SHA:raise ValueError('Not the exact inspected secondary scene')
    d=KzbDirectory(original);p=KzbDirectory(primary)
    if d.project!='3dscreen':raise ValueError('Wrong secondary scene project')
    strings=d.dictionary('/$strings');props=d.dictionary('/$property_dictionary')
    if PROPERTY in props or SCENE_MANAGER in d.records:raise ValueError('Scene already patched')
    if 'Car' not in strings or 'Plane' not in strings or 'RoadRoot' not in strings:
        raise ValueError('Expected distinct plain-scene children missing')
    for name in (PROPERTY,'DCIdle','DCFull','DCCenter',*PLAIN_TARGETS):
        if name not in strings:strings.append(name)
    props.append(PROPERTY)
    primary_props=p.dictionary('/$property_dictionary')
    kinds={primary_props[i]:kind for i,kind in STANDARD_NODE_TYPES.items()}
    control=custom_types(p)[PROPERTY]
    if control['kind']!=1 or control['inherited']!=1 or control['default']!=0:
        raise ValueError('Main inherited mode descriptor drift')
    raw=d.read(SCENE_RESOURCE)
    # Exact proven original root is 95 bytes followed by its children; validate
    # its scalar properties while inserting before the original binding count.
    root=append_scene_manager(d,raw,4,strings,props,kinds)
    types=d.read('/$property_types')
    types=words(struct.unpack_from('<I',types)[0]+1)+types[4:]+words(strings.index(PROPERTY),
        control['kind'],control['flags'],control['inherited'],0)
    rules=[(PLAIN_TARGETS[0],'Node.Opacity',F(0)),(PLAIN_TARGETS[1],'Node.Visible',words(0))]
    manager=encode_manager(strings,props,PROPERTY,[('DCIdle',0,[]),('DCFull',1,rules),('DCCenter',2,rules)])
    cls=d.records['/State Managers/ThemeLightMode_Switch']['class_id'] if '/State Managers/ThemeLightMode_Switch' in d.records else next(
        r['class_id'] for n,r in d.records.items() if n.startswith('/State Managers/'))
    changes={'/$strings':dictionary(strings),'/$property_dictionary':dictionary(props),
        '/$property_types':types,SCENE_RESOURCE:root}
    result=repack(d,changes,{SCENE_MANAGER:(cls,manager)})
    current=KzbDirectory(result)
    for n in d.records:
        if n not in changes and d.read(n)!=current.read(n):raise ValueError('Unrelated scene resource changed: '+n)
    return result,dict(input_sha256=digest(original),output_sha256=digest(result),changed_resources=list(changes),
        added_resources=[SCENE_MANAGER],suppressed_instances=list(PLAIN_TARGETS),
        retained=['RoadLine3D_L','RoadLine3D_R','RoadLine3D_B','TargetVehicle*','Radar','Prefab_WarningText3D','hcecar'],
        control=control,empty_idle=True,scene_engine_executed=False)


class CurvedSceneSource:
    def read_text(self,encoding):
        source=ContourSource().read_text(encoding)
        for before,after in (('movw r0, #0x5636','movw r0, #0x5637'),
            ('.asciz "DXTop"','.asciz "DYTop"'),('.asciz "DXBottom"','.asciz "DYBottom"')):
            if source.count(before)!=1:raise ValueError('Native capability/root drift')
            source=source.replace(before,after)
        return source


def build_curved_hmi(original):
    raw,report=apply_hmi_overlay(original,source_path=CurvedSceneSource())
    report.update(capability=CAPABILITY,lease_and_safety_paths_unchanged=True)
    return raw,report


def build_curved_receiver(previous):
    p=Patch(previous,'95a7acad736a40eb1754351b8e2e99024350b5f2fceecd4c03a7e3a60f12c2fd')
    p.arm('paired_RWV7_curved_scene',0x108b14,4,'movw r3, #0x5637')
    raw,report=p.finish();report.update(capability=CAPABILITY,media_guard_role_unchanged=True)
    return raw,report
