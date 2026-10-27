"""RWV9 inner rim. Full mode keeps decorative gauges hidden.

Photo-derived shape anchored to the native top line/footer, not a claim of
pixel-perfect native rendering. No photograph or vehicle data is embedded.
"""
import io
import math
import struct
from functools import lru_cache
from PIL import Image
from analyze_kzb_hmi import KzbDirectory
from navigation_layout import KZB_SHA,digest,dictionary,repack,words
from contour_layout import smooth
from product_scene_layout import (build_product_kzb,ProductSceneSource,build_plain_scene,
    FRAME_TOP as PRIOR_TOP,FRAME_BOTTOM as PRIOR_BOTTOM,CENTER_INNER,CENTER_OUTER,CENTER_SOURCE)
from background_poc_native import apply_hmi_overlay
from ref2_noauth_native import Patch

CAPABILITY=0x52575639
FRAME_TOP,FRAME_BOTTOM='I9Top','I9Bottom'
# Sharp readable content is inside the curved opening. Existing 32x16 fill
# supplies the sides/corners without stretching high-resolution glyphs.
FULL_INNER=(320,176,1280,336)
FULL_SOURCE=FULL_INNER[2:]
FULL_SAFE=FULL_INNER
BOARD_TOP,TOP=553,124
FEATHER=24.
# Tangent-continuous left rim; mirrored around x960. Geometry only; the
# decorative source images are not restored in the full-mode state manager.
CURVES=(
    ((450.,124.),(340.,124.),(150.,158.),(150.,226.)),
    ((150.,226.),(150.,320.),(218.,477.),(239.,510.)),
    ((239.,510.),(260.,543.),(270.,553.),(310.,553.)),
)

def encoded_inner(center):
    if not center:return FULL_INNER
    x,y,w,h=CENTER_OUTER;a,b,c,d=CENTER_INNER
    return ((a-x)*1920/w,(b-y)*720/h,c*1920/w,d*720/h)

@lru_cache(maxsize=1)
def inner_contour():
    points=[]
    for c in CURVES:
        for i in range(1025):
            t=i/1024;s=1-t
            points.append(tuple(s*s*s*c[0][k]+3*s*s*t*c[1][k]+3*s*t*t*c[2][k]+t*t*t*c[3][k] for k in (0,1)))
    rows=[];j=0
    for y in range(TOP,BOARD_TOP+1):
        while j+1<len(points) and points[j+1][1]<y:j+=1
        a,b=points[j],points[min(len(points)-1,j+1)]
        t=0 if b[1]==a[1] else (y-a[1])/(b[1]-a[1])
        x=a[0]+t*(b[0]-a[0]);rows.append((x,1920-x))
    return tuple(rows)

def _edt_line(f):
    """Exact squared Euclidean distance via a lower parabola envelope."""
    n=len(f);v=[0]*n;z=[-math.inf]+[math.inf]*n;k=0
    for q in range(1,n):
        s=((f[q]+q*q)-(f[v[k]]+v[k]*v[k]))/(2*(q-v[k]))
        while s<=z[k]:
            k-=1;s=((f[q]+q*q)-(f[v[k]]+v[k]*v[k]))/(2*(q-v[k]))
        k+=1;v[k]=q;z[k]=s;z[k+1]=math.inf
    result=[];k=0
    for q in range(n):
        while z[k+1]<q:k+=1
        result.append((q-v[k])**2+f[v[k]])
    return result

@lru_cache(maxsize=1)
def alpha_grid():
    # Each matte pixel represents two native design pixels. Fade only inward:
    # no outward halo, and the exact same alpha field for both original themes.
    outline=inner_contour();w,h=960,360;far=1e9;rows=[]
    for y in range(h):
        lo,hi=2*y,2*y+2
        # The complete texel footprint must lie inside the rim. Sampling only
        # its center leaks a few pixels outward at the steep top corner.
        left=max(outline[lo-TOP][0],outline[hi-TOP][0]) if TOP<=lo and hi<BOARD_TOP else 960.
        rows.append(_edt_line([far if left<2*x+1<1920-left else 0 for x in range(w)]))
    for x in range(w):
        column=_edt_line([rows[y][x] for y in range(h)])
        for y in range(h):rows[y][x]=1-smooth(max(0.,2*math.sqrt(column[y])-1)/FEATHER)
    return tuple(tuple(row) for row in rows)

def aperture_alpha(x,y,outline=None):
    if not 0<=x<1920 or not 0<=y<720:return 1.
    return alpha_grid()[int(y)//2][int(x)//2]

@lru_cache(maxsize=2)
def inner_frames(original):
    if digest(original)!=KZB_SHA:raise ValueError('Exact original rim/theme resources required')
    d=KzbDirectory(original);changes={};alpha=alpha_grid()
    for theme in ('Dark','Light'):
        rgb=tuple(round(v*255) for v in struct.unpack_from('<4f',d.read('/Brushes/Color Brush_BgBottom_'+theme),16)[:3])
        im=Image.new('RGBA',(960,360));im.putdata([(*rgb,round(255*a)) for row in alpha for a in row])
        stream=io.BytesIO();im.save(stream,'PNG',compress_level=9);raw=stream.getvalue()
        changes['/Resource Files/Images/DXFull'+theme+'.png']=words(0,0,0,0,len(raw))+raw
    return changes

def build_inner_kzb(original,toc):
    base,prior=build_product_kzb(original,toc);d=KzbDirectory(base)
    strings=[s.replace(PRIOR_TOP,FRAME_TOP).replace(PRIOR_BOTTOM,FRAME_BOTTOM) for s in d.dictionary('/$strings')]
    changes={'/$strings':dictionary(strings),**inner_frames(original)}
    out=repack(d,changes,{})
    return out,dict(input_sha256=digest(original),output_sha256=digest(out),prior=prior,
        changed_resources=list(changes),full_video_bounds=FULL_INNER,full_safe=FULL_SAFE,
        curve_source='Photo inner-rim shape; native line y124 and footer y553 anchors',
        curve_control_points=CURVES,feather_native_pixels=FEATHER,decorative_circles_restored=False,
        footer_top=BOARD_TOP,native_vehicle_state_unchanged=True,real_kanzi_executed=False)

class InnerSource:
    def read_text(self,encoding):
        s=ProductSceneSource().read_text(encoding)
        for a,b in (('movw r0, #0x5638','movw r0, #0x5639'),('.asciz "DZTop"','.asciz "I9Top"'),('.asciz "DZBottom"','.asciz "I9Bottom"')):
            if s.count(a)!=1:raise ValueError('Exact RWV8 source required')
            s=s.replace(a,b)
        return s

def build_inner_hmi(original):
    out,r=apply_hmi_overlay(original,source_path=InnerSource());r.update(capability=CAPABILITY,registered_prefab_slots=106)
    return out,r

def build_inner_receiver(previous):
    p=Patch(previous,'10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    p.arm('paired_RWV9_inner_rim',0x108b14,4,'movw r3, #0x5639')
    out,r=p.finish();r.update(capability=CAPABILITY,media_guard_role_unchanged=True)
    return out,r
