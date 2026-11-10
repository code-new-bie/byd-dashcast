package com.byd.dashcast.phase1.ncm

import java.util.Locale
import java.util.regex.Pattern

/** 线格式小工具：地址解析/格式化、字节序、校验和。 */
object Wire {
    // Pattern.split drops trailing empty fields exactly like String.split in Java.
    private val DOT = Pattern.compile("\\.")
    private val COLON = Pattern.compile(":")

    /* ---------------- IP ---------------- */

    /** "192.168.77.2" -> 0xC0A84D02（主机序，高位为第一段）。解析失败返回 0。 */
    @JvmStatic
    fun ip(s: String?): Int {
        if (s == null) return 0
        val p = DOT.split(s.trim())
        if (p.size != 4) return 0
        var v = 0
        try {
            for (i in 0 until 4) {
                val n = Integer.parseInt(p[i].trim())
                if (n < 0 || n > 255) return 0
                v = (v shl 8) or n
            }
        } catch (t: Throwable) {
            return 0
        }
        return v
    }

    @JvmStatic
    fun ip(v: Int): String =
        ((v ushr 24) and 0xFF).toString() + "." + ((v ushr 16) and 0xFF) + "." + ((v ushr 8) and 0xFF) + "." + (v and 0xFF)

    /** 前缀长度 -> 掩码（主机序）。 */
    @JvmStatic
    fun mask(prefix: Int): Int {
        if (prefix <= 0) return 0
        if (prefix >= 32) return -1
        return (0xFFFFFFFFL shl (32 - prefix)).toInt()
    }

    /** 同网段判定；prefix=0 时视为同网段（点对点直连场景）。 */
    @JvmStatic
    fun sameSubnet(a: Int, b: Int, prefix: Int): Boolean {
        if (prefix <= 0 || prefix >= 32) return true
        val m = mask(prefix)
        return (a and m) == (b and m)
    }

    /* ---------------- MAC ---------------- */

    /** "02:00:00:00:07:02" -> byte[6]；失败返回 null。 */
    @JvmStatic
    fun mac(s: String?): ByteArray? {
        if (s == null) return null
        val t = s.trim().replace('-', ':').replace(".", ":")
        val p = COLON.split(t)
        if (p.size != 6) return null
        val out = ByteArray(6)
        try {
            for (i in 0 until 6) {
                val n = Integer.parseInt(p[i].trim(), 16)
                if (n < 0 || n > 255) return null
                out[i] = n.toByte()
            }
        } catch (e: Throwable) {
            return null
        }
        return out
    }

    @JvmStatic
    fun mac(b: ByteArray?): String {
        if (b == null) return "(null)"
        val sb = StringBuilder()
        for (i in b.indices) {
            if (i > 0) sb.append(':')
            sb.append(String.format(Locale.US, "%02x", b[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    @JvmStatic
    fun macEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    @JvmField
    val MAC_BCAST = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    @JvmField
    val MAC_ZERO = ByteArray(6)

    @JvmStatic
    fun isBroadcast(mac: ByteArray?): Boolean = macEquals(mac, MAC_BCAST)

    /* ---------------- 字节序 ---------------- */

    @JvmStatic
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    @JvmStatic
    fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    @JvmStatic
    fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    @JvmStatic
    fun put8(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
    }

    @JvmStatic
    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    @JvmStatic
    fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    /* ---------------- 校验和 ---------------- */

    /**
     * 16 位一补数校验和，返回值已按「可直接写入校验和字段」的形式取反。
     *
     * @param init 之前累加的部分（用于伪首部），首次调用传 0
     */
    @JvmStatic
    fun sum(b: ByteArray, off: Int, len: Int, init: Int): Int {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            s += (b[i].toInt() and 0xFF) shl 8
        }
        while ((s ushr 16) != 0) {
            s = (s and 0xFFFF) + (s ushr 16)
        }
        return s.inv() and 0xFFFF
    }

    @JvmStatic
    fun sum2(init: Int, a: Int, b: Int): Int {
        var s = init + a + b
        while ((s ushr 16) != 0) {
            s = (s and 0xFFFF) + (s ushr 16)
        }
        return s
    }

    /** 原始累加（不取反），用于分两段计算（伪首部 + 报文）。 */
    @JvmStatic
    fun acc(b: ByteArray, off: Int, len: Int, init: Int): Int {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return foldCarry(s)
    }

    private fun foldCarry(s: Int): Int {
        var v = s
        while ((v ushr 16) != 0) {
            v = (v and 0xFFFF) + (v ushr 16)
        }
        return v
    }

    /** 把原始累加值收成最终校验和字段值。 */
    @JvmStatic
    fun finish(accRaw: Int): Int = foldCarry(accRaw).inv() and 0xFFFF

    /** 校验一段已带校验和的报文（返回 0 表示正确）。 */
    @JvmStatic
    fun verify(b: ByteArray, off: Int, len: Int, init: Int): Int = finish(acc(b, off, len, init))
}
