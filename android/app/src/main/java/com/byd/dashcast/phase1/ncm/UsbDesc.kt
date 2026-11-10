package com.byd.dashcast.phase1.ncm

import java.util.Locale

/**
 * USB 描述符原始字节解析。
 *
 * 为什么不用 `UsbDevice`/`UsbConfiguration` 那套高层对象？
 * 因为 Android 的框架层对 **交替设置（alternate setting）** 的支持是残缺的：
 * 一个 interface number 只保留一份，往往是 alt 0。而 CDC-ECM 风格的 NCM 设备
 * 恰恰把两个 bulk 端点放在 alt 1，alt 0 是零端点占位。
 * 一旦拿不到端点对象，整条链路就走不通。
 *
 * 所以这里直接吃 `UsbDeviceConnection.getRawDescriptors()` 的原始字节，
 * 自己把 configuration / interface / endpoint 全树还原出来——
 * 这既是接管失败时最有力的取证材料，也是 alt 1 端点信息的唯一可靠来源。
 */
object UsbDesc {

    /** 一个解析出来的端点。 */
    class Ep {
        @JvmField var ifaceNum = 0
        @JvmField var altSetting = 0
        @JvmField var address = 0      // bEndpointAddress，bit7 = 方向(1=IN)
        @JvmField var attributes = 0   // bmAttributes，bit1..0 = 传输类型(2=BULK)
        @JvmField var maxPacket = 0    // wMaxPacketSize
        @JvmField var interval = 0

        fun isIn(): Boolean = (address and 0x80) != 0

        fun isBulk(): Boolean = (attributes and 0x03) == 2

        fun number(): Int = address and 0x0F

        override fun toString(): String = String.format(Locale.US,
            "0x%02X %s num=%d %s mps=%d interval=%d (iface=%d alt=%d)",
            address, if (isIn()) "IN " else "OUT", number(),
            if (isBulk()) "BULK" else ("type" + (attributes and 3)),
            maxPacket, interval, ifaceNum, altSetting)
    }

    /* ---- 描述符类型 ---- */
    private const val T_DEVICE = 1
    private const val T_CONFIG = 2
    private const val T_STRING = 3
    private const val T_INTERFACE = 4
    private const val T_ENDPOINT = 5
    private const val T_IAD = 11
    private const val T_CS_INTERFACE = 0x24
    private const val T_CS_ENDPOINT = 0x25

