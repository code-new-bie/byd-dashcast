"""Compile and test the portable native controller. No firmware, SDK, device or network access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(root, output, sanitize=True):
    root = Path(root).resolve()
    output = Path(output).resolve()
    source = root / "phase1/qnx/native-hmi"
    if not output.is_relative_to(root / "build") or output.exists():
        raise ValueError("Use a NEW directory under this repository's build directory")
    paths = [source / "projection_background_controller.c", source / "projection_background_controller.h",
             source / "tests/background_controller_test.c"]
    for path in paths:
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError("Missing, linked or escaped native source")
    output.mkdir(parents=True)
    executable = output / "background_controller_test"
    compiler = os.environ.get("CC", "cc")
    command = [compiler, "-std=c99", "-Wall", "-Wextra", "-Werror", "-Wconversion", "-Wshadow",
               "-Wstrict-prototypes", "-Wmissing-prototypes", "-pedantic", "-g", "-O1", "-fno-omit-frame-pointer"]
    if sanitize:
        command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all"]
    command += ["-I", str(source), str(paths[0]), str(paths[2]), "-o", str(executable)]
    environment = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:halt_on_error=1",
                       UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    build = subprocess.run(command, check=True, text=True, capture_output=True, timeout=60, env=environment)
    test = subprocess.run([str(executable)], check=True, text=True, capture_output=True, timeout=30,
                          env=environment)
    names = re.findall(r"^PASS ([a-z0-9_]+)$", test.stdout, re.MULTILINE)
    count = re.search(r"^TESTS_PASSED=(\d+)$", test.stdout, re.MULTILINE)
    if count is None or len(names) != int(count[1]) or len(names) != len(set(names)) or len(names) < 35:
        raise ValueError("Incomplete or duplicate native test results")
    if build.stderr or test.stderr:
        raise ValueError("Review unexpected compiler or runtime diagnostics")
    report = dict(status="PASS_HOST_NATIVE_CONTROLLER_ONLY", tests=len(names), cases=names,
                  sanitizer_enabled=sanitize,
                  sources={str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                  qnx_compiled=False, kanzi_adapter_implemented=False, target_hmi_integrated=False,
                  fullscreen_enabled=False, vehicle_tested=False)
    (output / "REPORT.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--no-sanitize", action="store_true")
    args = parser.parse_args()
    run(args.root, args.output, not args.no_sanitize)
