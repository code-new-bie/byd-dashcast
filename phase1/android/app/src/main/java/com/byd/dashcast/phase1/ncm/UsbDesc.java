package com.byd.dashcast.phase1.ncm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB 描述符原始字节解析。
 *
 * <p>为什么不用 {@code UsbDevice}/{@code UsbConfiguration} 那套高层对象？
 * 因为 Android 的框架层对 **交替设置（alternate setting）** 的支持是残缺的：
 * 一个 interface number 只保留一份，往往是 alt 0。而 CDC-ECM 风格的 NCM 设备
 * 恰恰把两个 bulk 端点放在 alt 1，alt 0 是零端点占位。
 * 一旦拿不到端点对象，整条链路就走不通。
 *
 * <p>所以这里直接吃 {@code UsbDeviceConnection.getRawDescriptors()} 的原始字节，
 * 自己把 configuration / interface / endpoint 全树还原出来——
 * 这既是接管失败时最有力的取证材料，也是 alt 1 端点信息的唯一可靠来源。
 */
public final class UsbDesc {

    private UsbDesc() {
    }

    /** 一个解析出来的端点。 */
    public static final class Ep {
        public int ifaceNum;
        public int altSetting;
        public int address;      // bEndpointAddress，bit7 = 方向(1=IN)
        public int attributes;   // bmAttributes，bit1..0 = 传输类型(2=BULK)
        public int maxPacket;    // wMaxPacketSize
        public int interval;

        public boolean isIn() {
            return (address & 0x80) != 0;
        }

        public boolean isBulk() {
            return (attributes & 0x03) == 2;
        }

