"""Exact supplied originals + 1.1.2 receiver -> 1.2.0 components, NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path
from build_navigation_components import prepare_paths, TOC_SHA

ROOT = Path(__file__).resolve().parents[1]


def checked_paths(hmi, kzb, toc, receiver, output):
    hmi, kzb, toc, output = prepare_paths(hmi, kzb, toc, output)
    if receiver.is_symlink() or any(p.is_symlink() for p in receiver.parents):
        raise ValueError("Symlink receiver refused")
    receiver = receiver.resolve()
    if not receiver.is_file() or not 1 <= receiver.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError("Missing or unbounded receiver")
    return hmi, kzb, toc, receiver, output


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("hmi", "kzb", "toc", "receiver", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    args = p.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    toc = tp.read_bytes()
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError("Not the exact original TOC")
    sys.path.insert(0, str(ROOT / "analysis"))
    from display_fusion import build_fusion_hmi, build_fusion_kzb, build_fusion_receiver
    hmi, hr = build_fusion_hmi(hp.read_bytes())
    kzb, kr = build_fusion_kzb(kp.read_bytes(), toc.decode("utf-8-sig").splitlines())
    receiver, rr = build_fusion_receiver(rp.read_bytes())
    out.mkdir()
    for name, data in (("rc_hmi.exe", hmi), ("bic_byd_stha.kzb", kzb), ("receiver", receiver)):
        with (out / name).open("xb") as stream: stream.write(data)
    report = dict(status="DISPLAY_FUSION_COMPONENTS_NOT_AN_UPGRADE", hmi=hr, kzb=kr, receiver=rr,
        role_os_mcu_unchanged=True, target_shader_driver_tested=False, physical_layout_tested=False)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2); stream.write("\n")
    print(report["status"])


if __name__ == "__main__": main()
