"""RWVA: sharp video throughout the inner rim; feather only at the rim itself."""
from sharp_inner_geometry import FULL_INNER,FULL_SOURCE,FULL_SAFE,CURVES,validate_sharp_carrier
from inner_ring_refinement import (CENTER_INNER,CENTER_OUTER,CENTER_SOURCE,BOARD_TOP,TOP,FEATHER,
    inner_contour,aperture_alpha,inner_frames,alpha_grid,build_inner_kzb,InnerSource,build_plain_scene,
    FRAME_TOP as PRIOR_TOP,FRAME_BOTTOM as PRIOR_BOTTOM)
from analyze_kzb_hmi import KzbDirectory
from navigation_layout import dictionary,repack,digest
from background_poc_native import apply_hmi_overlay
from ref2_noauth_native import Patch

CAPABILITY=0x5257563a
FRAME_TOP,FRAME_BOTTOM='IATop','IABottom'

def encoded_inner(center):
    if not center:return FULL_INNER
    x,y,w,h=CENTER_OUTER;a,b,c,d=CENTER_INNER
    return ((a-x)*1920/w,(b-y)*720/h,c*1920/w,d*720/h)

def build_sharp_kzb(original,toc):
    validate_sharp_carrier()
    base,prior=build_inner_kzb(original,toc);d=KzbDirectory(base)
    strings=[s.replace(PRIOR_TOP,FRAME_TOP).replace(PRIOR_BOTTOM,FRAME_BOTTOM) for s in d.dictionary('/$strings')]
    output=repack(d,{'/$strings':dictionary(strings)},{})
    return output,dict(input_sha256=digest(original),output_sha256=digest(output),prior=prior,
        changed_resources=['/$strings'],full_video_bounds=FULL_INNER,full_safe=FULL_SAFE,
        safe_box_does_not_clip_sharp_video=True,all_unmasked_pixels_use_sharp_source=True,
        decorative_circles_restored=False,real_kanzi_executed=False)

class SharpSource:
    def read_text(self,encoding):
        s=InnerSource().read_text(encoding)
        for a,b in (('movw r0, #0x5639','movw r0, #0x563a'),('.asciz "I9Top"','.asciz "IATop"'),('.asciz "I9Bottom"','.asciz "IABottom"')):
            if s.count(a)!=1:raise ValueError('Exact prior inner source required')
            s=s.replace(a,b)
        return s

def build_sharp_hmi(original):
    output,report=apply_hmi_overlay(original,source_path=SharpSource())
    report.update(capability=CAPABILITY,registered_prefab_slots=106)
    return output,report

def build_sharp_receiver(previous):
    # The public caller supplies the hash-pinned RWV8 receiver; skip the rejected
    # candidate's receiver, avoiding a hidden dependency on a private delivery.
    p=Patch(previous,'10f47f96e419cdc584d56a6ed0e394be22a46d151b606dd8f30ae655a799e221')
    p.arm('paired_RWVA_sharp_inner',0x108b14,4,'movw r3, #0x563a')
    output,report=p.finish();report.update(capability=CAPABILITY,media_guard_role_unchanged=True)
    return output,report
