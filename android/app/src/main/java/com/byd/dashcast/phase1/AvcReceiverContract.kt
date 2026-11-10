package com.byd.dashcast.phase1

import java.io.IOException
import java.util.function.Consumer

/**
 * Preflight the instrument receiver's Baseline/progressive/SPS0/PPS0 contract.
 * This validates headers, not decoded pixels. Runs before opening instrument media.
 * @author Codex
 * @date 2026/10/12
 */
internal class AvcReceiverContract {
    private var sps = false
    private var pps = false
    private var picture = false

    @Throws(IOException::class)
    fun accept(unit: ByteArray?, log: Consumer<String>) {
        if (unit == null || unit.isEmpty() || unit.size > NoAuthVideoProtocol.MAX_FRAME_BYTES) {
            throw IOException("AVC_CONTRACT_BAD_LENGTH")
        }
        var offset = 0
        var slice = false
        while (offset < unit.size) {
            val prefix = prefix(unit, offset)
            val start = offset + prefix
            if (prefix == 0 || start >= unit.size) {
                throw IOException("AVC_CONTRACT_BAD_ANNEX_B")
            }
            var end = start + 1
            while (end < unit.size && prefix(unit, end) == 0) {
                end++
            }
            val type = unit[start].toInt() and 31
            if (unit[start].toInt() and 128 != 0) {
                throw IOException("AVC_CONTRACT_FORBIDDEN_BIT")
            }
            val bits = Bits(unit.copyOfRange(start + 1, end))
            if (type == 7) {
                if (slice) {
                    throw IOException("AVC_CONTRACT_PARAMETER_AFTER_SLICE")
                }
                val profile = bits.read(8)
                val constraints = bits.read(8)
                val level = bits.read(8)
                log.accept("AVC_SPS profile=$profile constraints=$constraints level=$level")
                if (profile != 66 || constraints and 3 != 0 || level < 1 || level > 42 || bits.ue() != 0) {
                    throw IOException("AVC_CONTRACT_EXPECTS_BASELINE_LEVEL_LE42_SPS0; profile=$profile")
                }
                if (bits.ue() > 12) {
                    throw IOException("AVC_CONTRACT_FRAME_NUM")
                }
                val order = bits.ue()
                if (order != 2 && (order != 0 || bits.ue() > 12)) {
                    throw IOException("AVC_CONTRACT_PICTURE_ORDER")
                }
                if (bits.ue() > 16) {
                    throw IOException("AVC_CONTRACT_REFERENCE_LIMIT")
                }
                bits.read(1)
                val macroWidth = bits.ue()
                val macroHeight = bits.ue()
                val maxMacroWidth = (NoAuthVideoProtocol.WIDTH + 15) / 16 - 1
                val maxMacroHeight = (NoAuthVideoProtocol.HEIGHT + 15) / 16 - 1
                if (macroWidth > maxMacroWidth || macroHeight > maxMacroHeight || bits.read(1) != 1) {
                    throw IOException("AVC_CONTRACT_PROGRESSIVE_SIZE")
                }
                bits.read(1)
                var left = 0
                var right = 0
                var top = 0
                var bottom = 0
                if (bits.read(1) == 1) {
                    left = bits.ue()
                    right = bits.ue()
                    top = bits.ue()
                    bottom = bits.ue()
                }
                val width = (macroWidth + 1) * 16 - 2 * (left + right)
                val height = (macroHeight + 1) * 16 - 2 * (top + bottom)
                if (width != NoAuthVideoProtocol.WIDTH || height != NoAuthVideoProtocol.HEIGHT ||
                    left + right >= (macroWidth + 1) * 8 || top + bottom >= (macroHeight + 1) * 8) {
                    throw IOException("AVC_CONTRACT_SIZE_MISMATCH " + width + "x" + height)
                }
                log.accept("AVC_CONTRACT_SPS baseline progressive " + width + "x" + height + " id=0")
                sps = true
            } else if (type == 8) {
                if (slice || bits.ue() != 0 || bits.ue() != 0) {
                    throw IOException("AVC_CONTRACT_EXPECTS_PPS0_SPS0")
                }
                pps = true
            } else if (type == 1 || type == 5) {
                val first = bits.ue()
                val sliceType = bits.ue()
                // Order matters: ue() reads bits only when the earlier checks pass.
                if (first >= 600 || sliceType > 9 || (sliceType % 5 != 0 && sliceType % 5 != 2) ||
                    bits.ue() != 0 || !sps || !pps ||
                    (!picture && ((!slice && first != 0) || type != 5))) {
                    throw IOException("AVC_CONTRACT_EXPECTS_P_I_PPS0_AND_INITIAL_IDR")
                }
                slice = true
            } else if (type != 6 && type != 9 && type != 12) {
                throw IOException("AVC_CONTRACT_UNSUPPORTED_NAL type=$type")
            }
            offset = end
        }
        if (!slice) {
            throw IOException("AVC_CONTRACT_NO_PICTURE")
        }
        picture = true
    }

    private class Bits(private val bytes: ByteArray) {
        private var offset = 0
        private var zeros = 0
        private var current = 0
        private var left = 0

        @Throws(IOException::class)
        fun ue(): Int {
            var leading = 0
            while (read(1) == 0) {
                if (++leading > 15) {
                    throw IOException("AVC_CONTRACT_GOLOMB_LIMIT")
                }
            }
            return (1 shl leading) - 1 + read(leading)
        }

        @Throws(IOException::class)
        fun read(count: Int): Int {
            var result = 0
            repeat(count) {
                if (left == 0) {
                    if (zeros >= 2 && offset < bytes.size && bytes[offset].toInt() == 3) {
                        offset++
                        zeros = 0
                    }
                    if (offset >= bytes.size) {
                        throw IOException("AVC_CONTRACT_TRUNCATED_RBSP")
                    }
                    current = bytes[offset++].toInt() and 255
                    zeros = if (current == 0) zeros + 1 else 0
                    left = 8
                }
                left--
                result = (result shl 1) or ((current ushr left) and 1)
            }
            return result
        }
    }

    private companion object {
        fun prefix(data: ByteArray, offset: Int): Int {
            if (offset + 3 > data.size || data[offset].toInt() != 0 || data[offset + 1].toInt() != 0) {
                return 0
            }
            if (data[offset + 2].toInt() == 1) {
                return 3
            }
            return if (offset + 4 <= data.size && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1) 4 else 0
        }
    }
}
