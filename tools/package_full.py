"""Make the FULL flash ZIP from the base firmware and the components of build_instrument_components.py.

    python tools/package_full.py --base BASE.zip --components OUT --output NEW-FULL.zip [--report REPORT.json]

release/app.sv   the base app.sv: rc_hmi.exe, bic_byd_stha.kzb, 3dscreen.kzb and application.cfg take their new
                 content; the module directory base/app/dashcast/ (root, 0700) with dashcast-receiver and
                 dashcast-usb is added after the last base member. Every other member keeps its content,
                 owner, mode, time and place.
release/os.sv    the base os.sv with only base/etc/slm-config-modules.xml replaced; every other byte of its
                 tar stays exactly as in the base (the record padding absorbs the longer file).
release/mcu.sv, RcAppVersion, kzbVersion, version_svp   the base files, unchanged.

The ZIP is stored (not deflated) with fixed names, order and times, so the same inputs give the same bytes.
The output must not exist; nothing is written until the result has been re-read and checked.
"""
import argparse
import gzip
import hashlib
import io
import json
import struct
import sys
import tarfile
import zipfile
from pathlib import Path

from base_firmware import RELEASE, read_release
from component_inputs import checked_file

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "analysis"))
from sv_container import pack_sv, unpack_sv  # noqa: E402

MODULE = "base/app/dashcast"
APP_REPLACED = ("base/app/rc_hmi/rc_hmi.exe", "base/app/rc_hmi/bic_byd_stha.kzb", "base/app/rc_hmi/3dscreen.kzb",
                "base/app/rc_hmi/application.cfg")
APP_ADDED = (MODULE + "/dashcast-receiver", MODULE + "/dashcast-usb")
OS_REPLACED = "base/etc/slm-config-modules.xml"
MODULE_TIME = 1791321600  # 2026-10-07 00:00:00 UTC, fixed for the added members
ZIP_ORDER = ("os.sv", "app.sv", "mcu.sv", "RcAppVersion", "kzbVersion", "version_svp")
ZIP_TIME = (2026, 10, 24, 9, 24, 6)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_components(folder):
    """{'app': {member: bytes}, 'os': {member: bytes}}; exactly the files this package format allows."""
    folder = Path(folder)
    found = {p.relative_to(folder).as_posix(): p for p in sorted(folder.rglob("*"))
             if p.is_file() and p.name != "REPORT.json"}
    if any(p.is_symlink() for p in folder.rglob("*")):
        raise ValueError("Symlinks are not allowed in the components directory")
    wanted = {"app/" + n for n in APP_REPLACED + APP_ADDED} | {"os/" + OS_REPLACED}
    if set(found) != wanted:
        raise ValueError(f"Components must be exactly {sorted(wanted)}; found {sorted(found)}")
    result = {"app": {}, "os": {}}
    for name, path in found.items():
        side, member = name.split("/", 1)
        result[side][member] = path.read_bytes()
    return result


def module_member(name, kind):
    member = tarfile.TarInfo(name)
    member.type, member.mode, member.uid, member.gid = kind, 0o700, 0, 0
    member.uname = member.gname = ""
    member.mtime = MODULE_TIME
    return member


def build_app(raw, components):
    entries, tail = unpack_sv(raw)
    if any(name == MODULE or name.startswith(MODULE + "/") for name in entries):
        raise ValueError("The base app.sv already has a dashcast module")
    for name in APP_REPLACED:
        member, old = entries[name]
        if not member.isreg() or old is None:
            raise ValueError("Not a regular base member: " + name)
        entries[name] = (member, components[name])
    entries[MODULE] = (module_member(MODULE, tarfile.DIRTYPE), None)
    for name in APP_ADDED:
        entries[name] = (module_member(name, tarfile.REGTYPE), components[name])
    return pack_sv(entries, tail)


def replace_tar_member(tar, name, content):
    """Replace one plain regular member; only its size/checksum fields and data blocks change.

    Members after it move by whole blocks; the archive keeps its length by giving up zero record padding,
    always leaving the two end-of-archive blocks.
    """
    with tarfile.open(fileobj=io.BytesIO(tar), mode="r:") as archive:
        members = archive.getmembers()
    matches = [m for m in members if m.name.removeprefix("./") == name]
    if len(matches) != 1 or not matches[0].isreg() or matches[0].pax_headers:
        raise ValueError("Expected one plain regular member: " + name)
    member = matches[0]
    header = bytearray(tar[member.offset:member.offset + 512])
    if header[257:262] != b"ustar":
        raise ValueError("Expected a ustar header")
    header[124:136] = b"%011o" % len(content) + bytes(header[135:136])
    header[148:156] = b" " * 8
    header[148:156] = b"%06o\0 " % sum(header)
    old_end = member.offset_data + (member.size + 511) // 512 * 512
    result = tar[:member.offset] + bytes(header) + content + bytes(-len(content) % 512) + tar[old_end:]
    grow = len(result) - len(tar)
    if grow > 0:
        if result[-grow:] != bytes(grow):
            raise ValueError("Not enough record padding to keep the archive length")
        result = result[:-grow]
    else:
        result += bytes(-grow)
    with tarfile.open(fileobj=io.BytesIO(result), mode="r:") as archive:
        last = archive.getmembers()[-1]
    end = last.offset_data + (last.size + 511) // 512 * 512
    if len(result) - end < 1024 or result[end:] != bytes(len(result) - end):
        raise ValueError("End-of-archive blocks would be lost")
    return result


