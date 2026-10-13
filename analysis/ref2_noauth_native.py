"""Hash-pinned, range-audited ARM source adaptation; preserves QNX loader/media ABI.

Keystone is used only as an assembler, Unicorn only for offline CPU/contract tests.
This is NOT a fresh QNX compilation nor an on-target mm-renderer/Screen test.
No supplied archive is ever written. No MCU is parsed or changed by this module.
"""
import hashlib
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "analysis/.native-deps"))
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM, KS_MODE_LITTLE_ENDIAN
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from inspect_qnx_elf import virtual_bytes

RECEIVER_HASH = "5a657b5d0c0eabaf4161df5a6882846fc0d149d1e5bc093e49b8499073868741"
USB_HASH = "b86dbcdae3ded35dcf1984ed26eb5cfade52cc69ddb01bd13882b49c2d9fdb20"
SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-dcv1.arm"
NOP = bytes.fromhex("00f020e3")


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Patch:
    def __init__(self, source, expected):
        if digest(source) != expected:
            raise ValueError("Native source hash is not the inspected reference-2 input")
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
        assembled, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(source, addr=address)
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
        # Deliberate media call/cleanup edits are confined to the reported ranges.
        return bytes(self.output), dict(input_sha256=digest(self.original),
                                       output_sha256=digest(self.output), patches=self.ranges,
                                       qnx_runtime_tested=False)


def build_receiver(source):
    patch = Patch(source, RECEIVER_HASH)
    arm_source = SOURCE.read_text(encoding="utf-8")
    blocks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", arm_source)
    for index in range(1, len(blocks), 4):
        name, address, size, code = blocks[index:index + 4]
        code = re.sub(r"(?m)//.*$", "", code).strip()
        patch.arm(name, int(address, 16), int(size), code)
    patch.arm("ack_entry", 0x10162c, 168, "b #0x102a30")
    patch.arm("offer_magic_low", 0x10175c, 4, "movw r3, #0x4344")
    patch.arm("offer_magic_high", 0x101760, 4, "movt r3, #0x3156")
    # Check the existing stop flag at least every 100 ms, including a SIGTERM
    # delivered between the flag check and poll(). Preserve deadline/error logic.
    # The reference caps poll at INT_MAX ms; an idle listener can otherwise miss
    # that signal and remain blocked long after the role-restoration wait ends.
    patch.put("poll_stop_race_bound_100ms", 0x101518,
              bytes.fromhex("6420a0e3"), expected=bytes.fromhex("0221e0e3"))
    patch.arm("frame_parser_call_stage", 0x101b38, 4, "bl #0x102d68")
    patch.arm("frame_media_call_stage", 0x101ebc, 4, "bl #0x102dbc")
    patch.arm("push_submit_failure_stage", 0x107e18, 4, "blt #0x102e10")
    patch.arm("media_guard_failure_stage", 0x107ec4, 4, "mvn r0, #4")
    # The reference hard-caps each owned resource cleanup IPC at 1s, even
    # context destruction that stops IVA playback. Keep it bounded, allow 2s;
    # preserve every genuine cleanup error and the quarantine guard.
    patch.put("owned_cleanup_ipc_budget_2s", 0x1082c8,
              (2000000000).to_bytes(8, "little"),
              expected=(1000000000).to_bytes(8, "little"))
    # No usable third-party RSA public key is carried in the new receiver.
    patch.put("unused_public_key_removed", 0x10ae68, bytes(270))
    result, report = patch.finish()
    report["arm_source_sha256"] = digest(arm_source.encode("utf-8"))
    report["protocol"] = "DCV1/1, TCP31707, no crypto/activation/identity"
    return result, report


