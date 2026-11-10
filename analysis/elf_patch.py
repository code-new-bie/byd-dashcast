"""Hash-pinned, range-audited patching of a QNX ARM ELF; preserves its loader and imported ABI.

Keystone is used only as an assembler, Capstone only to check what was assembled.
This is NOT a fresh QNX compilation nor an on-target test. No input file is ever written.
"""
import hashlib
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "analysis/.native-deps"))
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM, KS_MODE_LITTLE_ENDIAN  # noqa: E402
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM  # noqa: E402
from elftools.elf.elffile import ELFFile  # noqa: E402

NOP = bytes.fromhex("00f020e3")


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Patch:
    def __init__(self, source, expected):
        if digest(source) != expected:
            raise ValueError("Input hash is not the pinned original")
        self.original = source
        self.output = bytearray(source)
        self.elf = ELFFile(io.BytesIO(source))
        if self.elf.elfclass != 32 or self.elf["e_machine"] != "EM_ARM" or not self.elf.little_endian:
            raise ValueError("Unexpected QNX ELF ABI")
        self.ranges = []

    def offset(self, address, length):
        for segment in self.elf.iter_segments():
            start = int(segment["p_vaddr"])
            if (segment["p_type"] == "PT_LOAD" and start <= address
                    and address + length <= start + int(segment["p_filesz"])):
                return int(segment["p_offset"]) + address - start
        raise ValueError("Patch range not in a file-backed LOAD segment")

    def put(self, name, address, data, expected=None):
        offset = self.offset(address, len(data))
        before = self.original[offset:offset + len(data)]
        if expected is not None and before != expected:
            raise ValueError(f"Instruction precondition failed: {name}")
        if any(offset < row["offset"] + row["bytes"] and row["offset"] < offset + len(data)
               for row in self.ranges):
            raise ValueError(f"Overlapping patch: {name}")
        self.output[offset:offset + len(data)] = data
        self.ranges.append(dict(name=name, va=hex(address), offset=offset, bytes=len(data),
                                before_sha256=digest(before), after_sha256=digest(data)))

    def arm(self, name, address, capacity, source):
        try:
            assembled, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(source, addr=address)
        except Exception as error:
            raise ValueError(f"ARM assembly failed: {name}: {error}") from error
        code = bytes(assembled)
        if len(code) > capacity or len(code) % 4 or capacity % 4:
            raise ValueError(f"ARM source exceeds its pinned slot: {name}, {len(code)}/{capacity}")
        decoded = list(Cs(CS_ARCH_ARM, CS_MODE_ARM).disasm(code, address))
        if len(decoded) * 4 != len(code):
            raise ValueError("Invalid ARM instruction")
        self.put(name, address, code + NOP * ((capacity - len(code)) // 4))
        self.ranges[-1]["source_bytes"] = len(code)

    def finish(self):
        allowed = bytearray(len(self.original))
        for row in self.ranges:
            allowed[row["offset"]:row["offset"] + row["bytes"]] = b"\x01" * row["bytes"]
        if len(self.output) != len(self.original) or any(
                a != b and not permitted for a, b, permitted in zip(self.original, self.output, allowed)):
            raise ValueError("Changes escaped audited slots")
        # Program/section headers, relocations and imported ABI are unchanged.
        return bytes(self.output), dict(input_sha256=digest(self.original),
                                       output_sha256=digest(self.output), patches=self.ranges,
                                       qnx_runtime_tested=False)
