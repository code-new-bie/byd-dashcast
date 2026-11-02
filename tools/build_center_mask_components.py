"""Build the exact 1.19.3 HMI/KZB pair from the 1.19.2 B2 pair; NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HMI_SHA = "94afdc93bb641dd2d9e9b7cd62e0ffaf4094ac7be376f605fbb05c9eaa910bc8"
KZB_SHA = "bc2929b3b061a7ac8399537f6b42810d19de29b127c13036345edf39d21c9713"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hmi", required=True, type=Path)
    parser.add_argument("--kzb", required=True, type=Path)
    parser.add_argument("--mask", type=Path, default=ROOT / "phase1/qnx/assets/b3-center/DXCenterDark.png")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.hmi, args.kzb):
        if path.is_symlink() or not path.is_file():
            raise ValueError("Use regular input component files")
    output = args.output.resolve()
    if output.exists() or args.output.is_symlink() or any(p.resolve().is_relative_to(output) for p in (args.hmi, args.kzb, args.mask)):
        raise ValueError("Use a new output directory separate from all inputs")
    hmi, kzb = args.hmi.read_bytes(), args.kzb.read_bytes()
    if hashlib.sha256(hmi).hexdigest() != HMI_SHA or hashlib.sha256(kzb).hexdigest() != KZB_SHA:
        raise ValueError("Exact matching 1.19.2 B2 HMI/KZB pair required; no output created")
    sys.path.insert(0, str(ROOT / "analysis"))
    from center_premul_mask import build_pair
    final_hmi, final_kzb, report = build_pair(hmi, kzb, args.mask)
    output.mkdir(parents=True)
    for name, raw in (("rc_hmi.exe", final_hmi), ("bic_byd_stha.kzb", final_kzb)):
        with (output / name).open("xb") as stream:
            stream.write(raw)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="CENTER_1193_MATCHED_COMPONENTS_NOT_AN_UPGRADE", **report), stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print("CENTER_1193_MATCHED_COMPONENTS_NOT_AN_UPGRADE")


if __name__ == "__main__":
    main()
