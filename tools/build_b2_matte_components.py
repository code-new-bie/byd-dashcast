"""Build the exact B2 HMI/mask pair from 1.17--1.19 components; NOT a flash ZIP."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HMI_SHA = "5dfef87a7786c3b6af5866b44572c795156473001f0ffa29e7a0f2d3d966b9a2"
KZB_SHA = "15822ca13bf5b2c8ca07d4c5a64bec6af1a1562117def4211af951bb46da0a63"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hmi", required=True, type=Path)
    parser.add_argument("--kzb", required=True, type=Path)
    parser.add_argument("--masks", type=Path, default=ROOT / "phase1/qnx/assets/b2")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.hmi, args.kzb):
        if path.is_symlink() or not path.is_file():
            raise ValueError("Use regular input component files")
    output = args.output.resolve()
    if output.exists() or args.output.is_symlink() or any(p.resolve().is_relative_to(output) for p in (args.hmi, args.kzb, args.masks)):
        raise ValueError("Use a new output directory separate from all inputs")
    hmi, kzb = args.hmi.read_bytes(), args.kzb.read_bytes()
    if hashlib.sha256(hmi).hexdigest() != HMI_SHA or hashlib.sha256(kzb).hexdigest() != KZB_SHA:
        raise ValueError("Exact matching 1.17--1.19 HMI/KZB pair required; no output created")
    sys.path.insert(0, str(ROOT / "analysis"))
    from b2_matte import build_pair
    final_hmi, final_kzb, report = build_pair(hmi, kzb, args.masks)
    output.mkdir(parents=True)
    for name, raw in (("rc_hmi.exe", final_hmi), ("bic_byd_stha.kzb", final_kzb)):
        with (output / name).open("xb") as stream:
            stream.write(raw)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="B2_1192_MATCHED_COMPONENTS_NOT_AN_UPGRADE", **report), stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print("B2_1192_MATCHED_COMPONENTS_NOT_AN_UPGRADE")


if __name__ == "__main__":
    main()
