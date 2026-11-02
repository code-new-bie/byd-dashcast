"""Step 1: 1.17.0 native-layout and RGBA background components from exact originals; NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from component_inputs import TOC_SHA, checked_file, checked_paths

ROOT = Path(__file__).resolve().parents[1]
STATUS = 'RWVF_1170_USER_BACKGROUND_COMPONENTS_NOT_AN_UPGRADE'


def build_components(hmi, kzb, scene, toc, receiver, backgrounds):
    """Return the four 1.17 components (name -> bytes) and their report."""
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError('Wrong original TOC')
    sys.path.insert(0, str(ROOT / 'analysis'))
    from aperture_fit_overlay import build_alpha_hmi, build_runtime_kzb, build_plain_scene, build_runtime_receiver
    from product_scene_layout import build_product_receiver
    from integrated_layout import build_integrated_receiver
    from cleanup_guard_native import build_cleanup_guard_receiver
    from soft_edge_layout import build_soft_edge_receiver
    from contour_layout import build_contour_receiver
    from curved_scene_layout import build_curved_receiver
    hmi, hr = build_alpha_hmi(hmi)
    kzb, kr = build_runtime_kzb(kzb, toc.decode('utf-8-sig').splitlines(), backgrounds)
    scene, sr = build_plain_scene(scene, kzb)
    receiver, _ = build_integrated_receiver(receiver)
    for step in (build_cleanup_guard_receiver, build_soft_edge_receiver, build_contour_receiver,
                 build_curved_receiver, build_product_receiver):
        receiver, _ = step(receiver)
    receiver, rr = build_runtime_receiver(receiver)
    components = {'rc_hmi.exe': hmi, 'bic_byd_stha.kzb': kzb, '3dscreen.kzb': scene, 'receiver': receiver}
    return components, dict(hmi=hr, kzb=kr, scene=sr, receiver=rr, vehicle=False, complete_qnx_executed=False)


def checked_backgrounds(path, out):
    backgrounds = path.resolve()
    if not backgrounds.is_dir() or backgrounds.is_relative_to(out) or out.is_relative_to(backgrounds):
        raise ValueError('Keep exact user background inputs outside a new output directory')
    return backgrounds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--backgrounds', type=Path, default=ROOT / 'assets/backgrounds')
    args = parser.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    sp = checked_file(args.scene, out)
    backgrounds = checked_backgrounds(args.backgrounds, out)
    components, report = build_components(hp.read_bytes(), kp.read_bytes(), sp.read_bytes(), tp.read_bytes(),
                                          rp.read_bytes(), backgrounds)
    out.mkdir()
    for name, raw in components.items():
        with (out / name).open('xb') as stream:
            stream.write(raw)
    with (out / 'REPORT.json').open('x', encoding='utf-8') as stream:
        json.dump(dict(status=STATUS, **report), stream, ensure_ascii=False, indent=2)
    print(STATUS)


if __name__ == '__main__':
    main()
