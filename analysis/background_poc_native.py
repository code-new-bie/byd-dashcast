"""Hash-pinned 5s render-time background PoC; no SDK/target-runtime claim.

The HMI additions occupy verified zero file padding plus newly extended BSS.
No live business routine is reused. Original ELF entry/imports/relocations stay.
"""
import io
import re
import struct

from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from ref2_noauth_native import ROOT, digest, Patch, NOP, build_receiver
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB, KS_MODE_LITTLE_ENDIAN
from keystone import KS_MODE_ARM

HMI_SOURCE = ROOT / "phase1/qnx/native-hmi/background-poc.thumb"
HMI_BASE_SHA = "5fe489555ac7e3cc3cc4f73cfd47fa3cca580d881e570d22e4ac93a6fad07b10"
CAVE = 0x086a2850
CAVE_OFFSET = 0x65a850
CAVE_CAPACITY = 0x624
STATE = 0x086e84e0
STATE_BYTES = 128
LABELS = ("bg_getter", "bg_clear", "bg_render", "bg_brush", "bg_tick", "bg_find",
          "bg_leaf_named", "bg_state_address", "bg_original_literal", "bg_billion",
          "bg_half_billion", "bg_magic", "bg_root_name", "bg_content_name",
          "bg_background_name", "bg_image_name", "bg_sky_name", "bg_path", "bg_done")
RECEIVER_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-background-poc.arm"
BASE_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-dcv1-hd-window.arm"
RECEIVER_BASE_SHA = "9c71b0631c5045ae29e2e01092d039a8d193546eb25eed9083c4d5aa112fa563"


def apply_receiver_overlay(base):
    patch = Patch(base, RECEIVER_BASE_SHA)
    source = RECEIVER_SOURCE.read_text("utf-8")
    chunks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    expected = {"background_dispatch": (0x102e1c, 124), "background_control": (0x108898, 3232)}
    seen = set()
    for i in range(1, len(chunks), 4):
        name, address, capacity, code = chunks[i:i + 4]
        address, capacity = int(address, 16), int(capacity)
        if name in seen or expected.get(name) != (address, capacity):
            raise ValueError("Unexpected background receiver slot")
        seen.add(name)
        code = re.sub(r"(?m)//.*$", "", code).strip()
        raw, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(code, addr=address)
        raw = bytes(raw)
        if len(raw) > capacity or len(raw) % 4:
            raise ValueError("Background receiver slot overflow")
        patch.put(name, address, raw + NOP * ((capacity - len(raw)) // 4))
        patch.ranges[-1]["source_bytes"] = len(raw)
    if seen != set(expected):
        raise ValueError("Incomplete receiver overlay")
    patch.arm("background_dispatch_entry", 0x101998, 4, "b #0x102e1c")
    header = BASE_SOURCE.read_text("utf-8").split("// SLOT header_whitelist 0x103fc8 484\n", 1)[1].split("// SLOT ", 1)[0]
    old = "cmp r0, #8\nbeq header_heartbeat\nb header_fail"
    new = "cmp r0, #8\nbeq header_heartbeat\ncmp r0, #10\nbne header_fail\ncmp r1, #16\nb header_result"
    if header.count(old) != 1:
        raise ValueError("Legacy header precondition")
    patch.arm("background_header_whitelist", 0x103fc8, 484, re.sub(r"(?m)//.*$", "", header.replace(old, new)).strip())
    binary, report = patch.finish()
    report.update(source_sha256=digest(source.encode()), fullscreen_enabled=False,
                  baseline_sha256=digest(base), type9_supported=False, wire="DCV1 type10/16-byte BGP1 PoC")
    return binary, report


def build_background_receiver(original):
    base, _ = build_receiver(original, BASE_SOURCE)
    return apply_receiver_overlay(base)


def compile_thumb():
    source = HMI_SOURCE.read_text("utf-8")
    code = re.sub(r"(?m)//.*$", "", source)
    # Keystone's Thumb relaxation can move a previously encoded numeric BL.
    # Resolve external four-byte branches AFTER final label/layout resolution.
    external = []

    def defer_branch(match):
        name = "bg_external_" + str(len(external))
        external.append((name, match.group(1), int(match.group(2), 16)))
        return name + ":\nnop.w"

    code = re.sub(r"(?m)^(blx|bl|b\.w) #0x([0-9a-fA-F]+)\s*$", defer_branch, code)
    # CBZ has only a short forward displacement; never allow silent wrapping.
    code = re.sub(r"(?m)^cb(z|nz) (r\d+), (\w+)\s*$",
                  lambda m: f"cmp {m.group(2)}, #0\nb{'eq' if m.group(1) == 'z' else 'ne'}.w {m.group(3)}", code)
    # Marker words are stripped, never included in the ELF. Resolve using the
    # same assembler/layout as the bytes under test, not guessed instruction sizes.
    all_labels = LABELS + tuple(x[0] for x in external)
    augmented = code + "\n.balign 4\n" + "\n".join(".word " + x for x in all_labels)
    raw, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN).asm(augmented, addr=CAVE)
    if raw is None:
        raise ValueError("Thumb assembly returned no code")
    raw = bytes(raw)
    marker_bytes = 4 * len(all_labels)
    values = struct.unpack("<" + "I" * len(all_labels), raw[-marker_bytes:])
    resolved = dict(zip(all_labels, values))
    labels = {x: resolved[x] for x in LABELS}
    binary = bytearray(raw[:-marker_bytes])
    for name, instruction, target in external:
        address = resolved[name]
        encoded, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f"{instruction} #{target}", addr=address)
        check = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(bytes(encoded), address))
        if len(encoded) != 4 or len(check) != 1 or int(check[0].op_str.removeprefix("#"), 16) != target:
            raise ValueError("Thumb external branch target mismatch")
        binary[address - CAVE:address - CAVE + 4] = bytes(encoded)
    if len(binary) > CAVE_CAPACITY or len(binary) % 4:
        raise ValueError(f"HMI bridge exceeds zero padding: {len(binary)}/{CAVE_CAPACITY}")
    if any(not CAVE <= a < CAVE + len(binary) for a in labels.values()):
        raise ValueError("Thumb label escaped cave")
    return bytes(binary), labels, digest(source.encode())


