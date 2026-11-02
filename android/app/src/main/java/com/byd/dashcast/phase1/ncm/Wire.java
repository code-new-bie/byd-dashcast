package com.byd.dashcast.phase1.ncm;

import java.util.Locale;

/** 线格式小工具：地址解析/格式化、字节序、校验和。 */
public final class Wire {

    private Wire() {
    }

    /* ---------------- IP ---------------- */

    /** "192.168.77.2" -> 0xC0A84D02（主机序，高位为第一段）。解析失败返回 0。 */
    public static int ip(String s) {
        if (s == null) return 0;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return 0;
        int v = 0;
        try {
            for (int i = 0; i < 4; i++) {
                int n = Integer.parseInt(p[i].trim());
                if (n < 0 || n > 255) return 0;
                v = (v << 8) | n;
            }
        } catch (Throwable t) {
            return 0;
        }
        return v;
    }

    public static String ip(int v) {
        return ((v >>> 24) & 0xFF) + "." + ((v >>> 16) & 0xFF) + "."
                + ((v >>> 8) & 0xFF) + "." + (v & 0xFF);
    }

    /** 前缀长度 -> 掩码（主机序）。 */
    public static int mask(int prefix) {
        if (prefix <= 0) return 0;
        if (prefix >= 32) return -1;
        return (int) (0xFFFFFFFFL << (32 - prefix));
    }

    /** 同网段判定；prefix=0 时视为同网段（点对点直连场景）。 */
    public static boolean sameSubnet(int a, int b, int prefix) {
        if (prefix <= 0 || prefix >= 32) return true;
        int m = mask(prefix);
        return (a & m) == (b & m);
    }

    /* ---------------- MAC ---------------- */

    /** "02:00:00:00:07:02" -> byte[6]；失败返回 null。 */
    public static byte[] mac(String s) {
        if (s == null) return null;
        String t = s.trim().replace('-', ':').replace(".", ":");
        String[] p = t.split(":");
        if (p.length != 6) return null;
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                int n = Integer.parseInt(p[i].trim(), 16);
                if (n < 0 || n > 255) return null;
                out[i] = (byte) n;
            }
        } catch (Throwable e) {
            return null;
        }
        return out;
    }

    public static String mac(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    public static boolean macEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    public static final byte[] MAC_BCAST = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
    };

    public static final byte[] MAC_ZERO = new byte[6];

    public static boolean isBroadcast(byte[] mac) {
        return macEquals(mac, MAC_BCAST);
    }

    /* ---------------- 字节序 ---------------- */

    public static int u8(byte[] b, int o) {
        return b[o] & 0xFF;
    }

    public static int be16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static void put8(byte[] b, int o, int v) {
        b[o] = (byte) v;
    }

    public static void put16(byte[] b, int o, int v) {
        b[o] = (byte) ((v >> 8) & 0xFF);
        b[o + 1] = (byte) (v & 0xFF);
    }

    public static void put32(byte[] b, int o, long v) {
        b[o] = (byte) ((v >> 24) & 0xFF);
        b[o + 1] = (byte) ((v >> 16) & 0xFF);
        b[o + 2] = (byte) ((v >> 8) & 0xFF);
        b[o + 3] = (byte) (v & 0xFF);
    }

    /* ---------------- 校验和 ---------------- */

    /**
     * 16 位一补数校验和，返回值已按「可直接写入校验和字段」的形式取反。
     *
     * @param init 之前累加的部分（用于伪首部），首次调用传 0
     */
    public static int sum(byte[] b, int off, int len, int init) {
        int s = init;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) {
            s += ((b[i] & 0xFF) << 8);
        }
        while ((s >>> 16) != 0) {
            s = (s & 0xFFFF) + (s >>> 16);
        }
        return (~s) & 0xFFFF;
    }

    public static int sum2(int init, int a, int b) {
        int s = init + a + b;
        while ((s >>> 16) != 0) {
            s = (s & 0xFFFF) + (s >>> 16);
        }
        return s;
    }

    /** 原始累加（不取反），用于分两段计算（伪首部 + 报文）。 */
    public static int acc(byte[] b, int off, int len, int init) {
        int s = init;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) s += ((b[i] & 0xFF) << 8);
        return foldCarry(s);
    }

    private static int foldCarry(int s) {
        int v = s;
        while ((v >>> 16) != 0) {
            v = (v & 0xFFFF) + (v >>> 16);
        }
        return v;
    }

    /** 把原始累加值收成最终校验和字段值。 */
    public static int finish(int accRaw) {
        return (~foldCarry(accRaw)) & 0xFFFF;
    }

    /** 校验一段已带校验和的报文（返回 0 表示正确）。 */
    public static int verify(byte[] b, int off, int len, int init) {
        return finish(acc(b, off, len, init));
    }
}
