"""Build every changed instrument App file of the current 1.19.3 package in one run; NOT a flash ZIP.

Runs step 1 (1.17 base), step 2 (1.19.2 B2) and step 3 (1.19.3 small mask) in memory,
adds application.cfg and the four QNX scripts, and lays the files out under their
app.sv install paths. Every output must match the reviewed 1.19.3 bytes or nothing is written.
instrument-usb is not built here; see docs/BUILD.md.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from build_projection_fit_components import build_components, checked_backgrounds
from component_inputs import checked_file, checked_paths

ROOT = Path(__file__).resolve().parents[1]
STATUS = "INSTRUMENT_1193_APP_FILES_NOT_AN_UPGRADE"
SCRIPTS = ROOT / "instrument/ref2-noauth"
# Install path inside app.sv -> SHA256 of the reviewed 1.19.3 file.
EXPECTED = {
    "base/app/rc_hmi/rc_hmi.exe": "94afdc93bb641dd2d9e9b7cd62e0ffaf4094ac7be376f605fbb05c9eaa910bc8",
    "base/app/rc_hmi/bic_byd_stha.kzb": "24909ff06c5bbf3e6574e40bce39c35a6ddc1967b3c753f3237577d7881b81bf",
    "base/app/rc_hmi/3dscreen.kzb": "31ef7e87ed16632498384818297660c44a113215ec3bec9c9efd2d3b86170f46",
    "base/app/rc_hmi/application.cfg": "37c03ecb7b068f08857fc43a470a144bb3faa70ceddb4fe184cf767ed58f442f",
    "base/app/dip-instrument/instrument-receiver": "3beb6b714725a8640ef817eb94a5ba2cf0a6d4f3ce9403e6fc7877cbb59dbb8a",
    "base/app/dip-instrument/startup.ksh": "10af0bacfa4b797f57b4b88f986114a79ddceb029f87da0a322e80ae92f2fd9b",
    "base/app/dip-instrument/run-cast.ksh": "268ee0e537d6f78dc9715d44d5c3c8e5504aad794480a9708cfb77c9515f6b83",
    "base/app/dip-instrument/cast-mode.ksh": "f8cb30a9c6d9ca610ccb19c00d0360ea9e0794d2f9ad95f03a531229514d0c82",
    "base/app/dip-instrument/usb-mode.ksh": "9baebd17211f574ca4c6a287aada3396fe8ed4e8b8bc6cbb98cb1e32db2416e6",
}


def configure_alpha(original):
    lines = original.replace(b"\r", b"").splitlines()
    if lines != [b"SurfaceBitsRed = 8", b"SurfaceBitsGreen = 8", b"SurfaceBitsBlue = 8"]:
        raise ValueError("Unexpected HMI application.cfg; refuse to replace user settings")
    boundary = b"" if original.endswith(b"\n") else b"\r\n"
    return original + boundary + b"SurfaceBitsAlpha = 8\r\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("hmi", "kzb", "scene", "toc", "receiver", "config", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--backgrounds", type=Path, default=ROOT / "assets/backgrounds")
    parser.add_argument("--b2-masks", type=Path, default=ROOT / "instrument/assets/b2")
    parser.add_argument("--center-mask", type=Path, default=ROOT / "instrument/assets/b3-center/DXCenterDark.png")
    args = parser.parse_args()
    hp, kp, tp, rp, out = checked_paths(args.hmi, args.kzb, args.toc, args.receiver, args.output)
    sp = checked_file(args.scene, out)
    cp = checked_file(args.config, out, 64 * 1024)
    backgrounds = checked_backgrounds(args.backgrounds, out)
    base, base_report = build_components(hp.read_bytes(), kp.read_bytes(), sp.read_bytes(), tp.read_bytes(),
                                         rp.read_bytes(), backgrounds)
    from b2_matte import build_pair as build_b2
    from center_premul_mask import build_pair as build_center
    hmi, kzb, b2_report = build_b2(base["rc_hmi.exe"], base["bic_byd_stha.kzb"], args.b2_masks)
    hmi, kzb, center_report = build_center(hmi, kzb, args.center_mask)
    files = {
        "base/app/rc_hmi/rc_hmi.exe": hmi,
        "base/app/rc_hmi/bic_byd_stha.kzb": kzb,
        "base/app/rc_hmi/3dscreen.kzb": base["3dscreen.kzb"],
        "base/app/rc_hmi/application.cfg": configure_alpha(cp.read_bytes()),
        "base/app/dip-instrument/instrument-receiver": base["receiver"],
    }
    for name in ("startup.ksh", "run-cast.ksh", "cast-mode.ksh", "usb-mode.ksh"):
        files["base/app/dip-instrument/" + name] = (SCRIPTS / name).read_bytes()
    digests = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    wrong = sorted(name for name in EXPECTED if digests.get(name) != EXPECTED[name])
    if wrong or set(files) != set(EXPECTED):
        raise ValueError("Outputs differ from the reviewed 1.19.3 files; nothing written: " + ", ".join(wrong))
    out.mkdir()
    for name, raw in files.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(raw)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status=STATUS, sha256=digests, not_built=["base/app/dip-instrument/instrument-usb"],
                       steps=dict(base_1170=base_report, b2_1192=b2_report, center_1193=center_report),
                       vehicle=False), stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(STATUS)


if __name__ == "__main__":
    main()