        public int number() {
            return address & 0x0F;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "0x%02X %s num=%d %s mps=%d interval=%d (iface=%d alt=%d)",
                    address, isIn() ? "IN " : "OUT", number(),
                    isBulk() ? "BULK" : ("type" + (attributes & 3)),
                    maxPacket, interval, ifaceNum, altSetting);
        }
    }

    /* ---- 描述符类型 ---- */
    private static final int T_DEVICE = 1;
    private static final int T_CONFIG = 2;
    private static final int T_STRING = 3;
    private static final int T_INTERFACE = 4;
    private static final int T_ENDPOINT = 5;
    private static final int T_IAD = 11;
    private static final int T_CS_INTERFACE = 0x24;
    private static final int T_CS_ENDPOINT = 0x25;

    /**
     * 从原始描述符里挑出「指定接口号 + 指定交替设置」下的所有端点。
     *
     * @param raw       getRawDescriptors() 的返回值
     * @param ifaceNum  接口号
     * @param alt       -1 表示不限定交替设置
     */
    public static List<Ep> endpoints(byte[] raw, int ifaceNum, int alt) {
        List<Ep> out = new ArrayList<Ep>();
        if (raw == null) return out;
        int i = 0;
        int curIface = -1;
        int curAlt = -1;
        int cfgEnd = raw.length;
        while (i + 2 <= raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2) break;
            if (i + len > raw.length) break;

            if (type == T_CONFIG && len >= 9) {
                int total = u16(raw, i + 2);
                cfgEnd = Math.min(raw.length, i + total);
            } else if (type == T_INTERFACE && len >= 9) {
                curIface = raw[i + 2] & 0xFF;
                curAlt = raw[i + 3] & 0xFF;
            } else if (type == T_ENDPOINT && len >= 7) {
                Ep ep = new Ep();
                ep.ifaceNum = curIface;
                ep.altSetting = curAlt;
                ep.address = raw[i + 2] & 0xFF;
                ep.attributes = raw[i + 3] & 0xFF;
                ep.maxPacket = u16(raw, i + 4) & 0x7FF;
                ep.interval = raw[i + 6] & 0xFF;
                if (ifaceNum < 0 || ep.ifaceNum == ifaceNum) {
                    if (alt < 0 || ep.altSetting == alt) out.add(ep);
                }
            }
            i += len;
            if (i >= cfgEnd) break;
        }
        return out;
    }

    /** 接口的 (interfaceNumber, alternateSetting) 组合是否真实存在于描述符中。 */
    public static boolean hasAlt(byte[] raw, int ifaceNum, int alt) {
        if (raw == null) return false;
        int i = 0;
        int cfgEnd = raw.length;
        while (i + 2 <= raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2 || i + len > raw.length) break;
            if (type == T_CONFIG && len >= 9) {
                cfgEnd = Math.min(raw.length, i + u16(raw, i + 2));
            } else if (type == T_INTERFACE && len >= 9) {
                if ((raw[i + 2] & 0xFF) == ifaceNum && (raw[i + 3] & 0xFF) == alt) return true;
            }
            i += len;
            if (i >= cfgEnd) break;
        }
        return false;
    }

    /** 把整棵描述符树格式化成人类可读文本。 */
    public static String dump(byte[] raw) {
        StringBuilder sb = new StringBuilder();
        if (raw == null || raw.length == 0) return "(描述符为空)";
        sb.append("raw=").append(raw.length).append(" bytes\n");
        int i = 0;
        int indent = 0;
        while (i + 2 <= raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2 || i + len > raw.length) {
                sb.append(pad(indent)).append("!坏描述符 @").append(i)
                        .append(" len=").append(len).append('\n');
                break;
            }
            byte[] d = new byte[len];
            System.arraycopy(raw, i, d, 0, len);
            switch (type) {
                case T_DEVICE:
                    if (len >= 18) {
                        indent = 0;
                        sb.append("DEVICE  bcdUSB=0x").append(hex4(u16(d, 2)))
                                .append(" class=0x").append(hex2(d[4]))
                                .append(" sub=0x").append(hex2(d[5]))
                                .append(" proto=0x").append(hex2(d[6]))
                                .append(" mps0=").append(d[7] & 0xFF)
                                .append(" vid=0x").append(hex4(u16(d, 8)))
                                .append(" pid=0x").append(hex4(u16(d, 10)))
                                .append(" bcdDev=0x").append(hex4(u16(d, 12)))
                                .append('\n');
                    }
                    break;
                case T_CONFIG:
                    sb.append("CONFIG  total=").append(u16(d, 2))
                            .append(" ifaces=").append(d[4] & 0xFF)
                            .append(" bCfgValue=").append(d[5] & 0xFF)
                            .append(" attr=0x").append(hex2(d[7]))
                            .append(" ").append((d[8] & 0xff) * 2).append("mA\n");
                    indent = 1;
                    break;
                case T_INTERFACE:
                    if (len >= 9) {
                        sb.append(pad(1)).append("IFACE num=").append(d[2] & 0xFF)
                                .append(" alt=").append(d[3] & 0xFF)
                                .append(" eps=").append(d[4] & 0xFF)
                                .append(" class=0x").append(hex2(d[5]))
                                .append(" sub=0x").append(hex2(d[6]))
                                .append(" proto=0x").append(hex2(d[7]))
                                .append("  ").append(ncmHint(d[5] & 0xFF, d[6] & 0xFF, d[7] & 0xFF))
                                .append('\n');
                    }
                    indent = 2;
                    break;
                case T_ENDPOINT:
                    if (len >= 7) {
                        int attr = d[3] & 0xFF;
                        sb.append(pad(2)).append("EP    0x").append(hex2(d[2]))
                                .append(" ").append((d[2] & 0x80) != 0 ? "IN " : "OUT")
                                .append(" ").append(epTypeName(attr & 3))
                                .append(" mps=").append(u16(d, 4) & 0x7FF)
                                .append(" ivl=").append(d[6] & 0xFF)
                                .append('\n');
                    }
                    break;
                case T_IAD:
                    if (len >= 8) {
                        sb.append(pad(1)).append("IAD    first=").append(d[2] & 0xFF)
                                .append(" count=").append(d[3] & 0xFF)
                                .append(" class=0x").append(hex2(d[4]))
                                .append(" sub=0x").append(hex2(d[5]))
                                .append(" proto=0x").append(hex2(d[6]))
                                .append('\n');
                    }
                    break;
                case T_CS_INTERFACE:
                    if (len >= 3) {
                        sb.append(pad(2)).append("CS-IF subtype=0x").append(hex2(d[2]))
                                .append(" ").append(cdcSubtypeName(d[2] & 0xFF))
                                .append("  [").append(hexLine(d)).append("]\n");
                    }
                    break;
                case T_CS_ENDPOINT:
                    if (len >= 3) {
                        sb.append(pad(2)).append("CS-EP subtype=0x").append(hex2(d[2]))
                                .append("  [").append(hexLine(d)).append("]\n");
                    }
                    break;
                case T_STRING:
                    sb.append(pad(indent)).append("STRING [").append(hexLine(d)).append("]\n");
                    break;
                default:
                    sb.append(pad(indent)).append("TYPE").append(type)
                            .append(" len=").append(len)
                            .append("  [").append(hexLine(d)).append("]\n");
                    break;
            }
            i += len;
        }
        return sb.toString();
    }

    /**
     * 在配置描述符里找 CDC Ethernet Networking Functional Descriptor
     * （CS_INTERFACE, subtype=0x0F），返回它的 iMACAddress 字符串索引（-1 表示没有）。
     *
     * <p>NCM 设备通常会附带这个描述符来声明固件写死的 MAC；
     * 内核 cdc_ncm 正是靠它给 usb0 配 MAC——你 {@code ip link} 看到的
     * {@code 02:00:00:00:07:02} 就来自这里。本 App 不需要 usb0、不需要 root，
     * 直接从描述符读同一个值做交叉验证。
     */
    public static int ethernetMacIndex(byte[] raw) {
        if (raw == null) return -1;
        int i = 0;
        int cfgEnd = raw.length;
        while (i + 2 <= raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2) break;
            if (i + len > raw.length) break;
            if (type == T_CONFIG && len >= 9) {
                cfgEnd = Math.min(raw.length, i + u16(raw, i + 2));
            } else if (type == T_CS_INTERFACE && len >= 4) {
                int sub = raw[i + 2] & 0xFF;
                if (sub == 0x0F) {
                    return raw[i + 3] & 0xFF;   // iMACAddress
                }
            }
            i += len;
            if (i >= cfgEnd) break;
        }
        return -1;
    }

    private static String ncmHint(int cls, int sub, int proto) {
        if (cls == 2 && sub == 13) return "<= CDC-NCM 控制接口";
        if (cls == 2 && sub == 6) return "<= CDC-ECM 控制接口";
        if (cls == 2 && sub == 2) return "<= CDC-ACM 控制接口";
        if (cls == 2) return "<= CDC 控制接口";
        if (cls == 10) return "<= CDC 数据接口（NCM 载荷走这里）";
        if (cls == 239) return "<= 复合设备(IAD)";
        if (cls == 8) return "<= 大容量存储";
        if (cls == 3) return "<= HID";
        if (cls == 255) return "<= 厂商自定义（潜在控制面）";
        return "";
    }

    private static String cdcSubtypeName(int s) {
        switch (s) {
            case 0x00: return "HEADER";
            case 0x01: return "CALL_MANAGEMENT";
            case 0x02: return "ACM";
            case 0x03: return "DIRECT_LINE";
            case 0x06: return "UNION";
            case 0x0F: return "ETHERNET";
            case 0x1A: return "NCM";
            case 0x1B: return "MBIM";
            case 0x0D: return "OBEX";
            case 0x0B: return "WHCM";
            default: return "";
        }
    }

    private static String epTypeName(int t) {
        switch (t) {
            case 0: return "CTRL";
            case 1: return "ISOC";
            case 2: return "BULK";
            case 3: return "INT ";
            default: return "?   ";
        }
    }

    private static String hexLine(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < d.length; k++) {
            if (k > 0) sb.append(' ');
            sb.append(hex2(d[k]));
        }
        return sb.toString();
    }

    private static String hex2(byte b) {
        return String.format(Locale.US, "%02x", b & 0xFF);
    }

    private static String hex4(int v) {
        return String.format(Locale.US, "%04x", v & 0xFFFF);
    }

    private static int u16(byte[] d, int off) {
        if (off + 1 >= d.length) return 0;
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static String pad(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }
}
