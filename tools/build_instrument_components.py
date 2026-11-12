"""Build every changed instrument file of the current package from the base firmware in one run; NOT a flash ZIP.

    python tools/build_instrument_components.py --base BASE.zip \\
        --native-usb NATIVE/dashcast-usb --native-receiver NATIVE/dashcast-receiver --output OUT

The original HMI, KZB with its TOC, 3D scene, application.cfg and the OS start-up module list are read straight
from your base firmware ZIP and checked against their pins (tools/base_firmware.py). In memory the HMI/KZB go
through the layout and background step, the B2 blend mode and masks, the small-window mask, the RWVG fade-in,
the RWVI gauge geometry and the warning positions; the layout step's HMI also carries the runtime draw filter
that hides the ACCSys overlay during projection (both layouts); the start-up entry is added to the module list. The two
programs come from instrument/native/build.sh. OUT/app holds the app.sv files, OUT/os the one os.sv file;
tools/package_full.py turns them and the same base ZIP into the flash package.
Every output must match the reviewed bytes or nothing is written.
"""
import argparse
import hashlib
import json
from pathlib import Path

from base_firmware import read_inputs, read_release
from build_projection_fit_components import build_components, checked_backgrounds
from component_inputs import checked_file, new_output

ROOT = Path(__file__).resolve().parents[1]
STATUS = "INSTRUMENT_1410_FILES_NOT_AN_UPGRADE"
# Output path -> SHA256 of the reviewed 1.41.0 file.
EXPECTED = {
    "app/base/app/rc_hmi/rc_hmi.exe": "6895a7253afa3df2445bcfec3a0f50adfcf25e1f9637933c0440a6adb12c0351",
    "app/base/app/rc_hmi/bic_byd_stha.kzb": "f80d81981b5075361f8c8f46a585f73b96925c7c128f6ba8c2fdf837643763c2",
    "app/base/app/rc_hmi/3dscreen.kzb": "31ef7e87ed16632498384818297660c44a113215ec3bec9c9efd2d3b86170f46",
    "app/base/app/rc_hmi/application.cfg": "37c03ecb7b068f08857fc43a470a144bb3faa70ceddb4fe184cf767ed58f442f",
    "app/base/app/dashcast/dashcast-receiver": "fb4e9b42e74cefec72f433c8ce1c55deb7ab201dea686728af5fe402cb289e60",
    "app/base/app/dashcast/dashcast-usb": "3e4327a7ab07c926fc74067547ed51870d590e9de1ce3d96fd0b79f7bc15ee42",
    "os/base/etc/slm-config-modules.xml": "e34425c570cc5d2cd3a7b1a0fa2fc11707e5290fd79d1f0b405b4511352c04a8",
}


def configure_alpha(original):
    lines = original.replace(b"\r", b"").splitlines()
    if lines != [b"SurfaceBitsRed = 8", b"SurfaceBitsGreen = 8", b"SurfaceBitsBlue = 8"]:
        raise ValueError("Unexpected HMI application.cfg; refuse to replace user settings")
    boundary = b"" if original.endswith(b"\n") else b"\r\n"
    return original + boundary + b"SurfaceBitsAlpha = 8\r\n"


def build(original, native_usb, native_receiver, backgrounds, b2_masks, center_mask):
    """All changed files (output path -> bytes) and the per-step report, from the base originals."""
    base, base_report = build_components(original["hmi"], original["kzb"], original["scene"], original["toc"],
                                         backgrounds)
    from b2_matte import build_pair as build_b2
    from center_premul_mask import build_pair as build_center
    from fade_in_viewport import build as build_fade_in
    from theme_geometry import build_pair as build_theme
    from warning_perimeter import build_pair as build_warnings
    from startup_entry import build_slm
    hmi, kzb, b2_report = build_b2(base["rc_hmi.exe"], base["bic_byd_stha.kzb"], b2_masks)
    hmi, kzb, center_report = build_center(hmi, kzb, center_mask)
    hmi, kzb, fade_report = build_fade_in(hmi, kzb)
    hmi, kzb, theme_report = build_theme(hmi, kzb)
    hmi, kzb, warning_report = build_warnings(hmi, kzb)
    slm, slm_report = build_slm(original["slm"])
    files = {
        "app/base/app/rc_hmi/rc_hmi.exe": hmi,
        "app/base/app/rc_hmi/bic_byd_stha.kzb": kzb,
        "app/base/app/rc_hmi/3dscreen.kzb": base["3dscreen.kzb"],
        "app/base/app/rc_hmi/application.cfg": configure_alpha(original["config"]),
        "app/base/app/dashcast/dashcast-receiver": native_receiver,
        "app/base/app/dashcast/dashcast-usb": native_usb,
        "os/base/etc/slm-config-modules.xml": slm,
    }
    steps = dict(base_1170=base_report, b2_1192=b2_report, center_1193=center_report, fade_in_1220=fade_report,
                 theme_geometry_1251=theme_report, warning_perimeter_1261=warning_report,
                 startup_entry=dict(slm=slm_report))
    return files, steps


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", type=Path, required=True, help="your base firmware ZIP")
    for name in ("native-usb", "native-receiver", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--backgrounds", type=Path, default=ROOT / "assets/backgrounds")
    parser.add_argument("--b2-masks", type=Path, default=ROOT / "instrument/assets/b2")
    parser.add_argument("--center-mask", type=Path, default=ROOT / "instrument/assets/b3-center/DXCenterDark.png")
    args = parser.parse_args()
    out = new_output(args.output)
    base_zip = checked_file(args.base, out, 512 * 1024 * 1024)
    native_usb = checked_file(args.native_usb, out).read_bytes()
    native_receiver = checked_file(args.native_receiver, out).read_bytes()
    backgrounds = checked_backgrounds(args.backgrounds, out)
    original = read_inputs(read_release(base_zip))
    files, steps = build(original, native_usb, native_receiver, backgrounds, args.b2_masks, args.center_mask)
    digests = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    wrong = sorted(name for name in EXPECTED if digests.get(name) != EXPECTED[name])
    if wrong or set(files) != set(EXPECTED):
        raise ValueError("Outputs differ from the reviewed 1.40.0 files; nothing written: " + ", ".join(wrong))
    out.mkdir()
    for name, raw in files.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(raw)
    with (out / "REPORT.json").open("x", encoding="utf-8") as stream:
        json.dump(dict(status=STATUS, sha256=digests, steps=steps, vehicle=False), stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(STATUS)


if __name__ == "__main__":
    main()
