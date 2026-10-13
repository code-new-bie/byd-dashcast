package com.byd.dashcast.phase1.ncm;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USB 设备发现 / 运行时授权 / 接口接管 / CDC 控制请求。
 *
 * <p><b>权限边界（刻意如此）：</b>不使用 root、su、系统签名，不申请任何系统级网络配置权限。
 * 设备访问完全依赖 {@link UsbManager} 的运行时授权弹窗（用户点一下「允许」）。
 *
 * <p><b>接管策略（按优先级）：</b>
 * <ol>
 *   <li>框架已暴露带 bulk 端点的接口 → 直接用；</li>
 *   <li>框架只暴露 alt 0（零端点），真正端点藏在 alt 1 → 从原始描述符定位 alt 1，
 *       用 {@code force=true} 摘掉内核驱动后 {@code SET_INTERFACE} 切过去；</li>
 *   <li>都失败 → 输出完整描述符树供进一步分析，不静默。</li>
 * </ol>
 * 第 2 条是 CDC-ECM 风格 NCM 设备在 Android 上的典型坑，也是本 App 存在的理由之一。
 */
public final class Usb {

    private Usb() {
    }

    public static final String ACTION_PERM = "com.byd.dashcast.phase1.debug.USB_PERMISSION";
    public static final String EXTRA_GRANTED = UsbManager.EXTRA_PERMISSION_GRANTED;
    public static final String EXTRA_DEVICE = UsbManager.EXTRA_DEVICE;

    /* ---------------- 枚举 ---------------- */

    public static final class Dev {
        public UsbDevice device;
        public int vid;
        public int pid;
        public String name;
        public String manufacturer;
        public String product;
        public boolean granted;
        public String classify;
        public boolean likelyNcm;
        public boolean hasStorage;   // 含 0x08 大容量存储面（TF 卡/OTA）
        public String ifaceSummary;
        public String busDev;      // 001/002
        public String mac;         // 固件写死的 MAC（来自 CDC Ethernet 功能描述符 iMACAddress）

        public String label() {
            return String.format(Locale.US, "%04x:%04x", vid, pid) + "  " + describeProduct();
        }

        public String describeProduct() {
            StringBuilder sb = new StringBuilder();
            if (manufacturer != null && !manufacturer.isEmpty()) sb.append(manufacturer).append(' ');
            if (product != null && !product.isEmpty()) sb.append(product);
            else sb.append(name);
            return sb.toString().trim();
        }

        public String sub() {
            StringBuilder sb = new StringBuilder();
            sb.append(classify);
            if (granted) sb.append(" · 已授权");
            else sb.append(" · 未授权");
            if (busDev != null) sb.append(" · ").append(busDev);
            return sb.toString();
        }
    }

    public static UsbManager mgr(Context c) {
        return (UsbManager) c.getApplicationContext().getSystemService(Context.USB_SERVICE);
    }

    public static List<Dev> enumerate(Context c) {
        List<Dev> out = new ArrayList<Dev>();
        UsbManager m = mgr(c);
        if (m == null) {
            LogHub.e("usb", "UsbManager 为 null——本机不支持 USB Host？");
            return out;
        }
        Map<String, UsbDevice> map;
        try {
            map = m.getDeviceList();
        } catch (Throwable t) {
            LogHub.throwable("usb", "getDeviceList 失败", t);
            return out;
        }
        if (map == null) map = new LinkedHashMap<String, UsbDevice>();
        for (UsbDevice d : map.values()) {
            Dev v = new Dev();
            v.device = d;
            v.vid = d.getVendorId();
            v.pid = d.getProductId();
            v.name = safe(d.getDeviceName());
            v.manufacturer = safe(d.getManufacturerName());
            v.product = safe(d.getProductName());
            v.granted = m.hasPermission(d);
            v.busDev = busDevOf(d);
            v.ifaceSummary = ifaceSummary(d);
            // 已授权的设备顺手从描述符读固件 MAC（不依赖 usb0 / 不调系统命令）
            if (v.granted) {
                v.mac = firmwareMac(c, d);
            }
            classify(v, d);
            out.add(v);
        }
        return out;
    }

