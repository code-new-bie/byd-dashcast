package com.byd.dashcast.phase1.ncm;

import java.io.IOException;

/**
 * Validates the NCM function association and advertised capabilities before any USB writes.
 * @author Codex
 * @date 2026/10/11
 */
public final class NcmDescriptor {
    private static final int MAX_DESCRIPTOR_BYTES = 4096;
    public final int controlInterface;
    public final int dataInterface;
    public final int capabilities;
    public final int version;
    public final int maxSegmentBytes;

    private NcmDescriptor(int controlInterface, int dataInterface, int capabilities,
            int version, int maxSegmentBytes) {
        this.controlInterface = controlInterface;
        this.dataInterface = dataInterface;
        this.capabilities = capabilities;
        this.version = version;
        this.maxSegmentBytes = maxSegmentBytes;
    }

    /** Parses exactly one dedicated NCM configuration, including both data alternates. */
    public static NcmDescriptor parse(byte[] raw) throws IOException {
        if (raw == null || raw.length < 9 || raw.length > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("NCM descriptors missing or oversized");
        }
        int control = -1;
        int data = -1;
        int capabilities = -1;
        int version = -1;
        int maxSegment = -1;
        int current = -1;
        int alternate = -1;
        int interfaceClass = -1;
        int configEnd = raw.length;
        boolean configuration = false;
        boolean inactive = false;
        boolean active = false;
        boolean bulkIn = false;
        boolean bulkOut = false;
        for (int offset = 0; offset < configEnd; ) {
            if (offset + 2 > configEnd) {
                throw new IOException("Truncated NCM descriptor header");
            }
            int length = raw[offset] & 0xff;
            int type = raw[offset + 1] & 0xff;
            if (length < 2 || offset + length > configEnd) {
                throw new IOException("Truncated NCM descriptor");
            }
            if (type == 2) {
                if (configuration || length < 9 || u16(raw, offset + 2) < length
                        || offset + u16(raw, offset + 2) != raw.length) {
                    throw new IOException("NCM requires one complete configuration");
                }
                configuration = true;
                configEnd = offset + u16(raw, offset + 2);
            } else if (configuration && type == 4 && length >= 9) {
                current = raw[offset + 2] & 0xff;
                alternate = raw[offset + 3] & 0xff;
                interfaceClass = raw[offset + 5] & 0xff;
                int subclass = raw[offset + 6] & 0xff;
                if (interfaceClass == 8) {
                    throw new IOException("NCM storage composite is outside the validation scope");
                }
                if (interfaceClass == 2 && subclass == 13) {
                    if (control >= 0 || alternate != 0) {
                        throw new IOException("Ambiguous NCM control interface");
                    }
                    control = current;
                } else if (interfaceClass == 10 && current == data) {
                    int endpoints = raw[offset + 4] & 0xff;
                    if ((raw[offset + 7] & 0xff) != 1) {
                        throw new IOException("Unsupported NCM data protocol");
                    }
                    inactive |= alternate == 0 && endpoints == 0;
                    active |= alternate == 1 && endpoints == 2;
                }
            } else if (configuration && type == 0x24 && current == control && alternate == 0) {
                if (length < 3) {
                    throw new IOException("Truncated NCM functional descriptor");
                }
                int subtype = raw[offset + 2] & 0xff;
                if (subtype == 6) {
                    if (length != 5 || data >= 0 || (raw[offset + 3] & 0xff) != control) {
                        throw new IOException("Invalid NCM union descriptor");
                    }
                    data = raw[offset + 4] & 0xff;
                    if (data == control) {
                        throw new IOException("NCM control/data interface overlap");
                    }
                } else if (subtype == 0x1a) {
                    if (length != 6 || capabilities >= 0) {
                        throw new IOException("Invalid NCM capability descriptor");
                    }
                    version = u16(raw, offset + 3);
                    capabilities = raw[offset + 5] & 0xff;
                } else if (subtype == 0x0f) {
                    if (length < 13 || maxSegment >= 0) {
                        throw new IOException("Invalid NCM Ethernet descriptor");
                    }
                    maxSegment = u16(raw, offset + 8);
                }
            } else if (configuration && type == 5 && current == data && alternate == 1
                    && interfaceClass == 10) {
                if (length < 7 || (raw[offset + 3] & 3) != 2 || u16(raw, offset + 4) == 0) {
                    throw new IOException("Invalid NCM bulk endpoint");
                }
                boolean input = (raw[offset + 2] & 0x80) != 0;
                if (input ? bulkIn : bulkOut) {
                    throw new IOException("Duplicate NCM bulk direction");
                }
                bulkIn |= input;
                bulkOut |= !input;
            }
            offset += length;
        }
        if (!configuration || control < 0 || data < 0 || capabilities < 0 || version != 0x0100
                || maxSegment < 1514 || !inactive || !active || !bulkIn || !bulkOut) {
            throw new IOException("Incomplete or unsupported NCM 1.0 function");
        }
        return new NcmDescriptor(control, data, capabilities, version, maxSegment);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
    }
}
