"""RWVE rim/background components. Exact original inputs only; NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path
from build_display_fusion_components import checked_paths
from build_curved_scene_components import checked_scene
from build_navigation_components import TOC_SHA

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    sp = checked_scene(args.scene, out)
    toc = tp.read_bytes()
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError('Wrong original TOC')
    sys.path.insert(0, str(ROOT/'analysis'))
    from axes_rim_layout import build_axes_hmi, build_axes_kzb, build_plain_scene, build_axes_receiver
    from product_scene_layout import build_product_receiver
    from integrated_layout import build_integrated_receiver
    from cleanup_guard_native import build_cleanup_guard_receiver
    from soft_edge_layout import build_soft_edge_receiver
    from contour_layout import build_contour_receiver
    from curved_scene_layout import build_curved_receiver
    hmi, hr = build_axes_hmi(hp.read_bytes())
    kzb, kr = build_axes_kzb(kp.read_bytes(), toc.decode('utf-8-sig').splitlines())
    scene, sr = build_plain_scene(sp.read_bytes(), kzb)
    receiver, _ = build_integrated_receiver(rp.read_bytes())
    for step in (build_cleanup_guard_receiver, build_soft_edge_receiver, build_contour_receiver,
                 build_curved_receiver, build_product_receiver):
        receiver, _ = step(receiver)
    receiver, rr = build_axes_receiver(receiver)
    out.mkdir()
    for name, raw in (('rc_hmi.exe', hmi), ('bic_byd_stha.kzb', kzb), ('3dscreen.kzb', scene), ('receiver', receiver)):
        with (out/name).open('xb') as stream:
            stream.write(raw)
    with (out/'REPORT.json').open('x', encoding='utf-8') as stream:
        json.dump(dict(status='RWVE_1126_COMPONENTS_NOT_AN_UPGRADE', hmi=hr, kzb=kr, scene=sr,
                       receiver=rr, vehicle_tested=False, real_kanzi_executed=False), stream,
                  ensure_ascii=False, indent=2)
    print('RWVE_1126_COMPONENTS_NOT_AN_UPGRADE')

if __name__ == '__main__':
    main()
