"""Exact operator-provided inputs -> RWV4/1.7.1 components, NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from build_display_fusion_components import checked_paths
from build_navigation_components import TOC_SHA

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("hmi", "kzb", "toc", "receiver", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    toc = tp.read_bytes()
    if hashlib.sha256(toc).hexdigest() != TOC_SHA:
        raise ValueError("Exact original TOC required")
    sys.path.insert(0, str(ROOT / "analysis"))
    from integrated_layout import build_integrated_hmi, build_integrated_receiver
    from compact_info_layout import build_compact_info_kzb
    hmi, hr = build_integrated_hmi(hp.read_bytes())
    kzb, kr = build_compact_info_kzb(kp.read_bytes(), toc.decode("utf-8-sig").splitlines())
    receiver, rr = build_integrated_receiver(rp.read_bytes())
    out.mkdir()
    for name, data in (("rc_hmi.exe", hmi), ("bic_byd_stha.kzb", kzb), ("receiver", receiver)):
        with (out / name).open("xb") as stream:
            stream.write(data)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="RWV4_171_COMPONENTS_NOT_AN_UPGRADE", hmi=hr, kzb=kr, receiver=rr,
                       os_mcu_boot_usb_unchanged=True, original_kanzi_tested=False), stream, indent=2)
        stream.write("\n")
    print("RWV4_171_COMPONENTS_NOT_AN_UPGRADE")


if __name__ == "__main__":
    main()
