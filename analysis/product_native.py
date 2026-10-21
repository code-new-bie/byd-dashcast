"""Product lease and bounded 2h video adaptation; exact inputs, no physical safety claim."""
import re
from background_poc_native import ROOT, apply_hmi_overlay, digest
from ref2_noauth_native import Patch, NOP
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM, KS_MODE_LITTLE_ENDIAN

RECEIVER_SHA = "20273e74ef8103762aad9f6f5867e3ba263cb78b0075703b22cee516fda32e43"
HMI_SOURCE = ROOT / "phase1/qnx/native-hmi/background-product.thumb"
RECEIVER_SOURCE = ROOT / "phase1/qnx/ref2-noauth/receiver-product.arm"
CAPABILITY = 0x52574e31
MAX_SECONDS = 7200


def build_product_hmi(original):
    binary, report = apply_hmi_overlay(original, source_path=HMI_SOURCE)
    report.update(renewable_owned_lease=True, max_lease_ms=5000, capability=CAPABILITY,
                  physical_composition_tested=False)
    return binary, report


def apply_product_receiver(base):
    patch = Patch(base, RECEIVER_SHA)
    source = RECEIVER_SOURCE.read_text("utf-8")
    blocks = re.split(r"(?m)^// SLOT (\w+) (0x[0-9a-f]+) (\d+)\s*$", source)
    expected = {"background_dispatch": (0x102e1c, 124), "background_control": (0x108898, 3232)}
    seen = set()
    for i in range(1, len(blocks), 4):
        name, address, capacity, code = blocks[i:i + 4]
        address, capacity = int(address, 16), int(capacity)
        if name in seen or expected.get(name) != (address, capacity):
            raise ValueError("Unexpected product receiver slot")
        # This existing control slot contains both code and explicit string/literal data.
        # Use the same bounded mixed-slot assembler as the frozen 0.9.1 builder.
        raw, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM | KS_MODE_LITTLE_ENDIAN).asm(
            re.sub(r"(?m)//.*$", "", code).strip(), addr=address)
        raw = bytes(raw)
        if len(raw) > capacity or len(raw) % 4:
            raise ValueError("Product control slot overflow")
        patch.put(name, address, raw + NOP * ((capacity - len(raw)) // 4))
        patch.ranges[-1]["source_bytes"] = len(raw)
        seen.add(name)
    if seen != set(expected):
        raise ValueError("Product receiver slot missing")
    # Preserve framing whitelist; change only the finite frame-count bound.
    source_hd = (ROOT / "phase1/qnx/ref2-noauth/receiver-dcv1-hd-window.arm").read_text("utf-8")
    header = source_hd.split("// SLOT header_whitelist 0x103fc8 484\n", 1)[1].split("// SLOT", 1)[0]
    if header.count("movw r1, #65000") != 1:
        raise ValueError("Frame-count source precondition")
    header = header.replace("movw r1, #65000", "movw r1, #0xa980\nmovt r1, #3")
    header = header.replace("cmp r0, #8\nbeq header_heartbeat\nb header_fail",
                            "cmp r0, #8\nbeq header_heartbeat\ncmp r0, #10\nbne header_fail\ncmp r1, #16\nb header_result")
    patch.arm("product_frame_bound", 0x103fc8, 484, re.sub(r"(?m)//.*$", "", header).strip())
    deadline = source_hd.split("// SLOT connection_deadline_no_license 0x101bfc 156\n", 1)[1].split("// SLOT", 1)[0]
    bound = (MAX_SECONDS + 60) * 1000
    deadline = deadline.replace("movw ip, #0x61a0\nmovt ip, #0x1c",
                                f"movw ip, #{bound & 65535}\nmovt ip, #{bound >> 16}")
    patch.arm("product_session_bound", 0x101bfc, 156, re.sub(r"(?m)//.*$", "", deadline).strip())
    idle = ("ldr ip, [r5, #0x38]\ncmp ip, #2\nmovw r3, #1500\n"
            f"movweq r3, #{bound & 65535}\nmovteq r3, #{bound >> 16}\nbx lr")
    patch.arm("product_static_frame_bound", 0x1029d4, 24, idle)
    binary, report = patch.finish()
    report.update(source_sha256=digest(source.encode()), hmi_source_sha256=digest(HMI_SOURCE.read_bytes()),
                  hd_source_sha256=digest(source_hd.encode()), max_seconds=MAX_SECONDS,
                  max_frames=240000, heartbeat_ms=15000, lease_ms=5000,
                  capability=CAPABILITY, physical_composition_tested=False)
    return binary, report
