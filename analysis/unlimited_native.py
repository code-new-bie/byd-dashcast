"""Exact 1.0 -> no-total-duration DCV1/3, retaining operation/heartbeat/lease bounds."""
import re
import struct
from background_poc_native import ROOT, apply_hmi_overlay, digest
from ref2_noauth_native import Patch, NOP
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM, KS_MODE_LITTLE_ENDIAN
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM

RECEIVER_SHA = "974ee40f19643370b58e0846ea211ce0da5b6f841b845c03a72b7912253997d7"
HMI_SOURCE = ROOT / "phase1/qnx/native-hmi/background-unlimited.thumb"
RECEIVER_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-unlimited.arm"
CAPABILITY = 0x52574e32


def build_unlimited_hmi(original):
    binary, report = apply_hmi_overlay(original, source_path=HMI_SOURCE)
    report.update(unlimited_product_session=True, capability=CAPABILITY,
                  renewable_owned_lease=True, independent_expiry_ms=5000, physical_composition_tested=False)
    return binary, report


def apply_unlimited_receiver(base):
    patch = Patch(base, RECEIVER_SHA)
    source = RECEIVER_SOURCE.read_text("utf-8")
    blocks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    expected = {"background_dispatch": (0x102e1c, 124), "background_control": (0x108898, 3232)}
    labels = {}
    seen = set()
    for i in range(1, len(blocks), 4):
        name, address, capacity, code = blocks[i:i + 4]
        address, capacity = int(address, 16), int(capacity)
        if name in seen or expected.get(name) != (address, capacity):
            raise ValueError("Unexpected unlimited mixed slot")
        code = re.sub(r"(?m)//.*$", "", code).strip()
        helpers = ("unlimited_header_gate", "unlimited_frame_idle") if name == "background_control" else ()
        external = []
        def defer(match):
            label = f"unlimited_external_{len(external)}"
            external.append((label, match[1], int(match[2], 16)))
            return label + ":\nnop"
        # ARM ldr =symbol pools can also shift numeric branch fixups during
        # Keystone layout. Rebind each external branch after final layout.
        code = re.sub(r"(?m)^(b(?:l|lx|eq|ne|lo|hi|hs|ls|ge|gt|le|lt|mi|pl|cs|cc)?) #0x([0-9a-f]+)\s*$", defer, code)
        names = helpers + tuple(item[0] for item in external)
        # Flush ldr =symbol literal pools BEFORE marker words. Otherwise the
        # assembler appends its pool after them and the last words are not labels.
        if names: code += "\n.ltorg\n.balign 4\n" + "\n".join(".word " + n for n in names)
        raw, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(code, addr=address)
        raw = bytes(raw)
        if names:
            marker_bytes = 4 * len(names)
            resolved = dict(zip(names, struct.unpack("<" + "I" * len(names), raw[-marker_bytes:])))
            labels.update({n: resolved[n] for n in helpers})
            raw = bytearray(raw[:-marker_bytes])
            for label, instruction, target in external:
                branch, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(
                    f"{instruction} #{target}", addr=resolved[label])
                decoded = list(Cs(CS_ARCH_ARM, CS_MODE_ARM).disasm(bytes(branch), resolved[label]))
                if len(branch) != 4 or len(decoded) != 1 or int(decoded[0].op_str.removeprefix("#"), 16) != target:
                    raise ValueError("Unlimited external branch binding mismatch")
                raw[resolved[label] - address:resolved[label] - address + 4] = bytes(branch)
            raw = bytes(raw)
        if len(raw) > capacity or len(raw) % 4:
            raise ValueError("Unlimited mixed slot overflow")
        patch.put(name, address, raw + NOP * ((capacity - len(raw)) // 4))
        patch.ranges[-1]["source_bytes"] = len(raw)
        seen.add(name)
    if seen != set(expected) or any(not 0x108898 <= a < 0x108898 + 3232 for a in labels.values()):
        raise ValueError("Unlimited helper escaped inspected slot")
    legacy = (ROOT / "phase1/qnx/ref2-noauth/receiver-dcv1-hd-window.arm").read_text("utf-8")

    def slot(name, address, capacity):
        text = legacy.split(f"// SLOT {name} {hex(address)} {capacity}\n", 1)[1].split("// SLOT", 1)[0]
        return re.sub(r"(?m)//.*$", "", text).strip()

    version = slot("hello_version", 0x102cc8, 160).replace(
        "cmpne r2, #0x02000000", "cmpne r2, #0x02000000\ncmpne r2, #0x03000000")
    patch.arm("unlimited_hello_version", 0x102cc8, 160, version)
    valid = slot("session_valid", 0x102e98, 176).replace("cmpne r1, #2", "cmpne r1, #2\ncmpne r1, #3")
    patch.arm("unlimited_fresh_heartbeat", 0x102e98, 176, valid)
    heartbeat = slot("heartbeat", 0x102f48, 260).replace(
        "cmn r2, #1\nbeq heartbeat_saved_fail",
        "ldr ip, [r4, #0x38]\ncmp ip, #3\nbeq unlimited_heartbeat_next\n"
        "cmn r2, #1\nbeq heartbeat_saved_fail\nunlimited_heartbeat_next:")
    patch.arm("unlimited_heartbeat_counter", 0x102f48, 260, heartbeat)
    deadline = slot("connection_deadline_no_license", 0x101bfc, 156).replace(
        "ldrd r0, r1, [sp, #0xd8]", "ldr r1, [r5, #0x38]\ncmp r1, #3\nbeq unlimited_deadline_store\n"
        "ldrd r0, r1, [sp, #0xd8]")
    deadline = deadline.replace("movw ip, #0x61a0\nmovt ip, #0x1c", "movw ip, #0xc760\nmovt ip, #0x6e")
    deadline = deadline.replace("strd r2, r3, [sp, #0x28]", "unlimited_deadline_store:\nstrd r2, r3, [sp, #0x28]")
    patch.arm("heartbeat_deadline_without_total_cap", 0x101bfc, 156, deadline)
    patch.arm("unlimited_header_entry", 0x101970, 4, f"bl #{labels['unlimited_header_gate']}")
    patch.arm("heartbeat_relative_frame_idle", 0x1029d4, 24, f"b #{labels['unlimited_frame_idle']}")
    binary, report = patch.finish()
    report.update(source_sha256=digest(source.encode()), hmi_source_sha256=digest(HMI_SOURCE.read_bytes()),
                  legacy_source_sha256=digest(legacy.encode()), labels=labels, protocol_version=3,
                  product_total_duration_seconds=None, product_frame_count_limit=None,
                  frame_payload_bytes=0x200000, heartbeat_ms=15000, independent_background_expiry_ms=5000,
                  wire_sequence="uint32 modular +1", wire_pts="uint32 ms modular delta <=2000; native 64-bit accumulator",
                  physical_composition_tested=False)
    return binary, report
