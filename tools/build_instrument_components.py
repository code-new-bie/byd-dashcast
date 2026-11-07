"""Build every changed instrument App file of the current 1.25.0 package in one run; NOT a flash ZIP.

Runs step 1 (1.17 base), step 2 (1.19.2 B2), step 3 (1.19.3 small mask) and step 4 (1.22.0 RWVG
fade-in), then the 1.25.0 gauge ownership/readback patch in memory, adds application.cfg and the
four QNX scripts. Every output must match reviewed bytes or nothing is written.
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
STATUS = "INSTRUMENT_1250_APP_FILES_NOT_AN_UPGRADE"
SCRIPTS = ROOT / "instrument/ref2-noauth"
# Install path inside app.sv -> SHA256 of the reviewed 1.25.0 file.
EXPECTED = {
    "base/app/rc_hmi/rc_hmi.exe": "a094be8555b6d8b7e4c24fc0b51daccd015b1cd6fa3dde6e38517dbd3f7b3102",
    "base/app/rc_hmi/bic_byd_stha.kzb": "70ae4e79c722e98cc393552fe32fd3b1b71babdeee25bc592881b83e6f1e9b68",
    "base/app/rc_hmi/3dscreen.kzb": "31ef7e87ed16632498384818297660c44a113215ec3bec9c9efd2d3b86170f46",
    "base/app/rc_hmi/application.cfg": "37c03ecb7b068f08857fc43a470a144bb3faa70ceddb4fe184cf767ed58f442f",
    "base/app/dip-instrument/instrument-receiver": "0daf4091850796d6f5b8f88dec6188a56eba4bb7f76696d4f7b581ba301a8bd7",
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
    from fade_in_viewport import build as build_fade_in
    from theme_geometry import build_pair as build_theme
    hmi, kzb, b2_report = build_b2(base["rc_hmi.exe"], base["bic_byd_stha.kzb"], args.b2_masks)
    hmi, kzb, center_report = build_center(hmi, kzb, args.center_mask)
    hmi, kzb, receiver, fade_report = build_fade_in(hmi, kzb, base["receiver"])
    hmi, kzb, receiver, theme_report = build_theme(hmi, kzb, receiver)
    files = {
        "base/app/rc_hmi/rc_hmi.exe": hmi,
        "base/app/rc_hmi/bic_byd_stha.kzb": kzb,
        "base/app/rc_hmi/3dscreen.kzb": base["3dscreen.kzb"],
        "base/app/rc_hmi/application.cfg": configure_alpha(cp.read_bytes()),
        "base/app/dip-instrument/instrument-receiver": receiver,
    }
    for name in ("startup.ksh", "run-cast.ksh", "cast-mode.ksh", "usb-mode.ksh"):
        files["base/app/dip-instrument/" + name] = (SCRIPTS / name).read_bytes()
    digests = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    wrong = sorted(name for name in EXPECTED if digests.get(name) != EXPECTED[name])
    if wrong or set(files) != set(EXPECTED):
        raise ValueError("Outputs differ from the reviewed 1.25.0 files; nothing written: " + ", ".join(wrong))
    out.mkdir()
    for name, raw in files.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(raw)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status=STATUS, sha256=digests, not_built=["base/app/dip-instrument/instrument-usb"],
                       steps=dict(base_1170=base_report, b2_1192=b2_report, center_1193=center_report,
                                  fade_in_1220=fade_report, theme_geometry_1250=theme_report),
                       vehicle=False), stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(STATUS)


if __name__ == "__main__":
    main()