def apply_hmi_overlay(base, expected_sha=HMI_BASE_SHA):
    if not expected_sha or digest(base) != expected_sha:
        raise ValueError("Not the exact inspected HMI baseline")
    elf = ELFFile(io.BytesIO(base))
    if elf.elfclass != 32 or not elf.little_endian or elf["e_machine"] != "EM_ARM" or elf["e_type"] != "ET_EXEC":
        raise ValueError("Unexpected HMI ELF ABI")
    segments = list(elf.iter_segments())
    rx = next(s for s in segments if s["p_type"] == "PT_LOAD" and s["p_flags"] == 5)
    rw = next(s for s in segments if s["p_type"] == "PT_LOAD" and s["p_flags"] == 6)
    if (int(rx["p_offset"]), int(rx["p_vaddr"]), int(rx["p_filesz"]), int(rx["p_memsz"])) != (
            0, 0x08048000, CAVE_OFFSET, CAVE_OFFSET):
        raise ValueError("Unexpected RX segment")
    if int(rw["p_offset"]) != CAVE_OFFSET + CAVE_CAPACITY or int(rw["p_vaddr"]) + int(rw["p_memsz"]) != 0x086e84dc:
        raise ValueError("Unexpected RW segment/gap")
    if base[CAVE_OFFSET:CAVE_OFFSET + CAVE_CAPACITY] != bytes(CAVE_CAPACITY):
        raise ValueError("HMI code cave is not zero padding")
    for section in elf.iter_sections():
        if section["sh_type"] != "SHT_NOBITS" and section["sh_size"] and (
                section["sh_offset"] < CAVE_OFFSET + CAVE_CAPACITY
                and section["sh_offset"] + section["sh_size"] > CAVE_OFFSET):
            raise ValueError("HMI cave overlaps a section")
    code, labels, source_sha = compile_thumb()
    output = bytearray(base)
    allowed = []

    def put(name, offset, data, before=None):
        old = base[offset:offset + len(data)]
        if len(old) != len(data) or (before is not None and old != before):
            raise ValueError("HMI precondition: " + name)
        if any(offset < r["offset"] + r["bytes"] and r["offset"] < offset + len(data) for r in allowed):
            raise ValueError("HMI patch overlap")
        output[offset:offset + len(data)] = data
        allowed.append({"name": name, "offset": offset, "bytes": len(data),
                        "before_sha256": digest(old), "after_sha256": digest(data)})

    for address, target, instruction, expected in (
            (0x081eb848, CAVE, "bl", bytes.fromhex("4df754f9")),
            (0x081eb892, CAVE + 4, "bl", None),
            (0x081d09ec, CAVE + 8, "b.w", bytes.fromhex("08b50368")),
            (0x081d09f8, CAVE + 12, "b.w", bytes.fromhex("144a70b5"))):
        offset = address - 0x08048000
        original = base[offset:offset + 4]
        decoded = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(original, address))
        if instruction == "bl" and (len(decoded) != 1 or decoded[0].mnemonic != "bl"
                or decoded[0].op_str != ("#0x8138af4" if address == 0x081eb848 else "#0x831308c")):
            raise ValueError("HMI original call target changed")
        replacement, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(f"{instruction} #{target}", addr=address)
        if len(replacement) != 4:
            raise ValueError("HMI hook is not four bytes")
        put(f"hook_{address:x}", offset, bytes(replacement), expected)
    put("zero_padding_bridge", CAVE_OFFSET, code, bytes(len(code)))
    for index, segment in enumerate(segments):
        ph = int(elf["e_phoff"]) + index * int(elf["e_phentsize"])
        if segment is rx:
            put("rx_file_and_memory_size", ph + 16, struct.pack("<II", CAVE_OFFSET + len(code), CAVE_OFFSET + len(code)))
        elif segment is rw:
            new_size = STATE + STATE_BYTES - int(rw["p_vaddr"])
            put("rw_zero_fill_size", ph + 20, struct.pack("<I", new_size))
    bss_index, bss = next((i, s) for i, s in enumerate(elf.iter_sections()) if s.name == ".bss")
    if int(bss["sh_addr"]) + int(bss["sh_size"]) != 0x086e84dc:
        raise ValueError("BSS end precondition")
    put("bss_size", int(elf["e_shoff"]) + bss_index * int(elf["e_shentsize"]) + 20,
        struct.pack("<I", STATE + STATE_BYTES - int(bss["sh_addr"])))
    # Keep the CRT/exported end-of-BSS contracts consistent with PT_LOAD. The
    # old end address occurs only in these eight symbol entries, not live code.
    end_names = {"__end__", "__bss_end__", "_end", "_bss_end__"}
    end_offsets = []
    for section in elf.iter_sections():
        if section["sh_type"] not in ("SHT_SYMTAB", "SHT_DYNSYM"):
            continue
        found = set()
        for index, symbol in enumerate(section.iter_symbols()):
            if symbol["st_value"] != 0x086e84dc:
                continue
            if symbol.name not in end_names or symbol["st_info"]["type"] != "STT_NOTYPE":
                raise ValueError("Unexpected end-of-BSS symbol")
            offset = int(section["sh_offset"]) + index * int(section["sh_entsize"]) + 4
            put(section.name + "_" + symbol.name, offset, struct.pack("<I", STATE + STATE_BYTES),
                struct.pack("<I", 0x086e84dc))
            end_offsets.append(offset)
            found.add(symbol.name)
        if found != end_names:
            raise ValueError("Incomplete end-of-BSS symbol contract")
    actual_old_end = {i for i in range(0, len(base) - 3, 4) if base[i:i + 4] == struct.pack("<I", 0x086e84dc)}
    if set(end_offsets) != actual_old_end or len(end_offsets) != 8:
        raise ValueError("Unreviewed old BSS-end address reference")
    marks = bytearray(len(base))
    for row in allowed:
        marks[row["offset"]:row["offset"] + row["bytes"]] = b"\1" * row["bytes"]
    if len(output) != len(base) or any(a != b and not m for a, b, m in zip(base, output, marks)):
        raise ValueError("HMI changes escaped allowlist")
    candidate = bytes(output)
    parsed = ELFFile(io.BytesIO(candidate))
    loads = [s for s in parsed.iter_segments() if s["p_type"] == "PT_LOAD"]
    if any(s["p_filesz"] > s["p_memsz"] for s in loads) or loads[0]["p_vaddr"] + loads[0]["p_memsz"] > (loads[1]["p_vaddr"] & ~4095):
        raise ValueError("HMI LOAD overlap")
    return candidate, {"input_sha256": digest(base), "output_sha256": digest(candidate),
                       "patches": allowed, "labels": labels, "source_sha256": source_sha,
                       "bridge_bytes": len(code), "added_zero_fill_bytes": 132,
                       "qnx_runtime_tested": False, "fullscreen_enabled": False}