    /**
     * 从原始描述符里挑出「指定接口号 + 指定交替设置」下的所有端点。
     *
     * @param raw       getRawDescriptors() 的返回值
     * @param ifaceNum  接口号
     * @param alt       -1 表示不限定交替设置
     */
    @JvmStatic
    fun endpoints(raw: ByteArray?, ifaceNum: Int, alt: Int): List<Ep> {
        val out = ArrayList<Ep>()
        if (raw == null) return out
        var i = 0
        var curIface = -1
        var curAlt = -1
        var cfgEnd = raw.size
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (len < 2) break
            if (i + len > raw.size) break

            if (type == T_CONFIG && len >= 9) {
                val total = u16(raw, i + 2)
                cfgEnd = minOf(raw.size, i + total)
            } else if (type == T_INTERFACE && len >= 9) {
                curIface = raw[i + 2].toInt() and 0xFF
                curAlt = raw[i + 3].toInt() and 0xFF
            } else if (type == T_ENDPOINT && len >= 7) {
                val ep = Ep()
                ep.ifaceNum = curIface
                ep.altSetting = curAlt
                ep.address = raw[i + 2].toInt() and 0xFF
                ep.attributes = raw[i + 3].toInt() and 0xFF
                ep.maxPacket = u16(raw, i + 4) and 0x7FF
                ep.interval = raw[i + 6].toInt() and 0xFF
                if (ifaceNum < 0 || ep.ifaceNum == ifaceNum) {
                    if (alt < 0 || ep.altSetting == alt) out.add(ep)
                }
            }
            i += len
            if (i >= cfgEnd) break
        }
        return out
    }

    /** 接口的 (interfaceNumber, alternateSetting) 组合是否真实存在于描述符中。 */
    @JvmStatic
    fun hasAlt(raw: ByteArray?, ifaceNum: Int, alt: Int): Boolean {
        if (raw == null) return false
        var i = 0
        var cfgEnd = raw.size
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            if (type == T_CONFIG && len >= 9) {
                cfgEnd = minOf(raw.size, i + u16(raw, i + 2))
            } else if (type == T_INTERFACE && len >= 9) {
                if ((raw[i + 2].toInt() and 0xFF) == ifaceNum && (raw[i + 3].toInt() and 0xFF) == alt) return true
            }
            i += len
            if (i >= cfgEnd) break
        }
        return false
    }

    /** 把整棵描述符树格式化成人类可读文本。 */
    @JvmStatic
    fun dump(raw: ByteArray?): String {
        val sb = StringBuilder()
        if (raw == null || raw.isEmpty()) return "(描述符为空)"
        sb.append("raw=").append(raw.size).append(" bytes\n")
        var i = 0
        var indent = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) {
                sb.append(pad(indent)).append("!坏描述符 @").append(i)
                    .append(" len=").append(len).append('\n')
                break
            }
            val d = raw.copyOfRange(i, i + len)
            when (type) {
                T_DEVICE -> if (len >= 18) {
                    indent = 0
                    sb.append("DEVICE  bcdUSB=0x").append(hex4(u16(d, 2)))
                        .append(" class=0x").append(hex2(d[4]))
                        .append(" sub=0x").append(hex2(d[5]))
                        .append(" proto=0x").append(hex2(d[6]))
                        .append(" mps0=").append(d[7].toInt() and 0xFF)
                        .append(" vid=0x").append(hex4(u16(d, 8)))
                        .append(" pid=0x").append(hex4(u16(d, 10)))
                        .append(" bcdDev=0x").append(hex4(u16(d, 12)))
                        .append('\n')
                }
                T_CONFIG -> {
                    sb.append("CONFIG  total=").append(u16(d, 2))
                        .append(" ifaces=").append(d[4].toInt() and 0xFF)
                        .append(" bCfgValue=").append(d[5].toInt() and 0xFF)
                        .append(" attr=0x").append(hex2(d[7]))
                        .append(" ").append((d[8].toInt() and 0xff) * 2).append("mA\n")
                    indent = 1
                }
                T_INTERFACE -> {
                    if (len >= 9) {
                        sb.append(pad(1)).append("IFACE num=").append(d[2].toInt() and 0xFF)
                            .append(" alt=").append(d[3].toInt() and 0xFF)
                            .append(" eps=").append(d[4].toInt() and 0xFF)
                            .append(" class=0x").append(hex2(d[5]))
                            .append(" sub=0x").append(hex2(d[6]))
                            .append(" proto=0x").append(hex2(d[7]))
                            .append("  ").append(ncmHint(d[5].toInt() and 0xFF, d[6].toInt() and 0xFF, d[7].toInt() and 0xFF))
                            .append('\n')
                    }
                    indent = 2
                }
                T_ENDPOINT -> if (len >= 7) {
                    val attr = d[3].toInt() and 0xFF
                    sb.append(pad(2)).append("EP    0x").append(hex2(d[2]))
                        .append(" ").append(if ((d[2].toInt() and 0x80) != 0) "IN " else "OUT")
                        .append(" ").append(epTypeName(attr and 3))
                        .append(" mps=").append(u16(d, 4) and 0x7FF)
                        .append(" ivl=").append(d[6].toInt() and 0xFF)
                        .append('\n')
                }
                T_IAD -> if (len >= 8) {
                    sb.append(pad(1)).append("IAD    first=").append(d[2].toInt() and 0xFF)
                        .append(" count=").append(d[3].toInt() and 0xFF)
                        .append(" class=0x").append(hex2(d[4]))
                        .append(" sub=0x").append(hex2(d[5]))
                        .append(" proto=0x").append(hex2(d[6]))
                        .append('\n')
                }
                T_CS_INTERFACE -> if (len >= 3) {
                    sb.append(pad(2)).append("CS-IF subtype=0x").append(hex2(d[2]))
                        .append(" ").append(cdcSubtypeName(d[2].toInt() and 0xFF))
                        .append("  [").append(hexLine(d)).append("]\n")
                }
                T_CS_ENDPOINT -> if (len >= 3) {
                    sb.append(pad(2)).append("CS-EP subtype=0x").append(hex2(d[2]))
                        .append("  [").append(hexLine(d)).append("]\n")
                }
                T_STRING -> sb.append(pad(indent)).append("STRING [").append(hexLine(d)).append("]\n")
                else -> sb.append(pad(indent)).append("TYPE").append(type)
                    .append(" len=").append(len)
                    .append("  [").append(hexLine(d)).append("]\n")
            }
            i += len
        }
        return sb.toString()
    }

    /**
     * 在配置描述符里找 CDC Ethernet Networking Functional Descriptor
     * （CS_INTERFACE, subtype=0x0F），返回它的 iMACAddress 字符串索引（-1 表示没有）。
     *
     * NCM 设备通常会附带这个描述符来声明固件写死的 MAC；
     * 内核 cdc_ncm 正是靠它给 usb0 配 MAC——你 `ip link` 看到的
     * `02:00:00:00:07:02` 就来自这里。本 App 不需要 usb0、不需要 root，
     * 直接从描述符读同一个值做交叉验证。
     */
    @JvmStatic
    fun ethernetMacIndex(raw: ByteArray?): Int {
        if (raw == null) return -1
        var i = 0
        var cfgEnd = raw.size
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (len < 2) break
            if (i + len > raw.size) break
            if (type == T_CONFIG && len >= 9) {
                cfgEnd = minOf(raw.size, i + u16(raw, i + 2))
            } else if (type == T_CS_INTERFACE && len >= 4) {
                val sub = raw[i + 2].toInt() and 0xFF
                if (sub == 0x0F) {
                    return raw[i + 3].toInt() and 0xFF   // iMACAddress
                }
            }
            i += len
            if (i >= cfgEnd) break
        }
        return -1
    }

    private fun ncmHint(cls: Int, sub: Int, proto: Int): String {
        if (cls == 2 && sub == 13) return "<= CDC-NCM 控制接口"
        if (cls == 2 && sub == 6) return "<= CDC-ECM 控制接口"
        if (cls == 2 && sub == 2) return "<= CDC-ACM 控制接口"
        if (cls == 2) return "<= CDC 控制接口"
        if (cls == 10) return "<= CDC 数据接口（NCM 载荷走这里）"
        if (cls == 239) return "<= 复合设备(IAD)"
        if (cls == 8) return "<= 大容量存储"
        if (cls == 3) return "<= HID"
        if (cls == 255) return "<= 厂商自定义（潜在控制面）"
        return ""
    }

    private fun cdcSubtypeName(s: Int): String = when (s) {
        0x00 -> "HEADER"
        0x01 -> "CALL_MANAGEMENT"
        0x02 -> "ACM"
        0x03 -> "DIRECT_LINE"
        0x06 -> "UNION"
        0x0F -> "ETHERNET"
        0x1A -> "NCM"
        0x1B -> "MBIM"
        0x0D -> "OBEX"
        0x0B -> "WHCM"
        else -> ""
    }

    private fun epTypeName(t: Int): String = when (t) {
        0 -> "CTRL"
        1 -> "ISOC"
        2 -> "BULK"
        3 -> "INT "
        else -> "?   "
    }

    private fun hexLine(d: ByteArray): String {
        val sb = StringBuilder()
        for (k in d.indices) {
            if (k > 0) sb.append(' ')
            sb.append(hex2(d[k]))
        }
        return sb.toString()
    }

    private fun hex2(b: Byte): String = String.format(Locale.US, "%02x", b.toInt() and 0xFF)

    private fun hex4(v: Int): String = String.format(Locale.US, "%04x", v and 0xFFFF)

    private fun u16(d: ByteArray, off: Int): Int {
        if (off + 1 >= d.size) return 0
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun pad(n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) sb.append("  ")
        return sb.toString()
    }
}
