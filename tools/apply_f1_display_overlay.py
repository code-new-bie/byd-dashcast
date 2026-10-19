"""Apply the reviewed F1 overlay to a self-supplied exact receiver, NOT a firmware packer."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def prepare_paths(input_path, output_path):
    report_path = output_path.with_suffix(output_path.suffix + ".json")
    if input_path.is_symlink() or output_path.is_symlink() or report_path.is_symlink():
        raise ValueError("Symlink inputs/outputs are not permitted")
    source, output, report = input_path.resolve(), output_path.resolve(), report_path.resolve()
    if not source.is_file() or source.stat().st_size != 79808 or source == output:
        raise ValueError("An exact-size regular receiver input is required")
    if not output.parent.is_dir() or output.exists() or report.exists():
        raise ValueError("Existing parent and fresh output/report paths are required")
    return source, output, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source, output, report = prepare_paths(args.input, args.output)
    sys.path.insert(0, str(ROOT / "analysis"))
    from f1_display_probe_native import apply_probe_overlay
    data, evidence = apply_probe_overlay(source.read_bytes())
    with output.open("xb") as stream:
        stream.write(data)
    with report.open("x", encoding="utf-8") as stream:
        json.dump(evidence, stream, indent=2)
        stream.write("\n")
    print(json.dumps({"status": "F1_OVERLAY_ONLY_NOT_A_FLASH_PACKAGE", "receiver": evidence["output_sha256"],
                      "fullscreen_enabled": False, "runtime_tested": False}))


if __name__ == "__main__":
    main()
