package com.byd.dashcast.phase1.ncm

import java.io.IOException

/**
 * Validates the NCM function association and advertised capabilities before any USB writes.
 * @author Codex
 * @date 2026/10/11
 */
class NcmDescriptor private constructor(
    @JvmField val controlInterface: Int,
    @JvmField val dataInterface: Int,
    @JvmField val capabilities: Int,
    @JvmField val version: Int,
    @JvmField val maxSegmentBytes: Int,
) {
    companion object {
        private const val MAX_DESCRIPTOR_BYTES = 4096

        /** Parses exactly one dedicated NCM configuration, including both data alternates. */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(raw: ByteArray?): NcmDescriptor {
            if (raw == null || raw.size < 9 || raw.size > MAX_DESCRIPTOR_BYTES) {
                throw IOException("NCM descriptors missing or oversized")
            }
            var control = -1
            var data = -1
            var capabilities = -1
            var version = -1
            var maxSegment = -1
            var current = -1
            var alternate = -1
            var interfaceClass = -1
            var configEnd = raw.size
            var configuration = false
            var inactive = false
            var active = false
            var bulkIn = false
            var bulkOut = false
            var offset = 0
            while (offset < configEnd) {
                if (offset + 2 > configEnd) {
                    throw IOException("Truncated NCM descriptor header")
                }
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > configEnd) {
                    throw IOException("Truncated NCM descriptor")
                }
                if (type == 2) {
                    if (configuration || length < 9 || u16(raw, offset + 2) < length ||
                        offset + u16(raw, offset + 2) != raw.size) {
                        throw IOException("NCM requires one complete configuration")
                    }
                    configuration = true
                    configEnd = offset + u16(raw, offset + 2)
                } else if (configuration && type == 4 && length >= 9) {
                    current = raw[offset + 2].toInt() and 0xff
                    alternate = raw[offset + 3].toInt() and 0xff
                    interfaceClass = raw[offset + 5].toInt() and 0xff
                    val subclass = raw[offset + 6].toInt() and 0xff
                    if (interfaceClass == 8) {
                        throw IOException("NCM storage composite is outside the validation scope")
                    }
                    if (interfaceClass == 2 && subclass == 13) {
                        if (control >= 0 || alternate != 0) {
                            throw IOException("Ambiguous NCM control interface")
                        }
                        control = current
                    } else if (interfaceClass == 10 && current == data) {
                        val endpoints = raw[offset + 4].toInt() and 0xff
                        if (raw[offset + 7].toInt() and 0xff != 1) {
                            throw IOException("Unsupported NCM data protocol")
                        }
                        inactive = inactive or (alternate == 0 && endpoints == 0)
                        active = active or (alternate == 1 && endpoints == 2)
                    }
                } else if (configuration && type == 0x24 && current == control && alternate == 0) {
                    if (length < 3) {
                        throw IOException("Truncated NCM functional descriptor")
                    }
                    val subtype = raw[offset + 2].toInt() and 0xff
                    if (subtype == 6) {
                        if (length != 5 || data >= 0 || raw[offset + 3].toInt() and 0xff != control) {
                            throw IOException("Invalid NCM union descriptor")
                        }
                        data = raw[offset + 4].toInt() and 0xff
                        if (data == control) {
                            throw IOException("NCM control/data interface overlap")
                        }
                    } else if (subtype == 0x1a) {
                        if (length != 6 || capabilities >= 0) {
                            throw IOException("Invalid NCM capability descriptor")
                        }
                        version = u16(raw, offset + 3)
                        capabilities = raw[offset + 5].toInt() and 0xff
                    } else if (subtype == 0x0f) {
                        if (length < 13 || maxSegment >= 0) {
                            throw IOException("Invalid NCM Ethernet descriptor")
                        }
                        maxSegment = u16(raw, offset + 8)
                    }
                } else if (configuration && type == 5 && current == data && alternate == 1 && interfaceClass == 10) {
                    if (length < 7 || raw[offset + 3].toInt() and 3 != 2 || u16(raw, offset + 4) == 0) {
                        throw IOException("Invalid NCM bulk endpoint")
                    }
                    val input = raw[offset + 2].toInt() and 0x80 != 0
                    if (if (input) bulkIn else bulkOut) {
                        throw IOException("Duplicate NCM bulk direction")
                    }
                    bulkIn = bulkIn or input
                    bulkOut = bulkOut or !input
                }
                offset += length
            }
            if (!configuration || control < 0 || data < 0 || capabilities < 0 || version != 0x0100 ||
                maxSegment < 1514 || !inactive || !active || !bulkIn || !bulkOut) {
                throw IOException("Incomplete or unsupported NCM 1.0 function")
            }
            return NcmDescriptor(control, data, capabilities, version, maxSegment)
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }
}