    /**
     * 从 CDC Ethernet 功能描述符的 iMACAddress 读固件写死的 MAC。
     * 全程只做一次标准 GET_DESCRIPTOR(string) 控制传输，不需要 root、不需要 usb0。
     * 返回的字符串形如 "02:00:00:00:07:02"，读不到则返回 null。
     */
    public static String firmwareMac(Context c, UsbDevice d) {
        if (d == null) return null;
        UsbManager m = mgr(c);
        if (m == null) return null;
        UsbDeviceConnection conn = null;
        try {
            conn = m.openDevice(d);
            if (conn == null) return null;
            byte[] raw = conn.getRawDescriptors();
            int idx = UsbDesc.ethernetMacIndex(raw);
            if (idx <= 0) return null;
            byte[] buf = new byte[255];
            int r = conn.controlTransfer(0x80, 0x06,
                    (0x0300 | (idx & 0xFF)) & 0xFFFF, 0x0409, buf, buf.length, 1000);
            if (r < 4) return null;
            String s = parseUsbString(buf, r);
            return normalizeMac(s);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String parseUsbString(byte[] buf, int len) {
        if (len < 2 || buf[1] != 0x03) return null;
        int n = (buf[0] & 0xFF) - 2;
        if (n < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k += 2) {
            if (3 + k >= len) break;
            int lo = buf[2 + k] & 0xFF;
            int hi = buf[3 + k] & 0xFF;
            sb.append((char) ((lo) | (hi << 8)));
        }
        return sb.toString();
    }

    /** 把 "020000000702" / "02-00-00-00-07-02" 归一化成 "02:00:00:00:07:02"。 */
    private static String normalizeMac(String s) {
        if (s == null) return null;
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                hex.append(Character.toLowerCase(ch));
            }
        }
        if (hex.length() != 12) return s;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(hex.substring(i, i + 2));
        }
        return out.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** "/dev/bus/usb/001/002" -> "001/002" */
    private static String busDevOf(UsbDevice d) {
        String n = d.getDeviceName();
        if (n == null) return null;
        int i = n.lastIndexOf('/');
        if (i <= 0) return null;
        int j = n.lastIndexOf('/', i - 1);
        if (j < 0) return n;
        return n.substring(j + 1);
    }

    private static void classify(Dev v, UsbDevice d) {
        boolean ctrl = false;
        boolean data = false;
        boolean storage = false;
        boolean anybulk = false;
        int devCls = d.getDeviceClass();
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            int cl = f.getInterfaceClass();
            if (cl == 0x02) ctrl = true;
            if (cl == 0x0A) data = true;
            if (cl == 0x08) storage = true;
            for (int k = 0; k < f.getEndpointCount(); k++) {
                UsbEndpoint e = f.getEndpoint(k);
                if (e.getType() == 2) anybulk = true;
            }
        }
        v.hasStorage = storage;
        boolean ncm = (ctrl && data) || data;
        v.likelyNcm = ncm;

