"""Base-firmware reading and FULL packaging on a synthetic base; no firmware, vehicle or native tools needed."""
import gzip
import hashlib
import io
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "analysis"))
import base_firmware  # noqa: E402
import package_full as pack  # noqa: E402
from sv_container import pack_sv, unpack_sv  # noqa: E402

TAIL = b"Desay SV Automotive".ljust(32, b"\0") + bytes(480)


def member(name, kind=tarfile.REGTYPE, mode=0o664, uid=1000):
    info = tarfile.TarInfo(name)
    info.type, info.mode, info.uid, info.gid, info.uname, info.gname, info.mtime = kind, mode, uid, uid, "", "", 1700000000
    return info


def make_sv(files):
    entries = {}
    for name, data in files:
        kind = tarfile.DIRTYPE if data is None else tarfile.REGTYPE
        entries[name.removeprefix("./")] = (member(name, kind, 0o775 if data is None else 0o664), data)
    return pack_sv(entries, TAIL)


def synthetic_base():
    app = make_sv([("./base", None), ("./base/app", None), ("./base/app/rc_hmi", None),
                   ("./base/app/rc_hmi/rc_hmi.exe", b"hmi"), ("./base/app/rc_hmi/bic_byd_stha.kzb", b"kzb"),
                   ("./base/app/rc_hmi/bic_byd_stha.kzb.txt", b"toc"), ("./base/app/rc_hmi/3dscreen.kzb", b"scene"),
                   ("./base/app/rc_hmi/application.cfg", b"cfg"), ("./base/app/rc_hmi/Resource_Font", None),
                   ("./base/app/rc_hmi/Resource_Font/msyhbd.ttf", b"font")])
    os_sv = make_sv([("base", None), ("base/etc", None), ("base/etc/slm-config-modules.xml", b"<old/>\n"),
                     ("base/etc/after", b"x" * 700), ("base/z", b"tail")])
    return {"app.sv": app, "os.sv": os_sv, "mcu.sv": b"mcu", "RcAppVersion": b"1", "kzbVersion": b"2",
            "version_svp": b"3"}


def components():
    return {"app": {"base/app/rc_hmi/rc_hmi.exe": b"new-hmi" * 200, "base/app/rc_hmi/bic_byd_stha.kzb": b"new-kzb",
                    "base/app/rc_hmi/3dscreen.kzb": b"new-scene", "base/app/rc_hmi/application.cfg": b"cfg+alpha",
                    "base/app/dashcast/dashcast-receiver": b"rx", "base/app/dashcast/dashcast-usb": b"usb"},
            "os": {"base/etc/slm-config-modules.xml": b"<new>" + b"e" * 900 + b"</new>\n"}}


def pins(files):
    return {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}


class BaseFirmwareTests(unittest.TestCase):
    def write_zip(self, folder, files, prefix="release/", extra=None):
        path = Path(folder) / "base.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in files.items():
                archive.writestr(prefix + name, data)
            if extra:
                archive.writestr(extra, b"extra")
        return path

    def test_reads_release_with_or_without_folder(self):
        base = synthetic_base()
        with tempfile.TemporaryDirectory() as folder:
            for prefix in ("release/", ""):
                path = self.write_zip(folder, base, prefix)
                self.assertEqual(base, base_firmware.read_release(path, pins(base)))
                path.unlink()

    def test_refuses_wrong_missing_or_extra_files(self):
        base = synthetic_base()
        with tempfile.TemporaryDirectory() as folder:
            changed = dict(base, **{"mcu.sv": b"other"})
            path = self.write_zip(folder, changed)
            with self.assertRaisesRegex(ValueError, "Not the pinned base firmware.*mcu.sv"):
                base_firmware.read_release(path, pins(base))
            path.unlink()
            path = self.write_zip(folder, base, extra="release/notes.txt")
            with self.assertRaisesRegex(ValueError, "Unexpected file"):
                base_firmware.read_release(path, pins(base))

    def test_inputs_are_checked_against_their_pins(self):
        base = synthetic_base()
        table = {"hmi": ("app.sv", "base/app/rc_hmi/rc_hmi.exe", hashlib.sha256(b"hmi").hexdigest(), "rc_hmi.exe"),
                 "slm": ("os.sv", "base/etc/slm-config-modules.xml", hashlib.sha256(b"<old/>\n").hexdigest(), "s.xml")}
        self.assertEqual({"hmi": b"hmi", "slm": b"<old/>\n"}, base_firmware.read_inputs(base, table))
        table["hmi"] = ("app.sv", "base/app/rc_hmi/rc_hmi.exe", "0" * 64, "rc_hmi.exe")
        with self.assertRaisesRegex(ValueError, "differs from its pin"):
            base_firmware.read_inputs(base, table)


