"""SV codec extracted unchanged from the phase-one container implementation.
No firmware contents, OS changes or historical credential handling included.
"""
import copy
import gzip
import hashlib
import io
import struct
import tarfile
import zlib


def normalized(name):
    name = name.removeprefix("./")
    if name.startswith("/") or ".." in name.split("/"):
        raise ValueError(f"unsafe archive path: {name}")
    return name


def unpack_sv(data):
    decoder = zlib.decompressobj(31)
    raw = decoder.decompress(data) + decoder.flush()
    tail = decoder.unused_data
    if (
        not decoder.eof
        or len(tail) != 512
        or not tail.startswith(b"Desay SV Automotive\0")
    ):
        raise ValueError("unrecognized SV container")
    payload = data[:-512]
    expected = tail[0x120:0x140].rstrip(b"\0").decode("ascii")
    if hashlib.md5(payload).hexdigest() != expected or struct.unpack_from(
        "<Q", tail, 0x140
    )[0] != len(raw):
        raise ValueError("SV checksum/length mismatch")
    entries = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            key = normalized(member.name)
            if key in entries:
                raise ValueError(f"duplicate member: {key}")
            contents = archive.extractfile(member).read() if member.isfile() else None
            entries[key] = (copy.copy(member), contents)
    return entries, tail


def pack_sv(entries, tail):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.GNU_FORMAT) as archive:
        for member, contents in entries.values():
            member = copy.copy(member)
            if contents is not None:
                member.size = len(contents)
            archive.addfile(
                member, io.BytesIO(contents) if contents is not None else None
            )
    raw = output.getvalue()
    compressed = gzip.compress(raw, compresslevel=9, mtime=0)
    footer = bytearray(tail)
    footer[0x120:0x140] = hashlib.md5(compressed).hexdigest().encode("ascii")
    struct.pack_into("<Q", footer, 0x140, len(raw))
    result = compressed + footer
    checked, checked_tail = unpack_sv(result)
    verify_entries(entries, checked)
    if checked_tail[:0x120] != tail[:0x120] or checked_tail[0x148:] != tail[0x148:]:
        raise ValueError("SV metadata unexpectedly changed")
    return result


def verify_entries(expected, actual):
    if list(expected) != list(actual):
        raise ValueError("archive member order/set changed")
    for name, (member, contents) in expected.items():
        new_member, new_contents = actual[name]
        metadata = (
            "mode",
            "uid",
            "gid",
            "uname",
            "gname",
            "type",
            "linkname",
            "devmajor",
            "devminor",
            "mtime",
        )
        if contents != new_contents or any(
            getattr(member, k) != getattr(new_member, k) for k in metadata
        ):
            raise ValueError(f"archive round-trip failed: {name}")
