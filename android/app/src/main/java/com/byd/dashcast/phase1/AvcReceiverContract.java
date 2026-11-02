package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Preflight the reference receiver's actual Baseline/progressive/SPS0/PPS0 contract.
 * This validates headers, not decoded pixels. Runs before opening instrument media.
 * @author Codex
 * @date 2026/10/12
 */
final class AvcReceiverContract {
    private boolean sps;
    private boolean pps;
    private boolean picture;

    void accept(byte[] unit, Consumer<String> log) throws IOException {
        if (unit == null || unit.length == 0 || unit.length > NoAuthVideoProtocol.MAX_FRAME_BYTES) {
            throw new IOException("AVC_CONTRACT_BAD_LENGTH");
        }
        int offset = 0;
        boolean slice = false;
        while (offset < unit.length) {
            int prefix = prefix(unit, offset);
            int start = offset + prefix;
            if (prefix == 0 || start >= unit.length) {
                throw new IOException("AVC_CONTRACT_BAD_ANNEX_B");
            }
            int end = start + 1;
            while (end < unit.length && prefix(unit, end) == 0) {
                end++;
            }
            int type = unit[start] & 31;
            if ((unit[start] & 128) != 0) {
                throw new IOException("AVC_CONTRACT_FORBIDDEN_BIT");
            }
            Bits bits = new Bits(Arrays.copyOfRange(unit, start + 1, end));
            if (type == 7) {
                if (slice) {
                    throw new IOException("AVC_CONTRACT_PARAMETER_AFTER_SLICE");
                }
                int profile = bits.read(8);
                int constraints = bits.read(8);
                int level = bits.read(8);
                log.accept("AVC_SPS profile=" + profile + " constraints=" + constraints + " level=" + level);
                if (profile != 66 || (constraints & 3) != 0 || level < 1 || level > 42 || bits.ue() != 0) {
                    throw new IOException("AVC_CONTRACT_EXPECTS_BASELINE_LEVEL_LE42_SPS0; profile=" + profile);
                }
                if (bits.ue() > 12) {
                    throw new IOException("AVC_CONTRACT_FRAME_NUM");
                }
                int order = bits.ue();
                if (order != 2 && (order != 0 || bits.ue() > 12)) {
                    throw new IOException("AVC_CONTRACT_PICTURE_ORDER");
                }
                if (bits.ue() > 16) {
                    throw new IOException("AVC_CONTRACT_REFERENCE_LIMIT");
                }
                bits.read(1);
                int macroWidth = bits.ue();
                int macroHeight = bits.ue();
                int maxMacroWidth = (NoAuthVideoProtocol.WIDTH + 15) / 16 - 1;
                int maxMacroHeight = (NoAuthVideoProtocol.HEIGHT + 15) / 16 - 1;
                if (macroWidth > maxMacroWidth || macroHeight > maxMacroHeight || bits.read(1) != 1) {
                    throw new IOException("AVC_CONTRACT_PROGRESSIVE_SIZE");
                }
                bits.read(1);
                int left = 0;
                int right = 0;
                int top = 0;
                int bottom = 0;
                if (bits.read(1) == 1) {
                    left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue();
                }
                int width = (macroWidth + 1) * 16 - 2 * (left + right);
                int height = (macroHeight + 1) * 16 - 2 * (top + bottom);
                if (width != NoAuthVideoProtocol.WIDTH || height != NoAuthVideoProtocol.HEIGHT
                        || left + right >= (macroWidth + 1) * 8
                        || top + bottom >= (macroHeight + 1) * 8) {
                    throw new IOException("AVC_CONTRACT_SIZE_MISMATCH " + width + "x" + height);
                }
                log.accept("AVC_CONTRACT_SPS baseline progressive " + width + "x" + height + " id=0");
                sps = true;
            } else if (type == 8) {
                if (slice || bits.ue() != 0 || bits.ue() != 0) {
                    throw new IOException("AVC_CONTRACT_EXPECTS_PPS0_SPS0");
                }
                pps = true;
            } else if (type == 1 || type == 5) {
                int first = bits.ue();
                int sliceType = bits.ue();
                if (first >= 600 || sliceType > 9 || sliceType % 5 != 0 && sliceType % 5 != 2
                        || bits.ue() != 0 || !sps || !pps
                        || !picture && (!slice && first != 0 || type != 5)) {
                    throw new IOException("AVC_CONTRACT_EXPECTS_P_I_PPS0_AND_INITIAL_IDR");
                }
                slice = true;
            } else if (type != 6 && type != 9 && type != 12) {
                throw new IOException("AVC_CONTRACT_UNSUPPORTED_NAL type=" + type);
            }
            offset = end;
        }
        if (!slice) {
            throw new IOException("AVC_CONTRACT_NO_PICTURE");
        }
        picture = true;
    }

    private static int prefix(byte[] data, int offset) {
        if (offset + 3 > data.length || data[offset] != 0 || data[offset + 1] != 0) {
            return 0;
        }
        if (data[offset + 2] == 1) {
            return 3;
        }
        return offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1 ? 4 : 0;
    }

    private static final class Bits {
        private final byte[] bytes;
        private int offset;
        private int zeros;
        private int current;
        private int left;

        Bits(byte[] bytes) {
            this.bytes = bytes;
        }

        int ue() throws IOException {
            int leading = 0;
            while (read(1) == 0) {
                if (++leading > 15) {
                    throw new IOException("AVC_CONTRACT_GOLOMB_LIMIT");
                }
            }
            return (1 << leading) - 1 + read(leading);
        }

        int read(int count) throws IOException {
            int result = 0;
            for (int bit = 0; bit < count; bit++) {
                if (left == 0) {
                    if (zeros >= 2 && offset < bytes.length && bytes[offset] == 3) {
                        offset++;
                        zeros = 0;
                    }
                    if (offset >= bytes.length) {
                        throw new IOException("AVC_CONTRACT_TRUNCATED_RBSP");
                    }
                    current = bytes[offset++] & 255;
                    zeros = current == 0 ? zeros + 1 : 0;
                    left = 8;
                }
                result = result << 1 | current >>> --left & 1;
            }
            return result;
        }
    }
}