        if (ncm && storage) {
            v.classify = "复合设备：NCM 数据口（投屏/仪表）+ 大容量存储面（很可能是 OTA/TF 卡）";
        } else if (ncm) {
            v.classify = "NCM/CDC 数据设备（仪表通信口）";
        } else if (storage) {
            v.classify = "大容量存储（TF 卡/读卡器/OTA）— 无 NCM，不应接管";
            v.likelyNcm = false;
        } else if (devCls == 3) {
            v.classify = "HID";
        } else if (devCls == 239 || devCls == 0) {
            v.classify = anybulk ? "复合设备（含 bulk 端点，接口类未识别为 NCM）" : "复合设备";
        } else if (devCls == 255) {
            v.classify = "厂商自定义（潜在控制面）";
        } else {
            v.classify = "class=0x" + Integer.toHexString(devCls);
        }
        if (v.vid == 0x04FC) v.classify += " · VID 匹配原对接参数 04fc";
    }

    private static String ifaceSummary(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (i > 0) sb.append(" | ");
            sb.append("if").append(f.getId())
                    .append("/alt").append(f.getAlternateSetting())
                    .append(" cls").append(String.format(Locale.US, "%02x", f.getInterfaceClass()))
                    .append(" sub").append(String.format(Locale.US, "%02x", f.getInterfaceSubclass()))
                    .append(" ep").append(f.getEndpointCount());
        }
        return sb.toString();
    }

    /* ---------------- 运行时授权 ---------------- */

    public static void registerPermReceiver(Context c, BroadcastReceiver r) {
        try {
            IntentFilter f = new IntentFilter(ACTION_PERM);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                c.registerReceiver(r, f);
            }
        } catch (Throwable t) {
            LogHub.throwable("usb", "注册授权广播失败", t);
        }
    }

    public static void unregisterPermReceiver(Context c, BroadcastReceiver r) {
        try {
            c.unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    public static void requestPermission(Context c, UsbDevice d) {
        UsbManager m = mgr(c);
        if (m == null || d == null) return;
        Intent i = new Intent(ACTION_PERM);
        i.setPackage(c.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, flags);
        LogHub.i("usb", "弹窗申请 USB 授权: " + d.getDeviceName());
        try {
            m.requestPermission(d, pi);
        } catch (Throwable t) {
            LogHub.throwable("usb", "requestPermission 失败", t);
        }
    }

    public static boolean hasPermission(Context c, UsbDevice d) {
        UsbManager m = mgr(c);
        return m != null && d != null && m.hasPermission(d);
    }

    /* ---------------- NCM 控制请求常量 ---------------- */

    /*
     * 取值来源：linux/include/uapi/linux/usb/cdc.h（内核权威定义）。
     * 注意 0x81/0x82 是 GET/SET_NET_ADDRESS（NCM 1.1 新增），**不是** NTB_FORMAT——
     * 常见实现按 0x80 起顺推，会把 NTB 系列全部错位一格，必须避开。
     */
    public static final int REQ_SET_ETHERNET_PACKET_FILTER = 0x43;
    public static final int REQ_GET_ETHERNET_STATISTIC = 0x44;
    public static final int REQ_GET_NTB_PARAMETERS = 0x80;
    public static final int REQ_GET_NTB_FORMAT = 0x83;
    public static final int REQ_SET_NTB_FORMAT = 0x84;
    public static final int REQ_GET_NTB_INPUT_SIZE = 0x85;
    public static final int REQ_SET_NTB_INPUT_SIZE = 0x86;
    public static final int REQ_GET_MAX_DATAGRAM_SIZE = 0x87;
    public static final int REQ_SET_MAX_DATAGRAM_SIZE = 0x88;
    public static final int REQ_GET_CRC_MODE = 0x89;
    public static final int REQ_SET_CRC_MODE = 0x8a;

    /* CDC 以太网包过滤位（cdc.h：PROMISCUOUS/ALL_MULTICAST/DIRECTED/BROADCAST/MULTICAST） */
    public static final int PKT_PROMISCUOUS = 1;
    public static final int PKT_ALL_MULTICAST = 2;
    public static final int PKT_DIRECTED = 4;
    public static final int PKT_BROADCAST = 8;
    public static final int PKT_MULTICAST = 16;

    /** GET_NTB_PARAMETERS 的返回结构。 */
    public static final class Ntb {
        public int formats;
        public int inMaxSize;
        public int ndpInDivisor;
        public int ndpInPayloadRemainder;
        public int ndpInAlignment;
        public int outMaxSize;
        public int ndpOutDivisor;
        public int ndpOutPayloadRemainder;
        public int ndpOutAlignment;
        public int outMaxDatagrams;

        public String toString() {
            return "formats=" + formats + (formats == 1 ? " (仅 NTB-16)" : "")
                    + "\n  IN : maxSize=" + inMaxSize + " divisor=" + ndpInDivisor
                    + " rem=" + ndpInPayloadRemainder + " align=" + ndpInAlignment
                    + "\n  OUT: maxSize=" + outMaxSize + " divisor=" + ndpOutDivisor
                    + " rem=" + ndpOutPayloadRemainder + " align=" + ndpOutAlignment
                    + " maxDatagrams=" + outMaxDatagrams;
        }
    }

    /* ---------------- 接管会话 ---------------- */

    public static final class Session implements NcmInitialization.Io {

        public final UsbDevice device;
        public final UsbDeviceConnection conn;

        public UsbInterface controlIface;
        public UsbInterface dataIface;
        public UsbInterface inactiveDataIface;
        public NcmDescriptor descriptor;
        public UsbEndpoint epIn;
        public UsbEndpoint epOut;
        public UsbEndpoint epInt;        // 控制接口上的中断 IN 端点（CDC 通知通道）
        public int dataAlt = -1;

        public int commIfNum = -1;
        public int dataIfNum = -1;
        public String ifaceFrom = "";       // 端点来源说明
        public String rawDump = "";
        public String sysfs = "";
        public Ntb ntb;

        public int maxPacketIn = 512;
        public int maxPacketOut = 512;

        private boolean closed;
        private boolean dataClaimed;
        private boolean controlClaimed;
        private boolean cleanupConfirmed = true;

        public Session(UsbDevice d, UsbDeviceConnection c) {
            device = d;
            conn = c;
        }

        public boolean isClosed() {
            return closed;
        }

        public boolean close() {
            if (closed) return cleanupConfirmed;
            closed = true;
            // Closing usbfs alone does not select alternate 0. Explicitly deactivate
            // bulk endpoints after pumps stop, including partially failed initialization.
            cleanupConfirmed = NcmRelease.finish(new NcmRelease.Steps() {
                @Override
                public boolean deactivateData() {
                    return !dataClaimed || inactiveDataIface != null && conn.setInterface(inactiveDataIface);
                }

                @Override
                public boolean releaseData() {
                    return !dataClaimed || conn.releaseInterface(dataIface);
                }

                @Override
                public boolean releaseControl() {
                    return !controlClaimed || conn.releaseInterface(controlIface);
                }

                @Override
                public void closeConnection() {
                    conn.close();
                }
            });
            LogHub.i("usb", "USB released; data-deactivation-confirmed=" + cleanupConfirmed
                    + "; instrument Host role not changed");
            return cleanupConfirmed;
        }

        @Override
        public void alternate(int setting) throws IOException {
            if (closed || !dataClaimed || !controlClaimed || (setting != 0 && setting != 1)) {
                throw new IOException("NCM alternate selection without both interface claims");
            }
            UsbInterface selected = setting == 0 ? inactiveDataIface : dataIface;
            boolean accepted = selected != null && conn.setInterface(selected);
            LogHub.i("usb", "SET_INTERFACE data_if=" + dataIfNum + " alt=" + setting + " -> " + accepted);
            if (!accepted) {
                throw new IOException("SET_INTERFACE alt=" + setting + " rejected; TCP/video not started");
            }
        }

        @Override
        public int control(int type, int request, int value, int index, byte[] buffer, int timeoutMs)
                throws IOException {
            if (closed || !controlClaimed || index != commIfNum || timeoutMs < 1 || timeoutMs > 1000) {
                throw new IOException("Invalid NCM class request ownership/index/timeout");
            }
            try {
                return conn.controlTransfer(type, request, value, index, buffer,
                        buffer == null ? 0 : buffer.length, timeoutMs);
            } catch (RuntimeException error) {
                throw new IOException("NCM control request 0x" + Integer.toHexString(request) + " failed", error);
            }
        }

        /* ---- 控制请求 ---- */

        public int ctrlOut(int req, int value, int index, byte[] data) {
            int len = data == null ? 0 : data.length;
            try {
                return conn.controlTransfer(0x21, req, value, index, data, len, 1000);
            } catch (Throwable t) {
                LogHub.throwable("usb", "控制传输异常 req=0x" + Integer.toHexString(req), t);
                return -1000;
            }
        }

        public int ctrlIn(int req, int value, int index, byte[] data) {
            int len = data == null ? 0 : data.length;
            try {
                return conn.controlTransfer(0xA1, req, value, index, data, len, 1000);
            } catch (Throwable t) {
                LogHub.throwable("usb", "控制传输异常 req=0x" + Integer.toHexString(req), t);
                return -1000;
            }
        }

        /** 读 NTB 参数；index 先试数据接口，失败再试控制接口。返回 null 表示设备未实现。 */
        public Ntb getNtbParameters() {
            int[] indexes = indexesToTry();
            for (int idx : indexes) {
                byte[] buf = new byte[28];
                int r = ctrlIn(REQ_GET_NTB_PARAMETERS, 0, idx, buf);
                LogHub.i("ncm", "GET_NTB_PARAMETERS index=" + idx + " -> " + r);
                if (r >= 28) {
                    Ntb p = new Ntb();
                    p.formats = u16(buf, 2);
                    p.inMaxSize = (int) u32(buf, 4);
                    p.ndpInDivisor = u16(buf, 8);
                    p.ndpInPayloadRemainder = u16(buf, 10);
                    p.ndpInAlignment = u16(buf, 12);
                    p.outMaxSize = (int) u32(buf, 16);
                    p.ndpOutDivisor = u16(buf, 20);
                    p.ndpOutPayloadRemainder = u16(buf, 22);
                    p.ndpOutAlignment = u16(buf, 24);
                    p.outMaxDatagrams = u16(buf, 26);
                    ntb = p;
                    return p;
                }
            }
            return null;
        }

        public String readNtbInputSize() {
            for (int idx : indexesToTry()) {
                byte[] buf = new byte[8];
                int r = ctrlIn(REQ_GET_NTB_INPUT_SIZE, 0, idx, buf);
                LogHub.i("ncm", "GET_NTB_INPUT_SIZE index=" + idx + " -> " + r);
                if (r >= 8) {
                    return "dwNtbInputSize=" + u32(buf, 0)
                            + " wNtbInMaxDatagrams=" + u16(buf, 4)
                            + " wReserved=" + u16(buf, 6);
                }
            }
            return null;
        }

        public String writeNtbInputSize(int ntbSize, int maxDatagram, int align) {
            byte[] b = new byte[8];
            put32(b, 0, ntbSize);          // dwNtbInMaxSize
            put16(b, 4, maxDatagram);      // wNtbInMaxDatagrams
            put16(b, 6, align);            // wReserved
            for (int idx : indexesToTry()) {
                int r = ctrlOut(REQ_SET_NTB_INPUT_SIZE, 0, idx, b);
                LogHub.i("ncm", "SET_NTB_INPUT_SIZE index=" + idx
                        + " size=" + ntbSize + " dg=" + maxDatagram + " align=" + align
                        + " -> " + r);
                if (r == b.length) return "OK (index=" + idx + ", input-size=8B)";
            }
            // Four-byte NCM 1.0 form if the extended max-datagram form is unsupported.
            byte[] basic = new byte[4];
            put32(basic, 0, ntbSize);
            for (int idx : indexesToTry()) {
                int r = ctrlOut(REQ_SET_NTB_INPUT_SIZE, 0, idx, basic);
                LogHub.i("ncm", "SET_NTB_INPUT_SIZE basic 4B index=" + idx + " -> " + r);
                if (r == basic.length) return "OK (index=" + idx + ", input-size=4B)";
            }
            return "设备未实现/拒绝";
        }

        public String setNtbFormat(int format) {
            for (int idx : indexesToTry()) {
                int r = ctrlOut(REQ_SET_NTB_FORMAT, format, idx, null);
                LogHub.i("ncm", "SET_NTB_FORMAT index=" + idx + " format=" + format + " -> " + r);
                if (r == 0) return "OK (index=" + idx + ")";
            }
            return "设备未实现/拒绝";
        }

        public String readCrcMode() {
            for (int idx : indexesToTry()) {
                byte[] buf = new byte[2];
                int r = ctrlIn(REQ_GET_CRC_MODE, 0, idx, buf);
                LogHub.i("ncm", "GET_CRC_MODE index=" + idx + " -> " + r);
                if (r >= 2) {
                    int m = u16(buf, 0);
                    return "crcMode=" + m + (m == 0 ? " (不追加 CRC)" : " (追加 CRC)");
                }
            }
            return null;
        }

        /** CRC 模式 0 = 不追加。NOCRC 的 NDP 签名是 "NCM0"(0x304D434E)，本 App 按此组帧。 */
        public String writeCrcMode(int mode) {
            for (int idx : indexesToTry()) {
                int r = ctrlOut(REQ_SET_CRC_MODE, mode, idx, null);
                LogHub.i("ncm", "SET_CRC_MODE index=" + idx + " mode=" + mode + " -> " + r);
                if (r == 0) return "OK (index=" + idx + ")";
            }
            return "设备未实现/拒绝";
        }

        public String readMaxDatagramSize() {
            for (int idx : indexesToTry()) {
                byte[] buf = new byte[2];
                int r = ctrlIn(REQ_GET_MAX_DATAGRAM_SIZE, 0, idx, buf);
                LogHub.i("ncm", "GET_MAX_DATAGRAM_SIZE index=" + idx + " -> " + r);
                if (r >= 2) return "wMaxDatagramSize=" + u16(buf, 0);
            }
            return null;
        }

        public String setPacketFilter(int filter) {
            StringBuilder sb = new StringBuilder();
            for (int idx : indexesToTry()) {
                int r = ctrlOut(REQ_SET_ETHERNET_PACKET_FILTER, filter, idx, null);
                sb.append("index=").append(idx).append("->").append(r).append(' ');
                LogHub.i("ncm", "SET_ETHERNET_PACKET_FILTER index=" + idx
                        + " value=0x" + Integer.toHexString(filter) + " -> " + r);
                if (r == 0) return "OK (" + sb.toString().trim() + ")";
            }
            return "均失败 (" + sb.toString().trim() + ")";
        }

        public String getStatistic(int selector, String name) {
            for (int idx : indexesToTry()) {
                byte[] buf = new byte[4];
                int r = ctrlIn(REQ_GET_ETHERNET_STATISTIC, selector, idx, buf);
                LogHub.i("ncm", "GET_ETHERNET_STATISTIC " + name + " sel=0x"
                        + Integer.toHexString(selector) + " index=" + idx + " -> " + r);
                if (r >= 4) return name + " = " + u32(buf, 0);
            }
            return name + " 不可读";
        }

        public String readNtbFormat() {
            for (int idx : indexesToTry()) {
                byte[] buf = new byte[2];
                int r = ctrlIn(REQ_GET_NTB_FORMAT, 0, idx, buf);
                LogHub.i("ncm", "GET_NTB_FORMAT index=" + idx + " -> " + r);
                if (r >= 2) {
                    int f = u16(buf, 0);
                    return "format=" + f + (f == 0 ? " (NTB-16)" : " (NTB-32)");
                }
            }
            return null;
        }

        private int[] indexesToTry() {
            if (commIfNum >= 0) return new int[]{commIfNum};
            return new int[0];
        }

        /** 一键跑完所有 NCM 控制请求，返回可读报告。 */
        public String fullControlReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("── NCM 控制请求逐条结果 ──\n");
            Ntb p = getNtbParameters();
            sb.append("GET_NTB_PARAMETERS(0x80): ")
                    .append(p == null ? "无响应/长度不足" : "OK\n  " + p.toString()).append('\n');
            String fmt = readNtbFormat();
            sb.append("GET_NTB_FORMAT(0x83): ")
                    .append(fmt == null ? "无响应" : fmt).append('\n');
            String crc = readCrcMode();
            sb.append("GET_CRC_MODE(0x89): ")
                    .append(crc == null ? "无响应" : crc).append('\n');
            String dg = readMaxDatagramSize();
            sb.append("GET_MAX_DATAGRAM_SIZE(0x87): ")
                    .append(dg == null ? "无响应" : dg).append('\n');
            String inSize = readNtbInputSize();
            sb.append("GET_NTB_INPUT_SIZE(0x85): ")
                    .append(inSize == null ? "无响应" : inSize).append('\n');

            int want = p != null && p.inMaxSize >= 2048
                    ? (int) Math.min(p.inMaxSize, 65536) : 16384;
            sb.append("SET_NTB_INPUT_SIZE(0x86, ").append(want).append("): ")
                    .append(writeNtbInputSize(Math.min(want, 65535), 8, 0)).append('\n');
            sb.append("SET_NTB_FORMAT(0x84, NTB-16): ")
                    .append(setNtbFormat(0)).append('\n');
            sb.append("SET_CRC_MODE(0x8a, 不追加): ")
                    .append(writeCrcMode(0)).append('\n');
            sb.append("SET_ETHERNET_PACKET_FILTER(0x43, 0x0F=PROMISCUOUS|ALL_MULTICAST|DIRECTED|BROADCAST): ")
                    .append(setPacketFilter(PKT_PROMISCUOUS | PKT_ALL_MULTICAST
                            | PKT_DIRECTED | PKT_BROADCAST)).append('\n');
            sb.append(getStatistic(0x0000, "XMIT_OK")).append('\n');
            sb.append(getStatistic(0x0001, "RCV_OK")).append('\n');
            sb.append(getStatistic(0x0002, "XMIT_ERROR")).append('\n');
            return sb.toString();
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    /* ---------------- 打开 + 接管 ---------------- */

    public static Session open(Context c, UsbDevice dev) throws Exception {
        UsbManager m = mgr(c);
        if (m == null) throw new Exception("UsbManager 不可用");
        if (!m.hasPermission(dev)) throw new Exception("尚未获得 USB 授权，请先点「申请授权」");

        UsbDeviceConnection conn = m.openDevice(dev);
        if (conn == null) throw new Exception("openDevice 返回 null（授权被撤销或设备已拔出？）");

        Session s = new Session(dev, conn);
        LogHub.banner("USB 接管");
        LogHub.i("usb", "打开 " + dev.getDeviceName()
                + String.format(Locale.US, "  %04x:%04x", dev.getVendorId(), dev.getProductId()));

        byte[] raw = null;
        try {
            raw = conn.getRawDescriptors();
        } catch (Throwable t) {
            LogHub.throwable("usb", "getRawDescriptors 失败", t);
        }
        s.rawDump = UsbDesc.dump(raw);
        LogHub.i("usb", "描述符树：\n" + s.rawDump);

        // Firmware validation only: no sysfs configuration or diagnostic side effects.
        s.sysfs = "";

        try {
            s.descriptor = NcmDescriptor.parse(raw);
            pickInterfaces(s, dev, raw);
        } catch (Exception e) {
            if (!s.close()) {
                e.addSuppressed(new IOException("USB data cleanup unconfirmed; unplug before role change"));
            }
            throw e;
        }
        return s;
    }

    private static void pickInterfaces(Session s, UsbDevice dev, byte[] raw) throws Exception {
        List<UsbInterface> all = new ArrayList<UsbInterface>();
        for (int i = 0; i < dev.getInterfaceCount(); i++) all.add(dev.getInterface(i));

        for (UsbInterface f : all) {
            if (f.getId() == s.descriptor.controlInterface && f.getInterfaceClass() == 0x02
                    && f.getInterfaceSubclass() == 13 && s.controlIface == null) {
                s.controlIface = f;
                s.commIfNum = f.getId();
            }
        }

        /* 第 1 优先：框架直接给了带 bulk 的接口 */
        UsbInterface data = null;
        for (UsbInterface f : all) {
            if (f.getId() == s.descriptor.dataInterface && f.getAlternateSetting() == 1
                    && f.getInterfaceClass() == 0x0A && bulkPair(f) != null) {
                data = f;
                break;
            }
        }

        if (data != null) {
            UsbEndpoint[] pair = bulkPair(data);
            s.dataIface = data;
            s.dataIfNum = data.getId();
            s.dataAlt = data.getAlternateSetting();
            s.epIn = pair[0];
            s.epOut = pair[1];
            s.ifaceFrom = "框架对象 (if" + data.getId() + "/alt" + data.getAlternateSetting() + ")";
            LogHub.i("usb", "数据接口来源=" + s.ifaceFrom
                    + "  cls=0x" + Integer.toHexString(data.getInterfaceClass()));
        } else {
            /* 第 2 优先：从原始描述符里找 alt N（NCM 常见 alt 1）上的 bulk 对 */
            LogHub.w("usb", "框架未暴露带 bulk 端点的接口，改从原始描述符定位交替设置");
            int ifNum = s.descriptor.dataInterface;

            int altFound = -1;
            List<UsbDesc.Ep> eps = null;
            if (raw != null && ifNum >= 0) {
                for (int alt = 1; alt <= 1; alt++) {
                    if (!UsbDesc.hasAlt(raw, ifNum, alt)) continue;
                    List<UsbDesc.Ep> cand = UsbDesc.endpoints(raw, ifNum, alt);
                    UsbDesc.Ep in = null;
                    UsbDesc.Ep out = null;
                    for (UsbDesc.Ep e : cand) {
                        if (!e.isBulk()) continue;
                        if (e.isIn() && in == null) in = e;
                        if (!e.isIn() && out == null) out = e;
                    }
                    if (in != null && out != null) {
                        altFound = alt;
                        eps = cand;
                        s.epIn = makeEndpoint(in.address, in.attributes, in.maxPacket, in.interval);
                        s.epOut = makeEndpoint(out.address, out.attributes, out.maxPacket, out.interval);
                        break;
                    }
                }
            }

            if (altFound < 0 || s.epIn == null || s.epOut == null) {
                throw new Exception("未找到任何带 bulk 收发对的接口。\n"
                        + "描述符树：\n" + s.rawDump
                        + "\n框架暴露的接口：\n" + describe(all));
            }

            s.dataIfNum = ifNum;
            s.dataAlt = altFound;
            s.ifaceFrom = "原始描述符 (if" + ifNum + "/alt" + altFound + ")";

            UsbInterface base = null;
            for (UsbInterface f : all) {
                if (f.getId() == ifNum) {
                    base = f;
                    break;
                }
            }
            UsbInterface altObj = makeInterface(ifNum, altFound,
                    base == null ? null : base.getName(),
                    0x0A, base == null ? 0 : base.getInterfaceSubclass(),
                    base == null ? 0 : base.getInterfaceProtocol());

            // 摘掉内核驱动（若 cdc_ncm/rndis_host 已绑定，不摘就抢不到）
            if (base != null) {
                boolean ok = false;
                try {
                    ok = s.conn.claimInterface(base, true);
                } catch (Throwable t) {
                    LogHub.throwable("usb", "claim(alt0, force) 异常", t);
                }
                LogHub.i("usb", "claimInterface(if" + ifNum + "/alt"
                        + base.getAlternateSetting() + ", force=true) -> " + ok);
                if (!ok) {
                    throw new Exception("Cannot claim base data interface");
                }
            }
            if (altObj == null) {
                throw new Exception("无法构造 alt" + altFound + " 的 UsbInterface 对象"
                        + "（本机禁止对被隐藏构造器反射）");
            }
            s.dataIface = altObj;
        }

        s.dataIface = (s.dataIface != null) ? s.dataIface : makeInterface(s.dataIfNum, s.dataAlt,
                null, 0x0A, 0, 0);
        if (s.dataIface == null) throw new Exception("无法构造数据接口对象");
        boolean claimed = false;
        try {
            claimed = s.conn.claimInterface(s.dataIface, true);
        } catch (Throwable t) {
            LogHub.throwable("usb", "claim 数据接口异常", t);
        }
        LogHub.i("usb", "claimInterface(数据接口 if" + s.dataIfNum + "/alt"
                + s.dataAlt + ", force=true) -> " + claimed);
        if (!claimed) {
            throw new Exception("claimInterface 失败：接口可能已被内核驱动占用且无法剥离，"
                    + "或设备不支持 usbfs 直连。\n描述符树：\n" + s.rawDump);
        }
        s.dataClaimed = true;
        for (UsbInterface item : all) {
            if (item.getId() == s.dataIfNum && item.getAlternateSetting() == 0) {
                s.inactiveDataIface = item;
                break;
            }
        }
        if (s.inactiveDataIface == null) {
            s.inactiveDataIface = makeInterface(s.dataIfNum, 0, null, 0x0a, 0, 1);
        }
        if (s.inactiveDataIface == null) {
            throw new IOException("Cannot represent NCM inactive data alternate");
        }

        s.maxPacketIn = s.epIn.getMaxPacketSize();
        s.maxPacketOut = s.epOut.getMaxPacketSize();
        LogHub.i("usb", "端点确定: IN " + s.epIn + " / OUT " + s.epOut
                + "  (来源 " + s.ifaceFrom + ")");

        if (s.dataIfNum != s.commIfNum) {
            UsbInterface comm = null;
            for (UsbInterface f : all) {
                if (f.getId() == s.commIfNum) {
                    comm = f;
                    break;
                }
            }
            if (comm != null) {
                s.controlIface = comm;
                LogHub.i("usb", "CDC 控制接口 if" + s.commIfNum
                        + " cls=0x" + Integer.toHexString(comm.getInterfaceClass())
                        + " sub=0x" + Integer.toHexString(comm.getInterfaceSubclass()));
                // ★ claim 控制接口：usbfs 规定 bulk/interrupt 传输必须先 claim
                //   该端点所属接口（内核 cdc_ncm 也是两个接口一起 claim 的）。
                try {
                    boolean cok = s.conn.claimInterface(comm, true);
                    LogHub.i("usb", "claimInterface(控制接口 if" + s.commIfNum
                            + ", force=true) -> " + cok);
                    if (!cok) {
                        throw new Exception("Cannot claim NCM control interface");
                    }
                    s.controlClaimed = true;
                } catch (RuntimeException t) {
                    throw new Exception("NCM control interface unavailable", t);
                }
                // 找中断 IN 端点（CDC 通知通道，NCM 0x82）
                for (int i = 0; i < comm.getEndpointCount(); i++) {
                    UsbEndpoint e = comm.getEndpoint(i);
                    if (e.getDirection() == UsbConstants.USB_DIR_IN
                            && (e.getAttributes() & UsbConstants.USB_ENDPOINT_XFERTYPE_MASK)
                                    == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        s.epInt = e;
                        break;
                    }
                }
                if (s.epInt != null) {
                    LogHub.i("usb", "中断通知端点: " + hexEp(s.epInt)
                            + " mps=" + s.epInt.getMaxPacketSize()
                            + "（内核驱动 open 时必挂此端点读，缺少它设备可能不发数据）");
                } else {
                    LogHub.w("usb", "控制接口上未找到中断 IN 端点");
                }
            }
        }
        if (!s.controlClaimed) {
            throw new IOException("Associated NCM control interface not claimed");
        }
        LogHub.i("usb", "Both interfaces claimed; data activation deferred until capability negotiation");
    }

    private static String hexEp(UsbEndpoint e) {
        return "0x" + Integer.toHexString(e.getAddress()) + "("
                + ((e.getAttributes() & UsbConstants.USB_ENDPOINT_XFERTYPE_MASK)
                        == UsbConstants.USB_ENDPOINT_XFER_INT ? "intr" : "bulk") + ")";
    }

    private static String describe(List<UsbInterface> all) {
        StringBuilder sb = new StringBuilder();
        for (UsbInterface f : all) {
            sb.append("  if").append(f.getId()).append("/alt").append(f.getAlternateSetting())
                    .append(" cls=0x").append(Integer.toHexString(f.getInterfaceClass()))
                    .append(" eps=").append(f.getEndpointCount()).append('\n');
        }
        return sb.toString();
    }

    /** 返回 {in, out}，没有成对的 bulk 端点则返回 null。 */
    private static UsbEndpoint[] bulkPair(UsbInterface f) {
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int k = 0; k < f.getEndpointCount(); k++) {
            UsbEndpoint e = f.getEndpoint(k);
            if (e.getType() != 2) continue;
            // bEndpointAddress 的 bit7 = 方向（1=IN）。用 UsbConstants 常量会依赖
            // 不同 API 级别的可用性，直接判位最稳。
            boolean isIn = (e.getAddress() & 0x80) != 0;
            if (isIn && in == null) in = e;
            if (!isIn && out == null) out = e;
        }
        return (in != null && out != null) ? new UsbEndpoint[]{in, out} : null;
    }

    /* ---------------- 反射构造（隐藏 API 兜底） ---------------- */

    private static UsbEndpoint makeEndpoint(int addr, int attrs, int maxPacket, int interval) {
        try {
            Class<?> cls = Class.forName("android.hardware.usb.UsbEndpoint");
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            UsbEndpoint ep = (UsbEndpoint) ctor.newInstance(addr, attrs, maxPacket, interval);
            LogHub.i("usb", "反射构造端点成功: " + ep);
            return ep;
        } catch (Throwable t) {
            LogHub.throwable("usb", "反射构造 UsbEndpoint 失败（本机屏蔽了该隐藏构造器）", t);
            return null;
        }
    }

    private static UsbInterface makeInterface(int id, int alt, String name,
                                              int cls, int sub, int proto) {
        try {
            Class<?> k = Class.forName("android.hardware.usb.UsbInterface");
            Constructor<?> ctor = k.getDeclaredConstructor(int.class, int.class, String.class,
                    int.class, int.class, int.class);
            ctor.setAccessible(true);
            UsbInterface f = (UsbInterface) ctor.newInstance(id, alt, name, cls, sub, proto);
            LogHub.i("usb", "反射构造接口成功: if" + id + "/alt" + alt);
            return f;
        } catch (Throwable t) {
            LogHub.w("usb", "反射构造 UsbInterface 失败: " + t);
            return null;
        }
    }

    /* ---------------- 便于界面显示的辅助 ---------------- */

    /** USB 速度等级，读 sysfs 得到；读不到就返回 null。 */
    public static String speedOf(UsbDevice d) {
        try {
            String[] bd = busDevOf(d).split("/");
            int bus = Integer.parseInt(bd[0]);
            int devNum = Integer.parseInt(bd[1]);
            File dir = new File("/sys/bus/usb/devices");
            File[] list = dir.listFiles();
            if (list == null) return null;
            for (File f : list) {
                if (!f.isDirectory()) continue;
                String busnum = readTrim(new File(f, "busnum"));
                String devnum = readTrim(new File(f, "devnum"));
                if (busnum == null || devnum == null) continue;
                if (Integer.parseInt(busnum) == bus && Integer.parseInt(devnum) == devNum) {
                    String sp = readTrim(new File(f, "speed"));
                    return sp == null ? null : sp + " Mbps";
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readTrim(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[64];
                int n = in.read(b);
                if (n <= 0) return null;
                return new String(b, 0, n, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
