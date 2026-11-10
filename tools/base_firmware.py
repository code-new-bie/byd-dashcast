"""The firmware this project is built on, and the original files read from it.

The base is the 2021 Song Plus DM-i instrument flash package whose app.sv carries the full-cluster artwork and fonts,
paired with the factory MCU (the user's own ZIP, e.g. release_完美复刻四驱仪表_使用原厂mcu.zip). Its six release files
are pinned by SHA256 below; the ZIP around them may be re-made, with or without a release/ folder. Nothing from it
is stored in this repository.

    python tools/base_firmware.py --base BASE.zip --output NEW_DIR

writes the original files the build reads (HMI, KZB and TOC, 3D scene, application.cfg, the bold font that
notice_glyphs.py renders, and the OS start-up module list) into NEW_DIR, for the step-by-step tools and checks.
"""
import argparse
import hashlib
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "analysis"))
from sv_container import unpack_sv  # noqa: E402

# The six files of the base release/ folder (ZIP sha256 923b82788acb…c225b6094b when made by the maintainer).
RELEASE = {
    "app.sv": "d4969ab876f27d3a40cd04658a447d46da636f98f153889a5b520c34207062f5",
    "os.sv": "845cba196c82b1565872cc3728fb75002777b5b09d9eedb89dfa3e87c5cfd832",
    "mcu.sv": "1ac756c66b26e16c2d1f4b2a93a6bd4ea0722fdfe7df3a0f51ab572c7179f337",
    "RcAppVersion": "5b604cdb6ce90ff0004bd81463e5028a2bfd2e0557dc2c015df4a3868a38de8a",
    "kzbVersion": "a103c8fa49730744fc004b031688e6ae346c08d7baff5d370073612b58e77646",
    "version_svp": "fc6eaabe209eab86893d43cfee167d7ca3a48211cca52805f9263ba1eeda3236",
}
# Build input -> (release file, member, SHA256, file name written by --output).
INPUTS = {
    "hmi": ("app.sv", "base/app/rc_hmi/rc_hmi.exe",
            "5fe489555ac7e3cc3cc4f73cfd47fa3cca580d881e570d22e4ac93a6fad07b10", "rc_hmi.exe"),
    "kzb": ("app.sv", "base/app/rc_hmi/bic_byd_stha.kzb",
            "4a1938ec7eb6714b8bc116c57ad44447cfb1ec7371931827b886e288c319dc3d", "bic_byd_stha.kzb"),
    "toc": ("app.sv", "base/app/rc_hmi/bic_byd_stha.kzb.txt",
            "402c609076bc539bd552652f00fd63440f59d28e8cc908ca494f249060ad106e", "bic_byd_stha.kzb.txt"),
    "scene": ("app.sv", "base/app/rc_hmi/3dscreen.kzb",
              "18d817c89f07e7171a26e67ec4a1f567d76e8ae7b61c6c3383ee92882be8323c", "3dscreen.kzb"),
    "config": ("app.sv", "base/app/rc_hmi/application.cfg",
               "f672ed14e92ee2345aaefc5c4192f5d37bb10baf368f9edcca08bc5c7c662538", "application.cfg"),
    "font": ("app.sv", "base/app/rc_hmi/Resource_Font/msyhbd.ttf",
             "902de9fa292978d04e5052b5005bcc445ebb0907eb83245386e559f3aba732cd", "msyhbd.ttf"),
    "slm": ("os.sv", "base/etc/slm-config-modules.xml",
            "edfca97b4d149ab7b9edfde32cf65ac324cece1cea5cd5c3b3ad8925266ae4f2", "slm-config-modules.xml"),
}
LIMIT = 512 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_release(path, pins=RELEASE):
    """The six release files (name -> bytes); each must match its pin, nothing else may be in the ZIP."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= LIMIT:
        raise ValueError("Base firmware ZIP missing, a symlink or unbounded")
    files = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = info.filename.removeprefix("release/")
            if name not in pins or name in files or info.file_size > LIMIT:
                raise ValueError("Unexpected file in the base firmware ZIP: " + info.filename)
            files[name] = archive.read(info)
    wrong = sorted(name for name in pins if sha256(files.get(name, b"")) != pins[name])
    if wrong:
        raise ValueError("Not the pinned base firmware (wrong or missing: " + ", ".join(wrong) + ")")
    return files


def read_inputs(release, inputs=INPUTS):
    """The original build inputs (key -> bytes) out of the base app.sv / os.sv."""
    unpacked = {name: unpack_sv(release[name])[0] for name in {sv for sv, *_ in inputs.values()}}
    result = {}
    for key, (sv, member, pin, _) in inputs.items():
        entry = unpacked[sv].get(member)
        if entry is None or entry[1] is None or sha256(entry[1]) != pin:
            raise ValueError(f"Base firmware member differs from its pin: {sv}:{member}")
        result[key] = entry[1]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", type=Path, required=True, help="your base firmware ZIP")
    parser.add_argument("--output", type=Path, required=True, help="NEW directory for the original files")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or not output.parent.is_dir():
        raise SystemExit("Existing parent and NEW output directory required")
    inputs = read_inputs(read_release(args.base))
    output.mkdir()
    for key, (_, member, _, name) in INPUTS.items():
        with (output / name).open("xb") as stream:
            stream.write(inputs[key])
        print(f"{name}  <- {member}")


if __name__ == "__main__":
    main()
