"""Pure F1 read-only overlay; requires an exact operator-provided receiver.
No private archives or paired deliveries are read by this module.
"""
import io

import re

from capstone import CS_ARCH_ARM, CS_MODE_ARM, Cs

from elftools.elf.elffile import ELFFile

from inspect_qnx_elf import virtual_bytes

from ref2_noauth_native import (ROOT, Patch, NOP, Ks, KS_ARCH_ARM, KS_MODE_ARM,
                                KS_MODE_LITTLE_ENDIAN, build_receiver, digest)

BASE_SHA = "9c71b0631c5045ae29e2e01092d039a8d193546eb25eed9083c4d5aa112fa563"

BASE_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-dcv1-hd-window.arm"

SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-display-probe.arm"

ALLOWED = {"display_query_dispatch": (0x102e1c, 124), "display_query": (0x108898, 3232)}

def apply_probe_overlay(base):
    """Pure overlay entry for independently supplied, exact 0.8.2 receiver bytes."""
    patch = Patch(base, BASE_SHA)
    source = SOURCE.read_text(encoding="utf-8")
    chunks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    names = set()
    for index in range(1, len(chunks), 4):
        name, address, capacity, code = chunks[index:index + 4]
        address, capacity = int(address, 16), int(capacity)
        if name in names or ALLOWED.get(name) != (address, capacity):
            raise ValueError("Unexpected F1 code range")
        names.add(name)
        code = re.sub(r"(?m)//.*$", "", code).strip()
        output, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(code, addr=address)
        raw = bytes(output)
        if len(raw) % 4 or len(raw) > capacity:
            raise ValueError(f"F1 slot size: {name} {len(raw)}/{capacity}")
        code_end = raw.find(b"libc.so.3\0") if name == "display_query" else len(raw)
        if code_end < 0 or code_end % 4:
            raise ValueError("F1 code/data boundary")
        instructions = list(Cs(CS_ARCH_ARM, CS_MODE_ARM).disasm(raw[:code_end], address))
        if len(instructions) * 4 != code_end:
            raise ValueError("F1 invalid instruction")
        patch.put(name, address, raw + NOP * ((capacity - len(raw)) // 4))
        patch.ranges[-1].update(source_bytes=len(raw), code_bytes=code_end)
    if names != set(ALLOWED):
        raise ValueError("F1 source incomplete")
    # Preserve the existing dispatcher for EVERY legacy header type.
    patch.arm("query_dispatch_entry", 0x101998, 4, "b #0x102e1c")
    # Change only the fixed header whitelist source; legacy checks stay exact.
    header = BASE_SOURCE.read_text(encoding="utf-8").split(
        "// SLOT header_whitelist 0x103fc8 484\n", 1)[1].split("// SLOT ", 1)[0]
    old = "cmp r0, #8\nbeq header_heartbeat\nb header_fail"
    new = ("cmp r0, #8\nbeq header_heartbeat\ncmp r0, #9\n"
           "bne header_fail\ncmp r1, #20\nb header_result")
    if header.count(old) != 1:
        raise ValueError("Legacy whitelist precondition")
    header = re.sub(r"(?m)//.*$", "", header.replace(old, new)).strip()
    patch.arm("query_header_whitelist", 0x103fc8, 484, header)
    binary, result = patch.finish()
    elf = ELFFile(io.BytesIO(binary))
    old_elf = ELFFile(io.BytesIO(base))
    for address, length in ((0x1041ac, 316), (0x1042e8, 512), (0x107cf4, 512),
                            (0x107ef4, 1048), (0x104b00, 280), (0x105888, 2004),
                            (0x104500, 512), (0x106560, 2048), (0x10a418, 424),
                            (0x10885c, 60), (0x109538, 64)):
        if virtual_bytes(elf, binary, address, length) != virtual_bytes(old_elf, base, address, length):
            raise ValueError(f"Preserved live native path changed: {address:x}")
    result.update(arm_source_sha256=digest(source.encode()), baseline_sha256=digest(base),
                  wire="DCV1 type9/20-byte read-only F1 request, F1P1 reply",
                  fullscreen_enabled=False, hmi_modified=False, qnx_runtime_tested=False,
                  retired_mask_body_reused=True, production_video_paths_preserved=True)
    return binary, result
