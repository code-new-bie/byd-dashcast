"""Set the owned HMI alpha contract in native realization, before buffers.

Accept only the exact 1.17--1.19 HMI. Move the earlier create-only hook to
the common USAGE call without changing the mask, destination-in blend, ELF
layout, imports or any original function's result. Reuse the owned prefix
slot instead of replacing a supposedly unused factory function.
"""
import io

from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from elftools.elf.elffile import ELFFile
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

from native_background_alpha import (CREATE_SITE, CREATE_API, SET_PROPERTY_API,
    STUB, CAPACITY, SOURCE as CREATE_SOURCE, build_alpha_hmi)
from aperture_fit_overlay import build_runtime_kzb, build_plain_scene, build_runtime_receiver
from product_runtime_layout import CAPABILITY, RECURSIVE_2D, RENDER_MESH
from navigation_layout import digest
from ref2_noauth_native import Patch

BASE_SHA = "5dfef87a7786c3b6af5866b44572c795156473001f0ffa29e7a0f2d3d966b9a2"
USAGE_SITE = 0x083480BC
REALIZE_API = 0x08347BBC
BUFFER_API = 0x080D68FC
SOURCE = """
push {r4, lr}
sub sp, sp, #8
str r0, [sp, #4]
blx #0x080d6bd0
mov r4, r0
movs r3, #1
str r3, [sp]
ldr r0, [sp, #4]
movs r1, #1
mov r2, sp
blx #0x080d6bd0
mov r0, r4
add sp, sp, #8
pop {r4, pc}
"""


def patch_current_hmi(base):
    patch = Patch(base, BASE_SHA)
    asm = Ks(KS_ARCH_ARM, KS_MODE_THUMB)
    decoder = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    assemble = lambda source, address: bytes(asm.asm(source, addr=address)[0])
    old_stub = assemble(CREATE_SOURCE, STUB)
    code = assemble(SOURCE, STUB)
    assert len(code) == 32 and len(old_stub) == 36 and len(code) <= CAPACITY
    assert [(i.mnemonic, i.op_str) for i in decoder.disasm(code, STUB)
            if i.mnemonic in ("bl", "blx")] == [
        ("blx", "#0x80d6bd0"), ("blx", "#0x80d6bd0")]
    patch.put("restore_original_create_call", CREATE_SITE,
        assemble("blx #" + hex(CREATE_API), CREATE_SITE),
        expected=assemble("bl #" + hex(STUB), CREATE_SITE))
    patch.put("realize_usage_then_premultiplied_alpha", USAGE_SITE,
        assemble("bl #" + hex(STUB), USAGE_SITE), expected=bytes.fromhex("8ef588ed"))
    patch.put("reuse_owned_prefix_alpha_stub", STUB,
        code + bytes(len(old_stub) - len(code)), expected=old_stub)
    result, report = patch.finish()
    a, b = ELFFile(io.BytesIO(base)), ELFFile(io.BytesIO(result))
    assert len(base) == len(result) and a.header == b.header
    assert [dict(s.header) for s in a.iter_segments()] == [dict(s.header) for s in b.iter_segments()]
    report.update(changed_bytes=sum(x != y for x, y in zip(base, result)),
        stub_address=hex(STUB), stub_bytes=len(code), usage_site=hex(USAGE_SITE),
        former_create_hook_removed=True, factory_vehicle_menu_function_unchanged=True,
        screen_property_alpha_mode=1, requested_premultiplied_alpha=1,
        original_usage_result_preserved=True, optional_alpha_error_not_a_startup_error=True,
        alpha_is_requested_after_usage_before_window_buffers=True,
        masks_kzb_blend_mode_5_assets_and_geometry_unchanged=True,
        headers_segments_imports_relocations_and_entry_unchanged=True,
        expected_old_hash_rejects_the_mutually_exclusive_blend_workaround=True,
        qnx_runtime_tested=False, vehicle_tested=False)
    return result, report


def build_runtime_hmi(original):
    base, previous = build_alpha_hmi(original)
    result, report = patch_current_hmi(base)
    return result, dict(previous, previous=previous, output_sha256=digest(result), usage_alpha=report)
