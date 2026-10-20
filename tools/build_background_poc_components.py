"""Exact-input background PoC components, NOT a flashable upgrade or fullscreen."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def prepare_paths(hmi, receiver, output):
    for path in (hmi, receiver, output):
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError("Symlink inputs/outputs are not supported")
    hmi, receiver, output = hmi.resolve(), receiver.resolve(), output.resolve()
    if not hmi.is_file() or hmi.stat().st_size != 11289671:
        raise ValueError("Exact-size operator-provided HMI required")
    if not receiver.is_file() or receiver.stat().st_size != 79808:
        raise ValueError("Exact-size operator-provided 0.8.2 receiver required")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("An existing parent and NEW output directory are required")
    return hmi, receiver, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hmi", type=Path, required=True)
    parser.add_argument("--receiver", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    hmi_path, receiver_path, output = prepare_paths(args.hmi, args.receiver, args.output)
    sys.path.insert(0, str(ROOT / "analysis"))
    from background_poc_native import apply_hmi_overlay, apply_receiver_overlay
    hmi, hmi_report = apply_hmi_overlay(hmi_path.read_bytes())
    receiver, receiver_report = apply_receiver_overlay(receiver_path.read_bytes())
    output.mkdir()
    for name, data in (("rc_hmi.exe", hmi), ("instrument-receiver", receiver)):
        with (output / name).open("xb") as stream: stream.write(data)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump({"status": "COMPONENTS_ONLY_NOT_AN_UPGRADE", "hmi": hmi_report,
                   "receiver": receiver_report, "fullscreen_enabled": False, "runtime_tested": False}, stream, indent=2)
        stream.write("\n")
    print("COMPONENTS_ONLY_NOT_AN_UPGRADE; fullscreen=false; runtime_tested=false")


if __name__ == "__main__": main()
