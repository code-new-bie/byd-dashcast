"""Rebuild only the hash-pinned 0.4.4 App payload from user-owned local inputs.

Never flashes, changes MCU/OS, overwrites inputs, signs an APK or certifies runtime.
Output is a local packaging verification, not an independently certified delivery.
"""
import argparse
import copy
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "analysis"))
from sv_container import unpack_sv, pack_sv, verify_entries

PREFIX = "base/app/dip-instrument"
ORIGINAL_APP_HASH = "d4969ab876f27d3a40cd04658a447d46da636f98f153889a5b520c34207062f5"
ORIGINAL_MCU_HASH = "1ac756c66b26e16c2d1f4b2a93a6bd4ea0722fdfe7df3a0f51ab572c7179f337"
REFERENCE_OS_HASH = "779166fadb80a0cb100e358a7563e827633b8ba61286ae98880a482b08c2b9c7"
APP_OUTPUT_HASH = "a26f7926925db7696f3aa0d3c9360103393e952cfc46dd89c57b520fb1da5ffa"
MODULE_HASHES = {
    "instrument-receiver": "80f38d15c1b80b5cfb0fd3ca34209d6db63b0b17b605ab65523b8bafe8c2407e",
    "instrument-usb": "734cfe1796ee040a57237e21bbf0563514d81a6d8c4114cc8d958586183d0e4b",
    "startup.ksh": "dcc08669bdef4077196019df5956a45f7b29abc70860e40f71f8c2c81079a6f4",
    "run-cast.ksh": "268ee0e537d6f78dc9715d44d5c3c8e5504aad794480a9708cfb77c9515f6b83",
    "cast-mode.ksh": "f8cb30a9c6d9ca610ccb19c00d0360ea9e0794d2f9ad95f03a531229514d0c82",
    "usb-mode.ksh": "0438f98fefe276ad8da68b5f198511905cb613e4bd0f30caf2f5bed888e9729b",
}
SIDECARS = ("RcAppVersion", "kzbVersion", "version_svp")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_unique(archive, name):
    matches = [entry for entry in archive.infolist() if entry.filename == name]
    if len(matches) != 1:
        raise ValueError("Missing or duplicate required archive member: " + name)
    if matches[0].file_size > 256 * 1024 * 1024:
        raise ValueError("Oversized firmware input member")
    return archive.read(matches[0])


def build(original, reference, output):
    original, reference, output = (Path(value).resolve() for value in (original, reference, output))
    if output.exists():
        raise FileExistsError("Never overwrite an existing packaging output")
    if original.is_relative_to(output) or reference.is_relative_to(output):
        raise ValueError("Inputs must not be inside the new output directory")
    with zipfile.ZipFile(original) as archive:
        baseline = read_unique(archive, "app.sv")
        mcu = read_unique(archive, "mcu.sv")
        if digest(baseline) != ORIGINAL_APP_HASH or digest(mcu) != ORIGINAL_MCU_HASH:
            raise ValueError("Original App/MCU input differs from the inspected baseline")
        app, tail = unpack_sv(baseline)
        sidecars = {name: read_unique(archive, name) for name in SIDECARS}
    with zipfile.ZipFile(reference) as archive:
        if digest(read_unique(archive, "release/os.sv")) != REFERENCE_OS_HASH:
            raise ValueError("Reference OS is not the inspected ref2 version")
        ref_app, _ = unpack_sv(read_unique(archive, "release/app.sv"))
        if any(read_unique(archive, "release/" + name) != value for name, value in sidecars.items()):
            raise ValueError("Input version sidecars differ")
    if len(app) != 46 or any("fission-services" in name or name.startswith(PREFIX) for name in app):
        raise ValueError("Expected the clean domestic 46-member App baseline")
    from ref2_noauth_native import build_receiver, build_usb
    receiver, receiver_report = build_receiver(ref_app[PREFIX + "/instrument-receiver"][1])
    usb, usb_report = build_usb(ref_app[PREFIX + "/instrument-usb"][1])
    modules = {"instrument-receiver": receiver, "instrument-usb": usb}
    for name in ("startup.ksh", "run-cast.ksh", "cast-mode.ksh", "usb-mode.ksh"):
        modules[name] = (ROOT / "phase1/qnx/ref2-noauth" / name).read_text(encoding="utf-8").encode()
    if {name: digest(data) for name, data in modules.items()} != MODULE_HASHES:
        raise ValueError("Adapted module differs from 0.4.4; review instead of relabelling a changed payload")
    candidate = copy.deepcopy(app)
    directory = next(name for name in ref_app if name.rstrip("/") == PREFIX)
    candidate[directory] = copy.deepcopy(ref_app[directory])
    for name, data in modules.items():
        key = PREFIX + "/" + name
        meta = copy.copy(ref_app[key][0])
        if not meta.isfile() or meta.mode != 0o700 or meta.uid != 0 or meta.gid != 0:
            raise ValueError("Unexpected module owner/type/mode")
        if name.endswith(".ksh") and (not data.startswith(b"#!/bin/sh\n") or b"\r" in data or b"\0" in data):
            raise ValueError("Unexpected script encoding")
        meta.size = len(data)
        candidate[key] = (meta, data)
    if len(candidate) != 53 or set(candidate) - set(app) != {directory} | {PREFIX + "/" + name for name in modules}:
        raise ValueError("Unexpected App members")
    packed = pack_sv(candidate, tail)
    roundtrip, roundtrip_tail = unpack_sv(packed)
    verify_entries(app, {name: roundtrip[name] for name in app})
    if roundtrip_tail[:0x120] != tail[:0x120] or roundtrip_tail[0x148:] != tail[0x148:]:
        raise ValueError("SV installation metadata changed")
    if digest(packed) != APP_OUTPUT_HASH:
        raise ValueError("Final App differs from the independently audited 0.4.4 payload")
    output.mkdir(parents=True)
    package = output / "dashcast-phase1-app-0.4.4-LOCAL-REBUILD-NOT-CERTIFIED.zip"
    with zipfile.ZipFile(package, "x", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("release/app.sv", packed)
        for name, data in sidecars.items():
            archive.writestr("release/" + name, data)
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None or len(archive.infolist()) != 4:
            raise ValueError("Output ZIP integrity/range failure")
        if digest(archive.read("release/app.sv")) != APP_OUTPUT_HASH:
            raise ValueError("Output App readback mismatch")
    report = dict(status="PASS_LOCAL_APP_REPRODUCTION_RUNTIME_NOT_TESTED", version="0.4.4",
                  app_sha256=digest(packed), original_members_preserved=46, app_members=53,
                  os_in_package=False, mcu_in_package=False, apk_signature_checked=False,
                  modules={name: digest(data) for name, data in modules.items()},
                  receiver_adaptation=receiver_report, usb_adaptation=usb_report)
    reports = output / "reports"
    reports.mkdir()
    (reports / "APP_REPRODUCTION.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return {key: report[key] for key in ("status", "app_sha256", "original_members_preserved", "os_in_package", "mcu_in_package")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.original, args.reference, args.output), indent=2))