def build_usb(source):
    patch = Patch(source, USB_HASH)
    patch.put("all_text_command_paths_allow_USB_only", 0x1023d4,
              bytes.fromhex("030054e3"), expected=bytes.fromhex("070054e3"))
    # The reference does a blocking waitpid(pid, ..., 0) after SIGKILL. A child
    # blocked inside a resource manager can keep the serial USB command receiver
    # stuck indefinitely, even though the function claims to have a timeout.
    # Preserve the normal deadline/TERM/KILL path; bound only post-KILL reaping.
    patch.arm("kill_reap_entry", 0x1014d8, 4, "b #0x1014dc")
    patch.arm("term_exit_goes_to_timeout", 0x1014c8, 4, "bne #0x101548")
    patch.arm("post_kill_reap_bounded", 0x1014dc, 108, """
        mov r6, #10
    reap:
        mov r2, #0x40
        add r1, sp, #0xc
        mov r0, r8
        bl #0x100d70
        cmp r0, r8
        beq #0x101548
        cmp r0, #0
        blt #0x101548
        mov r3, #0
        add r0, sp, #0x10
        mov r1, r3
        str sb, [sp, #0x14]
        str r3, [sp, #0x10]
        bl #0x100e84
        subs r6, r6, #1
        bne reap
        b #0x101548
    """)
    # Distinguish a timeout from a legitimate child exit code 3. Scripts retain
    # their owned RAM operation guard on 124, preventing overlap with a child
    # whose termination was not confirmed. No shared QNX process is force-killed.
    patch.put("command_timeout_exit_124", 0x101564,
              bytes.fromhex("7c00a0e3"), expected=bytes.fromhex("0300a0e3"))
    # Serial bounded owned notice child: the old async replace path could kill a
    # notice before drawing, or drop the next notice while reaping was pending.
    # --notice needs no license. Timeout affects feedback, never reports USB ready.
    patch.arm("notice_serial_bounded", 0x10316c, 248, """
        cmp r0, #0
        bxeq lr
        cmp r1, #9
        bxhi lr
        push {r4, lr}
        sub sp, sp, #24
        str r0, [sp]
        movw r2, #0x897c
        movt r2, #0x10
        str r2, [sp, #4]
        add r2, sp, #16
        str r2, [sp, #8]
        add r1, r1, #48
        strb r1, [sp, #16]
        mov r1, #0
        strb r1, [sp, #17]
        str r1, [sp, #12]
        mov r0, sp
        mov r1, #3
        bl #0x101320
        add sp, sp, #24
        pop {r4, pc}
    """)
    # The deployed OS/IFS omit the rmdir executable, but this ELF already
    # imports libc rmdir. Reuse the unreachable, USB-only-disabled --control
    # entry/slot for TWO exact owned empty directories, never recursive rm.
    patch.put("owned_empty_guard_cli", 0x108920, b"--rmdir\0\0\0",
              expected=b"--control\0")
    patch.arm("owned_empty_guard_entry", 0x1019f8, 44, """
        ldr r0, [r4, #8]
        bl #0x1080c0
        mov r4, r0
        b #0x1019cc
    """)
    patch.arm("owned_empty_guard_release", 0x1080c0, 512, """
        push {r4, lr}
        mov r4, r0
        movw r1, #0x82c0
        movt r1, #0x10
        bl #0x100fb0
        cmp r0, #0
        beq guard_remove
        mov r0, r4
        movw r1, #0x82f0
        movt r1, #0x10
        bl #0x100fb0
        cmp r0, #0
        bne guard_denied
    guard_remove:
        mov r0, r4
        bl #0x100e24
        cmp r0, #0
        movne r0, #1
        pop {r4, pc}
    guard_denied:
        mov r0, #2
        pop {r4, pc}
    """)
    guard_paths = b"/fs/tmpfs/dip-usb/role-operation\0"
    guard_paths = guard_paths.ljust(48, b"\0") + b"/fs/tmpfs/dip-usb/cast-guard\0"
    patch.put("owned_empty_guard_exact_paths", 0x1082c0,
              guard_paths.ljust(432, b"\0"))
    return patch.finish()
