"""Read-only QNX ELF inspection directly from the supplied firmware archive."""

import argparse
import io
import re
import struct
import tarfile
import zipfile
from itertools import pairwise

from capstone import CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB, Cs
from elftools.elf.elffile import ELFFile


def virtual_bytes(elf, source, address, length):
    """mkifs may relocate file offsets: PT_LOAD, not PT_DYNAMIC.p_offset, wins."""
    for segment in elf.iter_segments():
        begin = int(segment["p_vaddr"])
        if (
            segment["p_type"] == "PT_LOAD"
            and begin <= address
            and address + length <= begin + int(segment["p_filesz"])
        ):
            offset = int(segment["p_offset"]) + address - begin
            raw = source[offset : offset + length]
            if len(raw) != length:
                raise ValueError("truncated mapped ELF range")
            return raw
    raise ValueError(f"unmapped ELF range: {address:#x}+{length:#x}")


def dynamic_metadata(elf, source):
    if elf.elfclass != 32 or not elf.little_endian:
        raise ValueError("only little-endian ELF32 dynamic metadata supported")
    segment = next(
        (s for s in elf.iter_segments() if s["p_type"] == "PT_DYNAMIC"), None
    )
    if segment is None:
        return {}, []
    tags = {}
    for tag, value in struct.iter_unpack(
        "<II", virtual_bytes(elf, source, segment["p_vaddr"], segment["p_filesz"])
    ):
        if tag == 0:
            break
        tags[tag] = value
    if tags.get(11) != 16:
        raise ValueError("only ELF32 dynamic symbols supported")
    if 4 in tags:
        count = struct.unpack("<II", virtual_bytes(elf, source, tags[4], 8))[1]
    elif 0x6FFFFEF5 in tags:
        address = tags[0x6FFFFEF5]
        buckets, first, bloom, _ = struct.unpack(
            "<4I", virtual_bytes(elf, source, address, 16)
        )
        if not 0 < buckets < 65536 or not 0 < bloom < 65536:
            raise ValueError("GNU hash bounds")
        bucket_base = address + 16 + 4 * bloom
        indices = struct.unpack(
            "<" + "I" * buckets, virtual_bytes(elf, source, bucket_base, 4 * buckets)
        )
        count = first
        chain_base = bucket_base + 4 * buckets
        for index in indices:
            if index == 0:
                continue
            if index < first:
                raise ValueError("GNU hash symbol index")
            while index < 65536:
                word = struct.unpack(
                    "<I",
                    virtual_bytes(elf, source, chain_base + 4 * (index - first), 4),
                )[0]
                index += 1
                if word & 1:
                    break
            else:
                raise ValueError("unterminated GNU hash chain")
            count = max(count, index)
    else:
        raise ValueError("no supported dynamic symbol hash")
    if not 0 < count < 65536:
        raise ValueError("dynamic symbol count bounds")
    strings = virtual_bytes(elf, source, tags[5], tags[10])

    class Symbol(dict):
        @property
        def name(self):
            return self["name"]

    symbols = []
    for offset in range(count):
        name, value, size, info, other, section = struct.unpack(
            "<IIIBBH", virtual_bytes(elf, source, tags[6] + offset * 16, 16)
        )
        if name >= len(strings) or b"\0" not in strings[name:]:
            raise ValueError("dynamic symbol string bounds")
        symbols.append(
            Symbol(
                name=strings[name:].split(b"\0", 1)[0].decode("utf-8"),
                st_value=value,
                st_size=size,
                st_info=info,
                st_other=other,
                st_shndx=section,
            )
        )
    return tags, symbols


def all_symbols(elf, source):
    sections = [
        s for s in elf.iter_sections() if s["sh_type"] in ("SHT_SYMTAB", "SHT_DYNSYM")
    ]
    if sections:
        return [symbol for section in sections for symbol in section.iter_symbols()]
    return dynamic_metadata(elf, source)[1]


