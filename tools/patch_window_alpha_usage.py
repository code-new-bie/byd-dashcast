"""Patch an exact 1.17--1.19 HMI component; NOT a flash ZIP or a generic patch."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_SHA = "5dfef87a7786c3b6af5866b44572c795156473001f0ffa29e7a0f2d3d966b9a2"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hmi", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    # Check the supplied path before resolving so a symlink is not hidden.
    if args.hmi.is_symlink() or not args.hmi.is_file():
        raise ValueError("The HMI input must be a regular file")
    source, output = args.hmi.resolve(), args.output.resolve()
    if output.exists() or args.output.is_symlink() or source.is_relative_to(output):
        raise ValueError("Use a new output directory separate from the input")
    raw = source.read_bytes()
    if hashlib.sha256(raw).hexdigest() != BASE_SHA:
        raise ValueError("Exact inspected 1.17--1.19 HMI required; no output was created")
    sys.path.insert(0, str(ROOT / "analysis"))
    from native_usage_alpha import patch_current_hmi
    result, report = patch_current_hmi(raw)
    output.mkdir(parents=True)
    with (output / "rc_hmi.exe").open("xb") as stream:
        stream.write(result)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="USAGE_ALPHA_1191_COMPONENT_NOT_AN_UPGRADE", hmi=report),
                  stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print("USAGE_ALPHA_1191_COMPONENT_NOT_AN_UPGRADE")


if __name__ == "__main__":
    main()
