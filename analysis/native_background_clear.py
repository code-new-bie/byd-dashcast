"""Preserve native clear RGBA; only the saved destination-in aperture opens video.

The original two LOADs/PHDR boot fix and all runtime hooks remain byte-exact.
One tail branch in the owned clear entry restores the original five-argument ABI.
No shader, palette, original resource, window or wire contract is changed.
"""
import io

from capstone import Cs,CS_ARCH_ARM,CS_MODE_THUMB
from elftools.elf.elffile import ELFFile
from keystone import Ks,KS_ARCH_ARM,KS_MODE_THUMB

from background_poc_native import CAVE
from navigation_layout import digest
from product_boot_layout import (build_boot_hmi,build_runtime_kzb,build_plain_scene,
                                build_runtime_receiver,CAPABILITY)
from product_runtime_layout import require_qnx_header_anchor,RECURSIVE_2D
from ref2_noauth_native import Patch

CLEAR_ENTRY=CAVE+4
ORIGINAL_CLEAR=0x0831308c


def build_background_hmi(original):
    base,prior=build_boot_hmi(original)
    assert digest(base)=='b657b7edf926b67748f51244e2eab02632ae53d18e85c703c484e4e081f4fa2c'
    patch=Patch(base,digest(base))
    offset=patch.offset(CLEAR_ENTRY,4)
    before=base[offset:offset+4]
    decoded=list(Cs(CS_ARCH_ARM,CS_MODE_THUMB).disasm(before,CLEAR_ENTRY))
    assert len(decoded)==1 and decoded[0].mnemonic=='b.w'
    encoded,_=Ks(KS_ARCH_ARM,KS_MODE_THUMB).asm('b.w #'+hex(ORIGINAL_CLEAR),addr=CLEAR_ENTRY)
    encoded=bytes(encoded)
    decoded=list(Cs(CS_ARCH_ARM,CS_MODE_THUMB).disasm(encoded,CLEAR_ENTRY))
    assert len(encoded)==4 and len(decoded)==1 and decoded[0].op_str=='#0x831308c'
    patch.put('native_clear_rgba_passthrough',CLEAR_ENTRY,encoded,expected=before)
    output,changes=patch.finish()
    assert output[:offset]==base[:offset] and output[offset+4:]==base[offset+4:]
    original_elf,new_elf=ELFFile(io.BytesIO(base)),ELFFile(io.BytesIO(output))
    assert dict(original_elf.header)==dict(new_elf.header)
    assert [dict(s.header) for s in original_elf.iter_segments()]==[dict(s.header) for s in new_elf.iter_segments()]
    anchor=require_qnx_header_anchor(output)
    return output,dict(**{**prior,'input_sha256':digest(original),'output_sha256':digest(output),'prior':prior,
                        'patch':changes},
        clear_entry=CLEAR_ENTRY,original_clear=ORIGINAL_CLEAR,
        changed_range_bytes=4,native_clear_rgba_and_stack_alpha_preserved=True,
        curve_mask_is_sole_video_aperture=True,program_headers_and_loads_identical=True,
        actual_header_anchor=anchor,full_qnx_or_vehicle_run=False)


# Existing paired behavior harnesses bind the builder explicitly through this API.
build_runtime_hmi=build_background_hmi