def plt_symbols(elf, source):
    """Resolve ARM PLT GOT loads, including an optional Thumb bx-pc veneer."""
    section = elf.get_section_by_name(".plt")
    relocations = elf.get_section_by_name(".rel.plt")
    if section is None or relocations is None:
        tags, symbols = dynamic_metadata(elf, source)
        if 23 not in tags:
            return {}
        if tags[20] != 17 or tags[2] % 8:
            raise ValueError("only ELF32 REL PLT supported")
        got = {
            address: symbols[info >> 8].name
            for address, info in struct.iter_unpack(
                "<II", virtual_bytes(elf, source, tags[23], tags[2])
            )
        }
        chunks = [
            (int(s["p_vaddr"]), virtual_bytes(elf, source, s["p_vaddr"], s["p_filesz"]))
            for s in elf.iter_segments()
            if s["p_type"] == "PT_LOAD" and s["p_flags"] & 1
        ]
    else:
        symtab = elf.get_section(relocations["sh_link"])
        got = {
            int(r["r_offset"]): symtab.get_symbol(r["r_info_sym"]).name
            for r in relocations.iter_relocations()
        }
        chunks = [(int(section["sh_addr"]), section.data())]
    result = {}
    current = None
    start = None
    decoder = Cs(CS_ARCH_ARM, CS_MODE_ARM)
    decoder.skipdata = True
    for base, raw in chunks:
        current = None
        for ins in decoder.disasm(raw, base):
            match = re.fullmatch(
                r"ip, (pc|ip), #(0x[0-9a-f]+|[0-9]+)(?:, #([0-9]+))?", ins.op_str
            )
            if ins.mnemonic == "add" and match:
                register, immediate, rotation = match.groups()
                if register == "pc":
                    current = ins.address + 8
                    start = ins.address
                if current is not None:
                    value = int(immediate, 0)
                    if rotation:
                        shift = int(rotation) % 32
                        value = (
                            (value >> shift) | (value << (32 - shift))
                        ) & 0xFFFFFFFF
                    current += value
            match = re.fullmatch(
                r"pc, \[ip(?:, #(0x[0-9a-f]+|[0-9]+))?\]!?", ins.op_str
            )
            if ins.mnemonic == "ldr" and match and current is not None:
                name = got.get(current + int(match.group(1) or "0", 0))
                if name:
                    result[start] = name
                    offset = start - base
                    if offset >= 4 and raw[offset - 4 : offset] == bytes.fromhex(
                        "7847c046"
                    ):
                        result[start - 4] = name
                current = None
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive")
    parser.add_argument("component", nargs="?")
    parser.add_argument("member", nargs="?")
    parser.add_argument(
        "--local",
        action="store_true",
        help="inspect a previously extracted ELF, read-only",
    )
    parser.add_argument("--contains", action="append", default=[])
    parser.add_argument("--text", action="store_true")
    parser.add_argument("--strings", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--start", type=lambda value: int(value, 0))
    parser.add_argument("--end", type=lambda value: int(value, 0))
    parser.add_argument("--thumb", action="store_true")
    parser.add_argument("--xref", action="store_true")
    args = parser.parse_args()
    if args.local:
        with open(args.archive, "rb") as local:
            data = local.read()
    else:
        if args.component is None or args.member is None:
            raise SystemExit("archive inspection requires component and member")
        with (
            zipfile.ZipFile(args.archive) as archive,
            tarfile.open(
                fileobj=io.BytesIO(archive.read(args.component)), mode="r:gz"
            ) as tar,
        ):
            member = next(
                (
                    m
                    for m in tar.getmembers()
                    if m.name.lstrip("./") == args.member.lstrip("./")
                ),
                None,
            )
            if member is None:
                raise SystemExit(f"member not found: {args.member}")
            source = tar.extractfile(member)
            if source is None:
                raise SystemExit("not a regular file")
            data = source.read()
    if args.text:
        print(data.decode("utf-8", errors="replace"))
        return
    elf = ELFFile(io.BytesIO(data))
    if args.start is not None or args.xref:
        if not args.xref and (
            args.end is None or args.end <= args.start or args.end - args.start > 65536
        ):
            raise SystemExit("range requires end > start, maximum 65536 bytes")
        symbols = {
            int(s["st_value"]) & ~1: s.name
            for s in all_symbols(elf, data)
            if s["st_value"]
        }
        symbols.update(plt_symbols(elf, data))

        def bytes_at(address, length):
            try:
                return virtual_bytes(elf, data, address, length)
            except ValueError:
                return b""

        if args.xref:
            section = elf.get_section_by_name(".text")
            args.start = int(section["sh_addr"])
            args.end = args.start + int(section["sh_size"])
        decoder = Cs(CS_ARCH_ARM, CS_MODE_THUMB if args.thumb else CS_MODE_ARM)
        decoder.skipdata = True
        raw_code = bytes_at(args.start, args.end - args.start)
        instructions = (
            (
                instruction
                for offset in range(0, len(raw_code) - 3, 2 if args.thumb else 4)
                for instruction in decoder.disasm(
                    raw_code[offset : offset + 4], args.start + offset, count=1
                )
            )
            if args.xref
            else decoder.disasm(raw_code, args.start)
        )
        for instruction in instructions:
            note = ""
            if instruction.mnemonic in (
                "bl",
                "blx",
                "b",
                "b.w",
            ) and instruction.op_str.startswith("#0x"):
                note = symbols.get(int(instruction.op_str[1:], 16), "")
            match = re.search(
                r"\[pc(?:, #(-?0x[0-9a-f]+|-?[0-9]+))?\]", instruction.op_str
            )
            if instruction.mnemonic.startswith("ldr") and match:
                pc = (
                    ((instruction.address + 4) & ~3)
                    if args.thumb
                    else instruction.address + 8
                )
                literal = bytes_at(pc + int(match.group(1) or "0", 0), 4)
                if len(literal) == 4:
                    value = struct.unpack("<I", literal)[0]
                    note = f"literal=0x{value:08x}"
                    raw = bytes_at(value, 1)
                    if raw:
                        raw = b"".join(
                            bytes_at(value + i, 1) for i in range(400)
                        ).split(b"\0", 1)[0]
                        if raw and all(32 <= c < 127 or c in (9, 10, 13) for c in raw):
                            note += " " + repr(raw.decode("ascii"))
            if not args.xref or any(word in note for word in args.contains):
                print(
                    f"{instruction.address:08x}: {instruction.mnemonic:8s} {instruction.op_str:32s} {note}"
                )
        return
    if args.strings:
        chunks = [
            (int(s["sh_addr"]), s.data())
            for s in elf.iter_sections()
            if s["sh_type"] == "SHT_PROGBITS" and s["sh_flags"] & 2
        ]
        if not chunks:
            chunks = [
                (
                    int(s["p_vaddr"]),
                    virtual_bytes(elf, data, s["p_vaddr"], s["p_filesz"]),
                )
                for s in elf.iter_segments()
                if s["p_type"] == "PT_LOAD"
            ]
        for base, raw in chunks:
            for match in re.finditer(rb"[ -~]{4,}", raw):
                value = match.group().decode("ascii")
                if not args.contains or any(word in value for word in args.contains):
                    print(f"{base + match.start():08x}: {value}")
        return
    if elf["e_machine"] != "EM_ARM":
        raise SystemExit(f"unsupported machine: {elf['e_machine']}")
    symbols = {}
    mapping_symbols = {}
    for symbol in all_symbols(elf, data):
        if symbol.name.startswith(("$t", "$a", "$d")):
            mapping_symbols[int(symbol["st_value"])] = symbol.name[:2]
        elif symbol["st_value"]:
            symbols[int(symbol["st_value"]) & ~1] = symbol.name
    symbols.update(plt_symbols(elf, data))
    seen = set()
    for symbol in all_symbols(elf, data):
        identity = (symbol.name, int(symbol["st_value"]))
        if identity in seen:
            continue
        seen.add(identity)
        if args.contains and not any(word in symbol.name for word in args.contains):
            continue
        if args.list:
            print(
                f"{int(symbol['st_value']):08x} size={symbol['st_size']} {symbol.name}"
            )
            continue
        address = int(symbol["st_value"]) & ~1
        size = int(symbol["st_size"])
        if not address or not size:
            continue
        for segment in elf.iter_segments():
            start = int(segment["p_vaddr"])
            if start <= address < start + int(segment["p_filesz"]):
                offset = int(segment["p_offset"]) + address - start
                break
        else:
            continue
        print(f"\n=== {symbol.name} @{address:08x} size={size} ===")
        mode = CS_MODE_THUMB if int(symbol["st_value"]) & 1 else CS_MODE_ARM
        low_immediates = {}

        def instructions(address=address, size=size, mode=mode, offset=offset):
            points = (
                [address]
                + sorted(p for p in mapping_symbols if address < p < address + size)
                + [address + size]
            )
            for begin, end in pairwise(points):
                earlier = [p for p in mapping_symbols if p <= begin]
                kind = (
                    mapping_symbols[max(earlier)]
                    if earlier
                    else ("$t" if mode == CS_MODE_THUMB else "$a")
                )
                if kind == "$d":
                    continue
                chunk_mode = CS_MODE_THUMB if kind == "$t" else CS_MODE_ARM
                yield from Cs(CS_ARCH_ARM, chunk_mode).disasm(
                    data[offset + begin - address : offset + end - address], begin
                )

        for ins in instructions():
            note = ""
            if ins.mnemonic in ("bl", "blx", "b") and ins.op_str.startswith("#0x"):
                note = symbols.get(int(ins.op_str[1:], 16), "")
            if ins.mnemonic in ("movw", "movt"):
                register, immediate = ins.op_str.split(", ", 1)
                value = int(immediate.replace("#", ""), 0)
                if ins.mnemonic == "movw":
                    low_immediates[register] = value
                elif register in low_immediates:
                    value = (value << 16) | low_immediates[register]
                    note = f"address=0x{value:x}"
                    for seg in elf.iter_segments():
                        begin = int(seg["p_vaddr"])
                        if begin <= value < begin + int(seg["p_filesz"]):
                            pos = int(seg["p_offset"]) + value - begin
                            candidate = data[pos : pos + 200].split(b"\0", 1)[0]
                            if candidate and all(
                                32 <= c < 127 or c in (9, 10, 13) for c in candidate
                            ):
                                note += " " + repr(candidate.decode("ascii"))
                            break
            if ins.mnemonic in ("ldr", "ldr.w") and "[pc" in ins.op_str:
                immediate = (
                    ins.op_str.split("[pc", 1)[1]
                    .split("]", 1)[0]
                    .replace(",", "")
                    .strip()
                )
                displacement = int(immediate.replace("#", ""), 0) if immediate else 0
                literal_address = (
                    ins.address + 8 if mode == CS_MODE_ARM else (ins.address + 4) & ~3
                )
                literal_offset = offset + literal_address - address + displacement
                if 0 <= literal_offset <= len(data) - 4:
                    value = struct.unpack_from("<I", data, literal_offset)[0]
                    note = f"literal=0x{value:x}"
                    for seg in elf.iter_segments():
                        begin = int(seg["p_vaddr"])
                        if begin <= value < begin + int(seg["p_filesz"]):
                            pos = int(seg["p_offset"]) + value - begin
                            candidate = data[pos : pos + 160].split(b"\0", 1)[0]
                            if candidate and all(32 <= c < 127 for c in candidate):
                                note += " " + repr(candidate.decode("ascii"))
                            break
            print(f"{ins.address:08x}: {ins.mnemonic:8} {ins.op_str:32} {note}")


if __name__ == "__main__":
    main()
