"""RWV7 curved aperture + plain scene; four components, NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from build_display_fusion_components import checked_paths
from build_navigation_components import TOC_SHA

ROOT = Path(__file__).resolve().parents[1]


def checked_scene(path, output):
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ValueError("Symlink scene refused")
    path = path.resolve()
    if not path.is_file() or not 1 <= path.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError("Missing or unbounded scene")
    if output == path or output in path.parents:
        raise ValueError("Output may not replace or contain an input")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("hmi", "kzb", "scene", "toc", "receiver", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    sp = checked_scene(args.scene, out)
    toc = tp.read_bytes()
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError("Not the exact original TOC")
    sys.path.insert(0, str(ROOT / "analysis"))
    from curved_scene_layout import (build_curved_hmi, build_curved_kzb,
                                    build_plain_scene, build_curved_receiver)
    from integrated_layout import build_integrated_receiver
    from cleanup_guard_native import build_cleanup_guard_receiver
    from soft_edge_layout import build_soft_edge_receiver
    from contour_layout import build_contour_receiver
    hmi, hr = build_curved_hmi(hp.read_bytes())
    kzb, kr = build_curved_kzb(kp.read_bytes(), toc.decode("utf-8-sig").splitlines())
    scene, sr = build_plain_scene(sp.read_bytes(), kzb)
    receiver, _ = build_integrated_receiver(rp.read_bytes())
    receiver, _ = build_cleanup_guard_receiver(receiver)
    receiver, _ = build_soft_edge_receiver(receiver)
    receiver, _ = build_contour_receiver(receiver)
    receiver, rr = build_curved_receiver(receiver)
    report = dict(status="RWV7_1110_COMPONENTS_NOT_AN_UPGRADE", hmi=hr, kzb=kr,
                  scene=sr, receiver=rr, mcu_os_boot_unchanged=True,
                  real_kanzi_executed=False, vehicle_tested=False)
    out.mkdir()
    for name, data in (("rc_hmi.exe", hmi), ("bic_byd_stha.kzb", kzb),
                       ("3dscreen.kzb", scene), ("receiver", receiver)):
        with (out / name).open("xb") as stream:
            stream.write(data)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(report["status"])


if __name__ == "__main__":
    main()