class PackageFullTests(unittest.TestCase):
    def test_package_keeps_the_base_and_adds_only_the_module(self):
        base, parts = synthetic_base(), components()
        full, report = pack.package(base, parts)
        self.assertEqual("PASS_FULL_PACKAGED_FROM_BASE", report["status"])
        with zipfile.ZipFile(io.BytesIO(full)) as archive:
            infos = archive.infolist()
            files = {i.filename.removeprefix("release/"): archive.read(i) for i in infos}
        self.assertEqual(["release/" + n for n in pack.ZIP_ORDER], [i.filename for i in infos])
        self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED and i.date_time == pack.ZIP_TIME for i in infos))
        for name in ("mcu.sv", "RcAppVersion", "kzbVersion", "version_svp"):
            self.assertEqual(base[name], files[name])
        old, _ = unpack_sv(base["app.sv"])
        new, _ = unpack_sv(files["app.sv"])
        self.assertEqual(list(old) + ["base/app/dashcast", *pack.APP_ADDED], list(new))
        self.assertEqual(b"new-hmi" * 200, new["base/app/rc_hmi/rc_hmi.exe"][1])
        self.assertEqual(b"font", new["base/app/rc_hmi/Resource_Font/msyhbd.ttf"][1])
        self.assertEqual("./base/app/rc_hmi/rc_hmi.exe", new["base/app/rc_hmi/rc_hmi.exe"][0].name)
        module = new["base/app/dashcast"][0]
        self.assertEqual((tarfile.DIRTYPE, 0o700, 0, 0, pack.MODULE_TIME),
                         (module.type, module.mode, module.uid, module.gid, module.mtime))
        old_tar = gzip.decompress(base["os.sv"][:-512])
        new_tar = gzip.decompress(files["os.sv"][:-512])
        self.assertEqual(len(old_tar), len(new_tar))
        os_new, _ = unpack_sv(files["os.sv"])
        self.assertEqual(parts["os"]["base/etc/slm-config-modules.xml"], os_new["base/etc/slm-config-modules.xml"][1])
        self.assertEqual(b"x" * 700, os_new["base/etc/after"][1])
        again, _ = pack.package(base, parts)
        self.assertEqual(full, again)

    def test_os_member_replacement_leaves_other_bytes_alone(self):
        base = synthetic_base()
        tar = gzip.decompress(base["os.sv"][:-512])
        with tarfile.open(fileobj=io.BytesIO(tar)) as archive:
            first = archive.getmember("base/etc/slm-config-modules.xml").offset
        result = pack.replace_tar_member(tar, "base/etc/slm-config-modules.xml", b"y" * 1300)
        self.assertEqual(tar[:first], result[:first])
        self.assertEqual(len(tar), len(result))
        with tarfile.open(fileobj=io.BytesIO(result)) as archive:
            self.assertEqual(b"x" * 700, archive.extractfile("base/etc/after").read())
        with self.assertRaisesRegex(ValueError, "record padding"):
            pack.replace_tar_member(tar, "base/etc/slm-config-modules.xml", b"y" * len(tar))

    def test_components_must_be_exactly_the_allowed_files(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            for side, files in components().items():
                for name, data in files.items():
                    path = folder / side / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
            self.assertEqual(components(), pack.read_components(folder))
            (folder / "app/base/app/dashcast/extra").write_bytes(b"x")
            with self.assertRaisesRegex(ValueError, "Components must be exactly"):
                pack.read_components(folder)

    def test_base_with_a_module_already_is_refused(self):
        base = synthetic_base()
        entries, tail = unpack_sv(base["app.sv"])
        entries["base/app/dashcast"] = (member("base/app/dashcast", tarfile.DIRTYPE, 0o700, 0), None)
        base["app.sv"] = pack_sv(entries, tail)
        with self.assertRaisesRegex(ValueError, "already has a dashcast module"):
            pack.package(base, components())

    def test_cli_refuses_an_existing_output(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "out.zip"
            output.write_bytes(b"keep")
            result = subprocess.run([sys.executable, "-B", ROOT / "tools/package_full.py", "--base", output,
                                     "--components", folder, "--output", output], capture_output=True, text=True,
                                    timeout=30)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("never overwrite", result.stderr)
            self.assertEqual(b"keep", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
