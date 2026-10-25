"""Exact operator-supplied inputs -> RWV6/1.10.0 components, NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path
from build_display_fusion_components import checked_paths
from build_navigation_components import TOC_SHA

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ('hmi','kzb','toc','receiver','output'):
        p.add_argument('--'+n,type=Path,required=True)
    a=p.parse_args()
    hp,kp,tp,rp,out=checked_paths(a.hmi,a.kzb,a.toc,a.receiver,a.output)
    toc=tp.read_bytes()
    if hashlib.sha256(toc).hexdigest()!=TOC_SHA:
        raise ValueError('Exact original TOC required')
    sys.path.insert(0,str(ROOT/'analysis'))
    from integrated_layout import build_integrated_receiver
    from cleanup_guard_native import build_cleanup_guard_receiver
    from soft_edge_layout import build_soft_edge_receiver
    from contour_layout import build_contour_hmi,build_contour_kzb,build_contour_receiver
    hmi,hr=build_contour_hmi(hp.read_bytes())
    kzb,kr=build_contour_kzb(kp.read_bytes(),toc.decode('utf-8-sig').splitlines())
    receiver,_=build_integrated_receiver(rp.read_bytes())
    receiver,_=build_cleanup_guard_receiver(receiver)
    receiver,_=build_soft_edge_receiver(receiver)
    receiver,rr=build_contour_receiver(receiver)
    out.mkdir()
    for n,data in (('rc_hmi.exe',hmi),('bic_byd_stha.kzb',kzb),('receiver',receiver)):
        with (out/n).open('xb') as stream:
            stream.write(data)
    with (out/'REPORT.json').open('x',encoding='utf-8') as stream:
        json.dump(dict(status='RWV6_1100_COMPONENTS_NOT_AN_UPGRADE',hmi=hr,kzb=kr,receiver=rr,
            os_mcu_boot_unchanged=True,vehicle_and_real_kanzi_tested=False),stream,indent=2)
        stream.write('\n')
    print('RWV6_1100_COMPONENTS_NOT_AN_UPGRADE')


if __name__=='__main__':main()
