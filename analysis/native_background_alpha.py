"""Match the owned HMI Screen window to its premultiplied background aperture.

The saved background/mask already produces premultiplied pixels. The reference
OS has no alpha-mode override, and the original HMI does not set property 1.
Keep the original creation result on an optional property failure so an edge
configuration error cannot introduce another HMI startup failure.
"""
import io

from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from native_background_clear import (build_background_hmi, build_runtime_kzb,
    build_plain_scene, build_runtime_receiver)
from product_runtime_layout import CAPABILITY, RECURSIVE_2D, RENDER_MESH
from navigation_layout import digest
from ref2_noauth_native import Patch

CREATE_SITE = 0x08345402
CREATE_API = 0x080d6ff4
SET_PROPERTY_API = 0x080d6bd0
STUB = 0x08047800
CAPACITY = 128

SOURCE = '''
push {r4, r5, r6, lr}
mov r4, r0
blx #0x080d6ff4
mov r5, r0
cmp r0, #0
bne alpha_done
sub sp, sp, #8
movs r3, #1
str r3, [sp]
ldr r0, [r4]
movs r1, #1
mov r2, sp
blx #0x080d6bd0
add sp, sp, #8
alpha_done:
mov r0, r5
pop {r4, r5, r6, pc}
'''


def build_alpha_hmi(original):
    base, prior = build_background_hmi(original)
    patch = Patch(base, digest(base))
    expected = base[patch.offset(CREATE_SITE, 4):patch.offset(CREATE_SITE, 4) + 4]
    decoder = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    instructions = list(decoder.disasm(expected, CREATE_SITE))
    assert [(i.mnemonic, i.op_str) for i in instructions] == [('blx', '#0x80d6ff4')]
    free = base[patch.offset(STUB, CAPACITY):patch.offset(STUB, CAPACITY) + CAPACITY]
    if free != bytes(CAPACITY):
        raise ValueError('Owned prefix space is no longer unused')
    code, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(SOURCE, addr=STUB)
    code = bytes(code)
    if len(code) > CAPACITY:
        raise ValueError('Window alpha stub exceeds owned prefix slot')
    calls = [(i.mnemonic, i.op_str) for i in decoder.disasm(code, STUB) if i.mnemonic in ('bl', 'blx')]
    assert calls == [('blx', '#0x80d6ff4'), ('blx', '#0x80d6bd0')]
    branch, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm('bl #' + hex(STUB), addr=CREATE_SITE)
    assert len(branch) == 4
    patch.put('owned_hmi_create_alpha_mode', CREATE_SITE, bytes(branch), expected=expected)
    patch.put('owned_prefix_alpha_stub', STUB, code, expected=bytes(len(code)))
    output, ranges = patch.finish()
    a, b = ELFFile(io.BytesIO(base)), ELFFile(io.BytesIO(output))
    assert len(base) == len(output) and a.header == b.header
    assert [dict(s.header) for s in a.iter_segments()] == [dict(s.header) for s in b.iter_segments()]
    return output, dict(prior, prior_sha256=digest(base), output_sha256=digest(output),
        prior=prior, window_alpha_patches=ranges, stub_address=STUB, stub_bytes=len(code),
        create_site=CREATE_SITE, screen_property_alpha_mode=1, requested_premultiplied_alpha=1,
        original_create_result_preserved=True, original_create_failure_skips_property=True,
        property_failure_does_not_fail_window_creation=True, owned_hmi_windows_only=True,
        original_elf_headers_loads_addresses_preserved=True, curve_and_background_assets_unchanged=True,
        whole_qnx_or_vehicle_tested=False)


build_runtime_hmi = build_alpha_hmi
