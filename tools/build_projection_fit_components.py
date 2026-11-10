"""Step 1: the 1.17 layout and RGBA background components (HMI, KZB, 3D scene) from the base firmware; NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from base_firmware import read_inputs, read_release
from component_inputs import TOC_SHA, checked_file, new_output

ROOT = Path(__file__).resolve().parents[1]
STATUS = 'RWVF_1170_USER_BACKGROUND_COMPONENTS_NOT_AN_UPGRADE'


def build_components(hmi, kzb, scene, toc, backgrounds):
    """Return the three step-1 components (name -> bytes) and their report."""
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError('Wrong original TOC')
    sys.path.insert(0, str(ROOT / 'analysis'))
    from aperture_fit_overlay import build_alpha_hmi, build_runtime_kzb, build_plain_scene
    hmi, hr = build_alpha_hmi(hmi)
    kzb, kr = build_runtime_kzb(kzb, toc.decode('utf-8-sig').splitlines(), backgrounds)
    scene, sr = build_plain_scene(scene, kzb)
    components = {'rc_hmi.exe': hmi, 'bic_byd_stha.kzb': kzb, '3dscreen.kzb': scene}
    return components, dict(hmi=hr, kzb=kr, scene=sr, vehicle=False, complete_qnx_executed=False)


def checked_backgrounds(path, out):
    backgrounds = path.resolve()
    if not backgrounds.is_dir() or backgrounds.is_relative_to(out) or out.is_relative_to(backgrounds):
        raise ValueError('Keep exact user background inputs outside a new output directory')
    return backgrounds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True, help='your base firmware ZIP (see tools/base_firmware.py)')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--backgrounds', type=Path, default=ROOT / 'assets/backgrounds')
    args = parser.parse_args()
    out = new_output(args.output)
    checked_file(args.base, out, 512 * 1024 * 1024)
    backgrounds = checked_backgrounds(args.backgrounds, out)
    original = read_inputs(read_release(args.base))
    components, report = build_components(original['hmi'], original['kzb'], original['scene'], original['toc'],
                                          backgrounds)
    out.mkdir()
    for name, raw in components.items():
        with (out / name).open('xb') as stream:
            stream.write(raw)
    with (out / 'REPORT.json').open('x', encoding='utf-8') as stream:
        json.dump(dict(status=STATUS, **report), stream, ensure_ascii=False, indent=2)
    print(STATUS)


if __name__ == '__main__':
    main()
