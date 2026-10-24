"""Exact RWV4 receiver: read-only guarded QUERY, no sticky-guard clearing.

BGP QUERY op0/nonce0/hold1 is an explicit capability probe. Legacy receivers
reject it. Legacy op0/hold0 and all START/RESTORE/RENEW semantics stay unchanged.
Only two entry instructions and verified unused ARM NOPs are touched.
"""
import struct
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM
from ref2_noauth_native import Patch, NOP
from navigation_layout import digest

BASE_SHA = "f756f58a294dfc819030de42c66bbc8cd211ea67b7e58f1a68a5718592c49f28"
HELPER = 0x109400


def build_cleanup_guard_receiver(original):
    patch = Patch(original, BASE_SHA)
    asm = Ks(KS_ARCH_ARM, KS_MODE_ARM)
    encoded = lambda code, address: bytes(asm.asm(code, addr=address)[0])
    def raw(address, size):
        offset = patch.offset(address, size)
        return original[offset:offset+size]
    if raw(0x108930, 12) != encoded("cmp r1, r3; beq #0x10894c; cmp r0, #1", 0x108930):
        raise ValueError("Exact center hold selector required")
    inst = list(Cs(CS_ARCH_ARM, CS_MODE_ARM).disasm(raw(0x10894c, 4), 0x10894c))
    if len(inst) != 1 or inst[0].mnemonic != "ldr" or inst[0].op_str != "r0, [pc, #0x548]":
        raise ValueError("Exact library pointer load required")
    libc_pointer = struct.unpack("<I", raw(0x10894c+8+0x548, 4))[0]
    if raw(libc_pointer, 10) != b"libc.so.3\0":
        raise ValueError("Original library identity drift")
    control = raw(0x108898, 3232)
    reply_pattern = encoded("mov r0, #2; mov r1, #0; mov r2, #0; mov r3, #0; str r1, [sp]", 0)
    if control.count(reply_pattern) != 1:
        raise ValueError("Unique original reply path required")
    reply = 0x108898 + control.index(reply_pattern)
    if raw(HELPER, 192) != NOP*48:
        raise ValueError("Helper must remain proven unused NOPs in existing control slot")
    hold = """
        beq #0x10894c
        cmp r0, #0
        cmpeq r1, #1
        beq #0x10894c
        b #0x108938
    """
    guard = f"""
        ldr r0, [sp, #12]
        cmp r0, #0
        bne original_library
        ldr r0, [r5, #12]
        rev r0, r0
        cmp r0, #1
        bne original_library
        movw ip, #0xafc8
        movt ip, #0x14
        ldr ip, [ip]
        cmp ip, #0
        movne r11, #8
        bne #{reply}
    original_library:
        movw r0, #{libc_pointer & 65535}
        movt r0, #{libc_pointer >> 16}
        bx lr
    """
    hold_bytes, guard_bytes = encoded(hold, HELPER), encoded(guard, HELPER+32)
    if len(hold_bytes) > 32 or len(guard_bytes) > 160:
        raise ValueError("Recovery helper overflow")
    patch.arm("guarded_query_hold_marker", 0x108934, 4, f"b #{HELPER}")
    patch.arm("guarded_query_sticky_check_entry", 0x10894c, 4, f"bl #{HELPER+32}")
    code = hold_bytes + NOP*((32-len(hold_bytes))//4) + guard_bytes
    patch.put("guarded_query_helpers_in_original_unused_slot", HELPER,
              code + NOP*((192-len(code))//4))
    output, report = patch.finish()
    report.update(guarded_query=dict(operation=0, nonce=0, hold_marker=1),
                  sticky_flag_address=0x14afc8, sticky_flag_never_written=True,
                  original_reply_address=reply, original_media_role_hmi_code_unchanged=True,
                  physical_vehicle_tested=False, output_sha256=digest(output))
    return output, report
