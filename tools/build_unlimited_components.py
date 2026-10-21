"""Exact 1.0.0 receiver + original inspected HMI -> 1.0.1 components, NOT an upgrade ZIP."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def prepare_paths(receiver, hmi, output):
    for path in (receiver, hmi, output):
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError("Symlink input/output refused")
    receiver, hmi, output = receiver.resolve(), hmi.resolve(), output.resolve()
    if not receiver.is_file() or receiver.stat().st_size != 79808:
        raise ValueError("Exact-size 1.0.0 receiver required")
    if not hmi.is_file() or not 1 <= hmi.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError("Bounded original inspected HMI required, not an already patched executable")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("Existing parent and NEW output directory required")
    return receiver, hmi, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receiver", type=Path, required=True)
    parser.add_argument("--hmi", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receiver_path, hmi_path, output = prepare_paths(args.receiver, args.hmi, args.output)
    sys.path.insert(0, str(ROOT / "analysis"))
    from unlimited_native import apply_unlimited_receiver, build_unlimited_hmi
    receiver, rr = apply_unlimited_receiver(receiver_path.read_bytes())
    hmi, hr = build_unlimited_hmi(hmi_path.read_bytes())
    output.mkdir()
    for name, data in (("instrument-receiver", receiver), ("rc_hmi.exe", hmi)):
        with (output / name).open("xb") as stream: stream.write(data)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="UNLIMITED_COMPONENTS_ONLY_NOT_AN_UPGRADE", receiver=rr, hmi=hr,
                       product_total_duration_seconds=None, product_frame_count_limit=None, product_byte_count_limit=None,
                       protocol_version=3, owned_renewal=True, independent_expiry_ms=5000, heartbeat_ms=15000,
                       physical_composition_tested=False, cable_hot_switch_hardware_tested=False), stream, indent=2)
        stream.write("\n")
    print("UNLIMITED_COMPONENTS_ONLY_NOT_AN_UPGRADE; no total budget; hardware_tested=false")


if __name__ == "__main__": main()