def build_os(raw, content):
    entries, tail = unpack_sv(raw)
    if OS_REPLACED not in entries:
        raise ValueError("Not a base os.sv member: " + OS_REPLACED)
    tar = replace_tar_member(gzip.decompress(raw[:-512]), OS_REPLACED, content)
    compressed = gzip.compress(tar, compresslevel=9, mtime=0)
    footer = bytearray(tail)
    footer[0x120:0x140] = hashlib.md5(compressed).hexdigest().encode("ascii")
    struct.pack_into("<Q", footer, 0x140, len(tar))
    return compressed + bytes(footer)


def make_zip(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name in ZIP_ORDER:
            info = zipfile.ZipInfo("release/" + name, ZIP_TIME)
            info.compress_type, info.create_system, info.create_version, info.extract_version = \
                zipfile.ZIP_STORED, 0, 20, 20
            info.external_attr = 0o600 << 16
            archive.writestr(info, files[name])
    return output.getvalue()


def verify(full, base, components):
    """Re-read the finished ZIP and compare every member with the base and the components."""
    with zipfile.ZipFile(io.BytesIO(full)) as archive:
        names = [info.filename for info in archive.infolist()]
        files = {name.removeprefix("release/"): archive.read(name) for name in names}
    if names != ["release/" + n for n in ZIP_ORDER]:
        raise ValueError("ZIP member list changed")
    for name in ZIP_ORDER:
        if name not in ("app.sv", "os.sv") and files[name] != base[name]:
            raise ValueError("Base file changed: " + name)
    base_app, base_app_tail = unpack_sv(base["app.sv"])
    app, app_tail = unpack_sv(files["app.sv"])
    if list(app) != list(base_app) + [MODULE, *APP_ADDED] or app_tail[:0x120] != base_app_tail[:0x120]:
        raise ValueError("app.sv member order or trailer changed")
    fields = ("name", "mode", "uid", "gid", "uname", "gname", "type", "mtime", "linkname", "devmajor", "devminor")
    for name, (member, data) in base_app.items():
        new_member, new_data = app[name]
        if any(getattr(member, f) != getattr(new_member, f) for f in fields):
            raise ValueError("app.sv member metadata changed: " + name)
        if new_data != (components["app"][name] if name in APP_REPLACED else data):
            raise ValueError("app.sv member content wrong: " + name)
    for name in (MODULE, *APP_ADDED):
        member, data = app[name]
        if (member.mode, member.uid, member.gid, member.mtime) != (0o700, 0, 0, MODULE_TIME) \
                or data != components["app"].get(name):
            raise ValueError("Added module member wrong: " + name)
    base_tar = gzip.decompress(base["os.sv"][:-512])
    new_tar = gzip.decompress(files["os.sv"][:-512])
    base_os, base_os_tail = unpack_sv(base["os.sv"])
    new_os, new_os_tail = unpack_sv(files["os.sv"])
    if list(new_os) != list(base_os) or new_os_tail[:0x120] != base_os_tail[:0x120] or len(new_tar) != len(base_tar):
        raise ValueError("os.sv member order, trailer or length changed")
    with tarfile.open(fileobj=io.BytesIO(base_tar)) as a, tarfile.open(fileobj=io.BytesIO(new_tar)) as b:
        for old, new in zip(a.getmembers(), b.getmembers()):
            if old.name.removeprefix("./") == OS_REPLACED:
                if new.size != len(components["os"][OS_REPLACED]):
                    raise ValueError("os.sv replaced member size wrong")
                continue
            span = 512 + (old.size + 511) // 512 * 512
            if base_tar[old.offset:old.offset + span] != new_tar[new.offset:new.offset + span]:
                raise ValueError("os.sv member bytes changed: " + old.name)
    if new_os[OS_REPLACED][1] != components["os"][OS_REPLACED]:
        raise ValueError("os.sv replaced member content wrong")
    return files


def package(base, components):
    """The FULL ZIP bytes and a report, from the base release files and the component files."""
    files = dict(base)
    files["app.sv"] = build_app(base["app.sv"], components["app"])
    files["os.sv"] = build_os(base["os.sv"], components["os"][OS_REPLACED])
    full = make_zip(files)
    verify(full, base, components)
    report = dict(status="PASS_FULL_PACKAGED_FROM_BASE", output_sha256=sha256(full), output_bytes=len(full),
                  base_release_sha256={n: sha256(base[n]) for n in ZIP_ORDER},
                  release_sha256={n: sha256(files[n]) for n in ZIP_ORDER},
                  app_replaced={n: sha256(components["app"][n]) for n in APP_REPLACED},
                  app_added={n: sha256(components["app"][n]) for n in APP_ADDED},
                  os_replaced={OS_REPLACED: sha256(components["os"][OS_REPLACED])},
                  unchanged=[n for n in ZIP_ORDER if n not in ("app.sv", "os.sv")])
    return full, report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", type=Path, required=True, help="your base firmware ZIP")
    parser.add_argument("--components", type=Path, required=True, help="OUT of build_instrument_components.py")
    parser.add_argument("--output", type=Path, required=True, help="new FULL ZIP")
    parser.add_argument("--report", type=Path, help="also write the report here (new file)")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or args.output.is_symlink() or not output.parent.is_dir():
        raise SystemExit("Output exists or has no parent directory; never overwrite a delivery")
    if args.report and args.report.exists():
        raise SystemExit("Report exists")
    base = read_release(checked_file(args.base, output, 512 * 1024 * 1024), RELEASE)
    full, report = package(base, read_components(args.components))
    with output.open("xb") as stream:
        stream.write(full)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        with args.report.open("x", encoding="utf-8") as stream:
            stream.write(text)
    print(text, end="")


if __name__ == "__main__":
    main()
