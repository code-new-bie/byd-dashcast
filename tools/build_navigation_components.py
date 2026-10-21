"""Original inspected HMI/KZB -> 1.1.2 refined local layout, NOT an upgrade ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOC_SHA = "402c609076bc539bd552652f00fd63440f59d28e8cc908ca494f249060ad106e"


def prepare_paths(hmi, kzb, toc, output):
    for path in (hmi, kzb, toc, output):
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
            raise ValueError("Symlink input/output refused")
    hmi, kzb, toc, output = (path.resolve() for path in (hmi, kzb, toc, output))
    for path, limit in ((hmi, 16 * 1024 * 1024), (kzb, 256 * 1024 * 1024), (toc, 4 * 1024 * 1024)):
        if not path.is_file() or not 1 <= path.stat().st_size <= limit:
            raise ValueError("Missing or unbounded original component")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("Existing parent and NEW output directory required")
    return hmi, kzb, toc, output


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("hmi", "kzb", "toc", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    args = p.parse_args()
    hp, kp, tp, out = prepare_paths(args.hmi, args.kzb, args.toc, args.output)
    toc = tp.read_bytes()
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError("Not the inspected original TOC")
    sys.path.insert(0, str(ROOT / "analysis"))
    from navigation_layout_fix import build_navigation_fix_hmi
    from navigation_layout_refine import build_navigation_refined_kzb
    hmi, hr = build_navigation_fix_hmi(hp.read_bytes())
    kzb, kr = build_navigation_refined_kzb(kp.read_bytes(), toc.decode("utf-8-sig").splitlines())
    out.mkdir()
    for name, data in (("rc_hmi.exe", hmi), ("bic_byd_stha.kzb", kzb)):
        with (out / name).open("xb") as stream: stream.write(data)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="NATIVE_NAVIGATION_COMPONENTS_NOT_AN_UPGRADE", hmi=hr, kzb=kr,
                       receiver_role_os_mcu_unchanged=True, physical_layout_tested=False), stream, indent=2)
        stream.write("\n")
    print("NATIVE_NAVIGATION_COMPONENTS_NOT_AN_UPGRADE; physical_layout_tested=false")


if __name__ == "__main__": main()
