"""Exact 0.9.1 receiver/config to 0.10.0 components; not a flashable FULL package."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def prepare_paths(receiver, config, output):
    for path in (receiver, config, output):
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError("Symlink inputs/outputs are not supported")
    receiver, config, output = receiver.resolve(), config.resolve(), output.resolve()
    if not receiver.is_file() or receiver.stat().st_size != 79808:
        raise ValueError("Exact-size operator-provided 0.9.1 receiver required")
    if not config.is_file() or not 0 < config.stat().st_size <= 4096:
        raise ValueError("Original bounded application.cfg required")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("An existing parent and NEW output directory are required")
    return receiver, config, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receiver", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receiver_path, config_path, output = prepare_paths(args.receiver, args.config, args.output)
    sys.path.insert(0, str(ROOT / "analysis"))
    from composition_native import apply_composition_overlay, configure_alpha
    receiver, report = apply_composition_overlay(receiver_path.read_bytes())
    config = configure_alpha(config_path.read_bytes())
    output.mkdir()
    for name, data in (("instrument-receiver", receiver), ("application.cfg", config)):
        with (output / name).open("xb") as stream:
            stream.write(data)
    with (output / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status="COMPONENTS_ONLY_NOT_AN_UPGRADE", receiver=report,
                       alpha_bits_requested=8, fullscreen_enabled=True, hold_ms=5000,
                       hmi_091_bridge_required=True, physical_composition_tested=False), stream, indent=2)
        stream.write("\n")
    print("COMPONENTS_ONLY_NOT_AN_UPGRADE; fullscreen_trial=5s; physical_composition_tested=false")


if __name__ == "__main__":
    main()
