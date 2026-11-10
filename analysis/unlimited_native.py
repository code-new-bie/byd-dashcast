"""Exact 1.0 -> no-total-duration DCV1/3, retaining operation/heartbeat/lease bounds."""
import re
import struct
from background_poc_native import ROOT, apply_hmi_overlay, digest
from elf_patch import Patch, NOP
from keystone import Ks, KS_ARCH_ARM, KS_MODE_ARM, KS_MODE_LITTLE_ENDIAN
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
HMI_SOURCE = ROOT / "instrument/native-hmi/background-unlimited.thumb"
CAPABILITY = 0x52574e32


def build_unlimited_hmi(original):
    binary, report = apply_hmi_overlay(original, source_path=HMI_SOURCE)
    report.update(unlimited_product_session=True, capability=CAPABILITY,
                  renewable_owned_lease=True, independent_expiry_ms=5000, physical_composition_tested=False)
    return binary, report
