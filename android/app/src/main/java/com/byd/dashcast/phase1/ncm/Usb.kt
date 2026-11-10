package com.byd.dashcast.phase1.ncm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * USB 设备发现 / 运行时授权 / 接口接管 / CDC 控制请求。
 *
 * **权限边界（刻意如此）：**不使用 root、su、系统签名，不申请任何系统级网络配置权限。
 * 设备访问完全依赖 [UsbManager] 的运行时授权弹窗（用户点一下「允许」）。
 *
 * **接管策略（按优先级）：**
 *  1. 框架已暴露带 bulk 端点的接口 → 直接用；
 *  2. 框架只暴露 alt 0（零端点），真正端点藏在 alt 1 → 从原始描述符定位 alt 1，
 *     用 `force=true` 摘掉内核驱动后 `SET_INTERFACE` 切过去；
 *  3. 都失败 → 输出完整描述符树供进一步分析，不静默。
 *
 * 第 2 条是 CDC-ECM 风格 NCM 设备在 Android 上的典型坑，也是本 App 存在的理由之一。
 */
object Usb {

    const val ACTION_PERM = "com.byd.dashcast.phase1.debug.USB_PERMISSION"
    const val EXTRA_GRANTED = UsbManager.EXTRA_PERMISSION_GRANTED
    const val EXTRA_DEVICE = UsbManager.EXTRA_DEVICE

    /* ---------------- 枚举 ---------------- */

    class Dev {
        @JvmField var device: UsbDevice? = null
        @JvmField var vid = 0
        @JvmField var pid = 0
        @JvmField var name: String? = null
        @JvmField var manufacturer: String? = null
        @JvmField var product: String? = null
        @JvmField var granted = false
        @JvmField var classify: String? = null
        @JvmField var likelyNcm = false
        @JvmField var hasStorage = false   // 含 0x08 大容量存储面（TF 卡/OTA）
        @JvmField var ifaceSummary: String? = null
        @JvmField var busDev: String? = null      // 001/002
        @JvmField var mac: String? = null         // 固件写死的 MAC（来自 CDC Ethernet 功能描述符 iMACAddress）

        fun label(): String = String.format(Locale.US, "%04x:%04x", vid, pid) + "  " + describeProduct()

        fun describeProduct(): String {
            val sb = StringBuilder()
            val maker = manufacturer
            val model = product
            if (maker != null && maker.isNotEmpty()) sb.append(maker).append(' ')
            if (model != null && model.isNotEmpty()) sb.append(model)
            else sb.append(name)
            return javaTrim(sb.toString())
        }

        fun sub(): String {
            val sb = StringBuilder()
            sb.append(classify)
            if (granted) sb.append(" · 已授权")
            else sb.append(" · 未授权")
            if (busDev != null) sb.append(" · ").append(busDev)
            return sb.toString()
        }
    }

    @JvmStatic
    fun mgr(c: Context): UsbManager? =
        c.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager?

    @JvmStatic
    fun enumerate(c: Context): List<Dev> {
        val out = ArrayList<Dev>()
        val m = mgr(c)
        if (m == null) {
            LogHub.e("usb", "UsbManager 为 null——本机不支持 USB Host？")
            return out
        }
        val map: Map<String, UsbDevice> = try {
            m.deviceList ?: LinkedHashMap()
        } catch (t: Throwable) {
            LogHub.throwable("usb", "getDeviceList 失败", t)
            return out
        }
        for (d in map.values) {
            val v = Dev()
            v.device = d
            v.vid = d.vendorId
            v.pid = d.productId
            v.name = safe(d.deviceName)
            v.manufacturer = safe(d.manufacturerName)
            v.product = safe(d.productName)
            v.granted = m.hasPermission(d)
            v.busDev = busDevOf(d)
            v.ifaceSummary = ifaceSummary(d)
            // 已授权的设备顺手从描述符读固件 MAC（不依赖 usb0 / 不调系统命令）
            if (v.granted) {
                v.mac = firmwareMac(c, d)
            }
            classify(v, d)
            out.add(v)
        }
        return out
    }

    /**
     * 从 CDC Ethernet 功能描述符的 iMACAddress 读固件写死的 MAC。
     * 全程只做一次标准 GET_DESCRIPTOR(string) 控制传输，不需要 root、不需要 usb0。
     * 返回的字符串形如 "02:00:00:00:07:02"，读不到则返回 null。
     */
    @JvmStatic
    fun firmwareMac(c: Context, d: UsbDevice?): String? {
        if (d == null) return null
        val m = mgr(c) ?: return null
        var conn: UsbDeviceConnection? = null
        try {
            conn = m.openDevice(d)
            if (conn == null) return null
            val raw = conn.rawDescriptors
            val idx = UsbDesc.ethernetMacIndex(raw)
            if (idx <= 0) return null
            val buf = ByteArray(255)
            val r = conn.controlTransfer(0x80, 0x06,
                (0x0300 or (idx and 0xFF)) and 0xFFFF, 0x0409, buf, buf.size, 1000)
            if (r < 4) return null
            val s = parseUsbString(buf, r)
            return normalizeMac(s)
        } catch (t: Throwable) {
            return null
        } finally {
            if (conn != null) {
                try {
                    conn.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun parseUsbString(buf: ByteArray, len: Int): String? {
        if (len < 2 || buf[1].toInt() != 0x03) return null
        val n = (buf[0].toInt() and 0xFF) - 2
        if (n < 0) return null
        val sb = StringBuilder()
        var k = 0
        while (k < n) {
            if (3 + k >= len) break
            val lo = buf[2 + k].toInt() and 0xFF
            val hi = buf[3 + k].toInt() and 0xFF
            sb.append((lo or (hi shl 8)).toChar())
            k += 2
        }
        return sb.toString()
    }

    /** 把 "020000000702" / "02-00-00-00-07-02" 归一化成 "02:00:00:00:07:02"。 */
    private fun normalizeMac(s: String?): String? {
        if (s == null) return null
        val hex = StringBuilder()
        for (ch in s) {
            if ((ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')) {
                hex.append(Character.toLowerCase(ch))
            }
        }
        if (hex.length != 12) return s
        val out = StringBuilder()
        var i = 0
        while (i < 12) {
            if (i > 0) out.append(':')
            out.append(hex, i, i + 2)
            i += 2
        }
        return out.toString()
    }

    private fun safe(s: String?): String = s ?: ""

    /** "/dev/bus/usb/001/002" -> "001/002" */
    private fun busDevOf(d: UsbDevice): String? {
        val n = d.deviceName ?: return null
        val i = n.lastIndexOf('/')
        if (i <= 0) return null
        val j = n.lastIndexOf('/', i - 1)
        if (j < 0) return n
        return n.substring(j + 1)
    }

    private fun classify(v: Dev, d: UsbDevice) {
        var ctrl = false
        var data = false
        var storage = false
        var anybulk = false
        val devCls = d.deviceClass
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            val cl = f.interfaceClass
            if (cl == 0x02) ctrl = true
            if (cl == 0x0A) data = true
            if (cl == 0x08) storage = true
            for (k in 0 until f.endpointCount) {
                val e = f.getEndpoint(k)
                if (e.type == 2) anybulk = true
            }
        }
        v.hasStorage = storage
        val ncm = (ctrl && data) || data
        v.likelyNcm = ncm

        if (ncm && storage) {
            v.classify = "复合设备：NCM 数据口（投屏/仪表）+ 大容量存储面（很可能是 OTA/TF 卡）"
        } else if (ncm) {
            v.classify = "NCM/CDC 数据设备（仪表通信口）"
        } else if (storage) {
            v.classify = "大容量存储（TF 卡/读卡器/OTA）— 无 NCM，不应接管"
            v.likelyNcm = false
        } else if (devCls == 3) {
            v.classify = "HID"
        } else if (devCls == 239 || devCls == 0) {
            v.classify = if (anybulk) "复合设备（含 bulk 端点，接口类未识别为 NCM）" else "复合设备"
        } else if (devCls == 255) {
            v.classify = "厂商自定义（潜在控制面）"
        } else {
            v.classify = "class=0x" + Integer.toHexString(devCls)
        }
        if (v.vid == 0x04FC) v.classify += " · VID 匹配原对接参数 04fc"
    }

    private fun ifaceSummary(d: UsbDevice): String {
        val sb = StringBuilder()
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (i > 0) sb.append(" | ")
            sb.append("if").append(f.id)
                .append("/alt").append(f.alternateSetting)
                .append(" cls").append(String.format(Locale.US, "%02x", f.interfaceClass))
                .append(" sub").append(String.format(Locale.US, "%02x", f.interfaceSubclass))
                .append(" ep").append(f.endpointCount)
        }
        return sb.toString()
    }

    /* ---------------- 运行时授权 ---------------- */

    @JvmStatic
    fun registerPermReceiver(c: Context, r: BroadcastReceiver) {
        try {
            val f = IntentFilter(ACTION_PERM)
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                c.registerReceiver(r, f)
            }
        } catch (t: Throwable) {
            LogHub.throwable("usb", "注册授权广播失败", t)
        }
    }

    @JvmStatic
    fun unregisterPermReceiver(c: Context, r: BroadcastReceiver) {
        try {
            c.unregisterReceiver(r)
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun requestPermission(c: Context, d: UsbDevice?) {
        val m = mgr(c)
        if (m == null || d == null) return
        val i = Intent(ACTION_PERM)
        i.setPackage(c.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pi = PendingIntent.getBroadcast(c, 0, i, flags)
        LogHub.i("usb", "弹窗申请 USB 授权: " + d.deviceName)
        try {
            m.requestPermission(d, pi)
        } catch (t: Throwable) {
            LogHub.throwable("usb", "requestPermission 失败", t)
        }
    }

    @JvmStatic
    fun hasPermission(c: Context, d: UsbDevice?): Boolean {
        val m = mgr(c)
        return m != null && d != null && m.hasPermission(d)
    }

    /* ---------------- NCM 控制请求常量 ---------------- */

    /*
     * 取值来源：linux/include/uapi/linux/usb/cdc.h（内核权威定义）。
     * 注意 0x81/0x82 是 GET/SET_NET_ADDRESS（NCM 1.1 新增），**不是** NTB_FORMAT——
     * 常见实现按 0x80 起顺推，会把 NTB 系列全部错位一格，必须避开。
     */
    const val REQ_SET_ETHERNET_PACKET_FILTER = 0x43
    const val REQ_GET_ETHERNET_STATISTIC = 0x44
    const val REQ_GET_NTB_PARAMETERS = 0x80
    const val REQ_GET_NTB_FORMAT = 0x83
    const val REQ_SET_NTB_FORMAT = 0x84
    const val REQ_GET_NTB_INPUT_SIZE = 0x85
    const val REQ_SET_NTB_INPUT_SIZE = 0x86
    const val REQ_GET_MAX_DATAGRAM_SIZE = 0x87
    const val REQ_SET_MAX_DATAGRAM_SIZE = 0x88
    const val REQ_GET_CRC_MODE = 0x89
    const val REQ_SET_CRC_MODE = 0x8a

    /* CDC 以太网包过滤位（cdc.h：PROMISCUOUS/ALL_MULTICAST/DIRECTED/BROADCAST/MULTICAST） */
    const val PKT_PROMISCUOUS = 1
    const val PKT_ALL_MULTICAST = 2
    const val PKT_DIRECTED = 4
    const val PKT_BROADCAST = 8
    const val PKT_MULTICAST = 16

    /** GET_NTB_PARAMETERS 的返回结构。 */
    class Ntb {
        @JvmField var formats = 0
        @JvmField var inMaxSize = 0
        @JvmField var ndpInDivisor = 0
        @JvmField var ndpInPayloadRemainder = 0
        @JvmField var ndpInAlignment = 0
        @JvmField var outMaxSize = 0
        @JvmField var ndpOutDivisor = 0
        @JvmField var ndpOutPayloadRemainder = 0
        @JvmField var ndpOutAlignment = 0
        @JvmField var outMaxDatagrams = 0

        override fun toString(): String =
            "formats=" + formats + (if (formats == 1) " (仅 NTB-16)" else "") +
                "\n  IN : maxSize=" + inMaxSize + " divisor=" + ndpInDivisor +
                " rem=" + ndpInPayloadRemainder + " align=" + ndpInAlignment +
                "\n  OUT: maxSize=" + outMaxSize + " divisor=" + ndpOutDivisor +
                " rem=" + ndpOutPayloadRemainder + " align=" + ndpOutAlignment +
                " maxDatagrams=" + outMaxDatagrams
    }

    /* ---------------- 接管会话 ---------------- */

    class Session(@JvmField val device: UsbDevice, @JvmField val conn: UsbDeviceConnection) : NcmInitialization.Io {

        @JvmField var controlIface: UsbInterface? = null
        @JvmField var dataIface: UsbInterface? = null
        @JvmField var inactiveDataIface: UsbInterface? = null
        @JvmField var descriptor: NcmDescriptor? = null
        @JvmField var epIn: UsbEndpoint? = null
        @JvmField var epOut: UsbEndpoint? = null
        @JvmField var epInt: UsbEndpoint? = null        // 控制接口上的中断 IN 端点（CDC 通知通道）
        @JvmField var dataAlt = -1

        @JvmField var commIfNum = -1
        @JvmField var dataIfNum = -1
        @JvmField var ifaceFrom = ""       // 端点来源说明
        @JvmField var rawDump = ""
        @JvmField var sysfs = ""
        @JvmField var ntb: Ntb? = null
        @JvmField var maxPacketIn = 512
        @JvmField var maxPacketOut = 512

        private var closed = false
        internal var dataClaimed = false
        internal var controlClaimed = false
        private var cleanupConfirmed = true
        private var connectionClosed = false

        /** Local descriptor evidence only; not an alternate-setting or remote-role acknowledgement. */
        fun connectionClosed(): Boolean = connectionClosed

        fun isClosed(): Boolean = closed

        fun close(): Boolean {
            if (closed) return cleanupConfirmed
            closed = true
            // Closing usbfs alone does not select alternate 0. Explicitly deactivate
            // bulk endpoints after pumps stop, including partially failed initialization.
            cleanupConfirmed = NcmRelease.finish(object : NcmRelease.Steps {
                override fun deactivateData(): Boolean {
                    val inactive = inactiveDataIface
                    return !dataClaimed || (inactive != null && conn.setInterface(inactive))
                }

                override fun releaseData(): Boolean = !dataClaimed || conn.releaseInterface(dataIface)

                override fun releaseControl(): Boolean = !controlClaimed || conn.releaseInterface(controlIface)

                override fun closeConnection() {
                    conn.close()
                    connectionClosed = true
                }
            })
            LogHub.i("usb", "USB released; data-deactivation-confirmed=" + cleanupConfirmed +
                "; instrument Host role not changed")
            return cleanupConfirmed
        }

        @Throws(IOException::class)
        override fun alternate(setting: Int) {
            if (closed || !dataClaimed || !controlClaimed || (setting != 0 && setting != 1)) {
                throw IOException("NCM alternate selection without both interface claims")
            }
            val selected = if (setting == 0) inactiveDataIface else dataIface
            val accepted = selected != null && conn.setInterface(selected)
            LogHub.i("usb", "SET_INTERFACE data_if=" + dataIfNum + " alt=" + setting + " -> " + accepted)
            if (!accepted) {
                throw IOException("SET_INTERFACE alt=" + setting + " rejected; TCP/video not started")
            }
        }

        @Throws(IOException::class)
        override fun control(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, timeoutMs: Int): Int {
            if (closed || !controlClaimed || index != commIfNum || timeoutMs < 1 || timeoutMs > 1000) {
                throw IOException("Invalid NCM class request ownership/index/timeout")
            }
            try {
                return conn.controlTransfer(type, request, value, index, buffer,
                    buffer?.size ?: 0, timeoutMs)
            } catch (error: RuntimeException) {
                throw IOException("NCM control request 0x" + Integer.toHexString(request) + " failed", error)
            }
        }

        /* ---- 控制请求 ---- */

        fun ctrlOut(req: Int, value: Int, index: Int, data: ByteArray?): Int {
            val len = data?.size ?: 0
            return try {
                conn.controlTransfer(0x21, req, value, index, data, len, 1000)
            } catch (t: Throwable) {
                LogHub.throwable("usb", "控制传输异常 req=0x" + Integer.toHexString(req), t)
                -1000
            }
        }

        fun ctrlIn(req: Int, value: Int, index: Int, data: ByteArray?): Int {
            val len = data?.size ?: 0
            return try {
                conn.controlTransfer(0xA1, req, value, index, data, len, 1000)
            } catch (t: Throwable) {
                LogHub.throwable("usb", "控制传输异常 req=0x" + Integer.toHexString(req), t)
                -1000
            }
        }

        /** 读 NTB 参数；index 先试数据接口，失败再试控制接口。返回 null 表示设备未实现。 */
        fun getNtbParameters(): Ntb? {
            for (idx in indexesToTry()) {
                val buf = ByteArray(28)
                val r = ctrlIn(REQ_GET_NTB_PARAMETERS, 0, idx, buf)
                LogHub.i("ncm", "GET_NTB_PARAMETERS index=" + idx + " -> " + r)
                if (r >= 28) {
                    val p = Ntb()
                    p.formats = u16(buf, 2)
                    p.inMaxSize = u32(buf, 4).toInt()
                    p.ndpInDivisor = u16(buf, 8)
                    p.ndpInPayloadRemainder = u16(buf, 10)
                    p.ndpInAlignment = u16(buf, 12)
                    p.outMaxSize = u32(buf, 16).toInt()
                    p.ndpOutDivisor = u16(buf, 20)
                    p.ndpOutPayloadRemainder = u16(buf, 22)
                    p.ndpOutAlignment = u16(buf, 24)
                    p.outMaxDatagrams = u16(buf, 26)
                    ntb = p
                    return p
                }
            }
            return null
        }

        fun readNtbInputSize(): String? {
            for (idx in indexesToTry()) {
                val buf = ByteArray(8)
                val r = ctrlIn(REQ_GET_NTB_INPUT_SIZE, 0, idx, buf)
                LogHub.i("ncm", "GET_NTB_INPUT_SIZE index=" + idx + " -> " + r)
                if (r >= 8) {
                    return "dwNtbInputSize=" + u32(buf, 0) +
                        " wNtbInMaxDatagrams=" + u16(buf, 4) +
                        " wReserved=" + u16(buf, 6)
                }
            }
            return null
        }

        fun writeNtbInputSize(ntbSize: Int, maxDatagram: Int, align: Int): String {
            val b = ByteArray(8)
            put32(b, 0, ntbSize.toLong())  // dwNtbInMaxSize
            put16(b, 4, maxDatagram)       // wNtbInMaxDatagrams
            put16(b, 6, align)             // wReserved
            for (idx in indexesToTry()) {
                val r = ctrlOut(REQ_SET_NTB_INPUT_SIZE, 0, idx, b)
                LogHub.i("ncm", "SET_NTB_INPUT_SIZE index=" + idx +
                    " size=" + ntbSize + " dg=" + maxDatagram + " align=" + align +
                    " -> " + r)
                if (r == b.size) return "OK (index=" + idx + ", input-size=8B)"
            }
            // Four-byte NCM 1.0 form if the extended max-datagram form is unsupported.
            val basic = ByteArray(4)
            put32(basic, 0, ntbSize.toLong())
            for (idx in indexesToTry()) {
                val r = ctrlOut(REQ_SET_NTB_INPUT_SIZE, 0, idx, basic)
                LogHub.i("ncm", "SET_NTB_INPUT_SIZE basic 4B index=" + idx + " -> " + r)
                if (r == basic.size) return "OK (index=" + idx + ", input-size=4B)"
            }
            return "设备未实现/拒绝"
        }

        fun setNtbFormat(format: Int): String {
            for (idx in indexesToTry()) {
                val r = ctrlOut(REQ_SET_NTB_FORMAT, format, idx, null)
                LogHub.i("ncm", "SET_NTB_FORMAT index=" + idx + " format=" + format + " -> " + r)
                if (r == 0) return "OK (index=" + idx + ")"
            }
            return "设备未实现/拒绝"
        }

        fun readCrcMode(): String? {
            for (idx in indexesToTry()) {
                val buf = ByteArray(2)
                val r = ctrlIn(REQ_GET_CRC_MODE, 0, idx, buf)
                LogHub.i("ncm", "GET_CRC_MODE index=" + idx + " -> " + r)
                if (r >= 2) {
                    val m = u16(buf, 0)
                    return "crcMode=" + m + (if (m == 0) " (不追加 CRC)" else " (追加 CRC)")
                }
            }
            return null
        }

        /** CRC 模式 0 = 不追加。NOCRC 的 NDP 签名是 "NCM0"(0x304D434E)，本 App 按此组帧。 */
        fun writeCrcMode(mode: Int): String {
            for (idx in indexesToTry()) {
                val r = ctrlOut(REQ_SET_CRC_MODE, mode, idx, null)
                LogHub.i("ncm", "SET_CRC_MODE index=" + idx + " mode=" + mode + " -> " + r)
                if (r == 0) return "OK (index=" + idx + ")"
            }
            return "设备未实现/拒绝"
        }

        fun readMaxDatagramSize(): String? {
            for (idx in indexesToTry()) {
                val buf = ByteArray(2)
                val r = ctrlIn(REQ_GET_MAX_DATAGRAM_SIZE, 0, idx, buf)
                LogHub.i("ncm", "GET_MAX_DATAGRAM_SIZE index=" + idx + " -> " + r)
                if (r >= 2) return "wMaxDatagramSize=" + u16(buf, 0)
            }
            return null
        }

        fun setPacketFilter(filter: Int): String {
            val sb = StringBuilder()
            for (idx in indexesToTry()) {
                val r = ctrlOut(REQ_SET_ETHERNET_PACKET_FILTER, filter, idx, null)
                sb.append("index=").append(idx).append("->").append(r).append(' ')
                LogHub.i("ncm", "SET_ETHERNET_PACKET_FILTER index=" + idx +
                    " value=0x" + Integer.toHexString(filter) + " -> " + r)
                if (r == 0) return "OK (" + javaTrim(sb.toString()) + ")"
            }
            return "均失败 (" + javaTrim(sb.toString()) + ")"
        }

        fun getStatistic(selector: Int, name: String): String {
            for (idx in indexesToTry()) {
                val buf = ByteArray(4)
                val r = ctrlIn(REQ_GET_ETHERNET_STATISTIC, selector, idx, buf)
                LogHub.i("ncm", "GET_ETHERNET_STATISTIC " + name + " sel=0x" +
                    Integer.toHexString(selector) + " index=" + idx + " -> " + r)
                if (r >= 4) return name + " = " + u32(buf, 0)
            }
            return name + " 不可读"
        }

        fun readNtbFormat(): String? {
            for (idx in indexesToTry()) {
                val buf = ByteArray(2)
                val r = ctrlIn(REQ_GET_NTB_FORMAT, 0, idx, buf)
                LogHub.i("ncm", "GET_NTB_FORMAT index=" + idx + " -> " + r)
                if (r >= 2) {
                    val f = u16(buf, 0)
                    return "format=" + f + (if (f == 0) " (NTB-16)" else " (NTB-32)")
                }
            }
            return null
        }

        private fun indexesToTry(): IntArray =
            if (commIfNum >= 0) intArrayOf(commIfNum) else IntArray(0)

        /** 一键跑完所有 NCM 控制请求，返回可读报告。 */
        fun fullControlReport(): String {
            val sb = StringBuilder()
            sb.append("── NCM 控制请求逐条结果 ──\n")
            val p = getNtbParameters()
            sb.append("GET_NTB_PARAMETERS(0x80): ")
                .append(if (p == null) "无响应/长度不足" else "OK\n  " + p.toString()).append('\n')
            val fmt = readNtbFormat()
            sb.append("GET_NTB_FORMAT(0x83): ")
                .append(fmt ?: "无响应").append('\n')
            val crc = readCrcMode()
            sb.append("GET_CRC_MODE(0x89): ")
                .append(crc ?: "无响应").append('\n')
            val dg = readMaxDatagramSize()
            sb.append("GET_MAX_DATAGRAM_SIZE(0x87): ")
                .append(dg ?: "无响应").append('\n')
            val inSize = readNtbInputSize()
            sb.append("GET_NTB_INPUT_SIZE(0x85): ")
                .append(inSize ?: "无响应").append('\n')

            val want = if (p != null && p.inMaxSize >= 2048) minOf(p.inMaxSize, 65536) else 16384
            sb.append("SET_NTB_INPUT_SIZE(0x86, ").append(want).append("): ")
                .append(writeNtbInputSize(minOf(want, 65535), 8, 0)).append('\n')
            sb.append("SET_NTB_FORMAT(0x84, NTB-16): ")
                .append(setNtbFormat(0)).append('\n')
            sb.append("SET_CRC_MODE(0x8a, 不追加): ")
                .append(writeCrcMode(0)).append('\n')
            sb.append("SET_ETHERNET_PACKET_FILTER(0x43, 0x0F=PROMISCUOUS|ALL_MULTICAST|DIRECTED|BROADCAST): ")
                .append(setPacketFilter(PKT_PROMISCUOUS or PKT_ALL_MULTICAST or
                    PKT_DIRECTED or PKT_BROADCAST)).append('\n')
            sb.append(getStatistic(0x0000, "XMIT_OK")).append('\n')
            sb.append(getStatistic(0x0001, "RCV_OK")).append('\n')
            sb.append(getStatistic(0x0002, "XMIT_ERROR")).append('\n')
            return sb.toString()
        }
    }

    /** java.lang.String.trim semantics (every char <= U+0020), unlike Kotlin's whitespace trim. */
    private fun javaTrim(s: String): String = s.trim { it <= ' ' }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFFL) or ((b[o + 1].toLong() and 0xFFL) shl 8) or
            ((b[o + 2].toLong() and 0xFFL) shl 16) or ((b[o + 3].toLong() and 0xFFL) shl 24)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /* ---------------- 打开 + 接管 ---------------- */

    @JvmStatic
    @Throws(Exception::class)
    fun open(c: Context, dev: UsbDevice): Session {
        val m = mgr(c) ?: throw Exception("UsbManager 不可用")
        if (!m.hasPermission(dev)) throw Exception("尚未获得 USB 授权，请先点「申请授权」")

        val conn = m.openDevice(dev) ?: throw Exception("openDevice 返回 null（授权被撤销或设备已拔出？）")

        val s = Session(dev, conn)
        LogHub.banner("USB 接管")
        LogHub.i("usb", "打开 " + dev.deviceName +
            String.format(Locale.US, "  %04x:%04x", dev.vendorId, dev.productId))

        var raw: ByteArray? = null
        try {
            raw = conn.rawDescriptors
        } catch (t: Throwable) {
            LogHub.throwable("usb", "getRawDescriptors 失败", t)
        }
        s.rawDump = UsbDesc.dump(raw)
        LogHub.i("usb", "描述符树：\n" + s.rawDump)

        // Firmware validation only: no sysfs configuration or diagnostic side effects.
        s.sysfs = ""

        try {
            val descriptor = NcmDescriptor.parse(raw)
            s.descriptor = descriptor
            pickInterfaces(s, dev, raw, descriptor)
        } catch (e: Exception) {
            if (!s.close()) {
                e.addSuppressed(IOException("USB data cleanup unconfirmed; unplug before role change"))
            }
            throw e
        }
        return s
    }

    @Throws(Exception::class)
    private fun pickInterfaces(s: Session, dev: UsbDevice, raw: ByteArray?, descriptor: NcmDescriptor) {
        val all = ArrayList<UsbInterface>()
        for (i in 0 until dev.interfaceCount) all.add(dev.getInterface(i))

        for (f in all) {
            if (f.id == descriptor.controlInterface && f.interfaceClass == 0x02 &&
                f.interfaceSubclass == 13 && s.controlIface == null) {
                s.controlIface = f
                s.commIfNum = f.id
            }
        }

        /* 第 1 优先：框架直接给了带 bulk 的接口 */
        var data: UsbInterface? = null
        for (f in all) {
            if (f.id == descriptor.dataInterface && f.alternateSetting == 1 &&
                f.interfaceClass == 0x0A && bulkPair(f) != null) {
                data = f
                break
            }
        }

        if (data != null) {
            val pair = bulkPair(data)!!
            s.dataIface = data
            s.dataIfNum = data.id
            s.dataAlt = data.alternateSetting
            s.epIn = pair[0]
            s.epOut = pair[1]
            s.ifaceFrom = "框架对象 (if" + data.id + "/alt" + data.alternateSetting + ")"
            LogHub.i("usb", "数据接口来源=" + s.ifaceFrom +
                "  cls=0x" + Integer.toHexString(data.interfaceClass))
        } else {
            /* 第 2 优先：从原始描述符里找 alt N（NCM 常见 alt 1）上的 bulk 对 */
            LogHub.w("usb", "框架未暴露带 bulk 端点的接口，改从原始描述符定位交替设置")
            val ifNum = descriptor.dataInterface

            var altFound = -1
            if (raw != null && ifNum >= 0) {
                for (alt in 1..1) {
                    if (!UsbDesc.hasAlt(raw, ifNum, alt)) continue
                    val cand = UsbDesc.endpoints(raw, ifNum, alt)
                    var epIn: UsbDesc.Ep? = null
                    var epOut: UsbDesc.Ep? = null
                    for (e in cand) {
                        if (!e.isBulk()) continue
                        if (e.isIn() && epIn == null) epIn = e
                        if (!e.isIn() && epOut == null) epOut = e
                    }
                    if (epIn != null && epOut != null) {
                        altFound = alt
                        s.epIn = makeEndpoint(epIn.address, epIn.attributes, epIn.maxPacket, epIn.interval)
                        s.epOut = makeEndpoint(epOut.address, epOut.attributes, epOut.maxPacket, epOut.interval)
                        break
                    }
                }
            }

            if (altFound < 0 || s.epIn == null || s.epOut == null) {
                throw Exception("未找到任何带 bulk 收发对的接口。\n" +
                    "描述符树：\n" + s.rawDump +
                    "\n框架暴露的接口：\n" + describe(all))
            }

            s.dataIfNum = ifNum
            s.dataAlt = altFound
            s.ifaceFrom = "原始描述符 (if" + ifNum + "/alt" + altFound + ")"

            var base: UsbInterface? = null
            for (f in all) {
                if (f.id == ifNum) {
                    base = f
                    break
                }
            }
            val altObj = makeInterface(ifNum, altFound,
                base?.name,
                0x0A, base?.interfaceSubclass ?: 0,
                base?.interfaceProtocol ?: 0)

            // 摘掉内核驱动（若 cdc_ncm/rndis_host 已绑定，不摘就抢不到）
            if (base != null) {
                var ok = false
                try {
                    ok = s.conn.claimInterface(base, true)
                } catch (t: Throwable) {
                    LogHub.throwable("usb", "claim(alt0, force) 异常", t)
                }
                LogHub.i("usb", "claimInterface(if" + ifNum + "/alt" +
                    base.alternateSetting + ", force=true) -> " + ok)
                if (!ok) {
                    throw Exception("Cannot claim base data interface")
                }
            }
            if (altObj == null) {
                throw Exception("无法构造 alt" + altFound + " 的 UsbInterface 对象" +
                    "（本机禁止对被隐藏构造器反射）")
            }
            s.dataIface = altObj
        }

        s.dataIface = s.dataIface ?: makeInterface(s.dataIfNum, s.dataAlt, null, 0x0A, 0, 0)
        val dataIface = s.dataIface ?: throw Exception("无法构造数据接口对象")
        var claimed = false
        try {
            claimed = s.conn.claimInterface(dataIface, true)
        } catch (t: Throwable) {
            LogHub.throwable("usb", "claim 数据接口异常", t)
        }
        LogHub.i("usb", "claimInterface(数据接口 if" + s.dataIfNum + "/alt" +
            s.dataAlt + ", force=true) -> " + claimed)
        if (!claimed) {
            throw Exception("claimInterface 失败：接口可能已被内核驱动占用且无法剥离，" +
                "或设备不支持 usbfs 直连。\n描述符树：\n" + s.rawDump)
        }
        s.dataClaimed = true
        for (item in all) {
            if (item.id == s.dataIfNum && item.alternateSetting == 0) {
                s.inactiveDataIface = item
                break
            }
        }
        if (s.inactiveDataIface == null) {
            s.inactiveDataIface = makeInterface(s.dataIfNum, 0, null, 0x0a, 0, 1)
        }
        if (s.inactiveDataIface == null) {
            throw IOException("Cannot represent NCM inactive data alternate")
        }

        val epIn = s.epIn!!
        val epOut = s.epOut!!
        s.maxPacketIn = epIn.maxPacketSize
        s.maxPacketOut = epOut.maxPacketSize
        LogHub.i("usb", "端点确定: IN " + epIn + " / OUT " + epOut +
            "  (来源 " + s.ifaceFrom + ")")

        if (s.dataIfNum != s.commIfNum) {
            var comm: UsbInterface? = null
            for (f in all) {
                if (f.id == s.commIfNum) {
                    comm = f
                    break
                }
            }
            if (comm != null) {
                s.controlIface = comm
                LogHub.i("usb", "CDC 控制接口 if" + s.commIfNum +
                    " cls=0x" + Integer.toHexString(comm.interfaceClass) +
                    " sub=0x" + Integer.toHexString(comm.interfaceSubclass))
                // ★ claim 控制接口：usbfs 规定 bulk/interrupt 传输必须先 claim
                //   该端点所属接口（内核 cdc_ncm 也是两个接口一起 claim 的）。
                try {
                    val cok = s.conn.claimInterface(comm, true)
                    LogHub.i("usb", "claimInterface(控制接口 if" + s.commIfNum +
                        ", force=true) -> " + cok)
                    if (!cok) {
                        throw Exception("Cannot claim NCM control interface")
                    }
                    s.controlClaimed = true
                } catch (t: RuntimeException) {
                    throw Exception("NCM control interface unavailable", t)
                }
                // 找中断 IN 端点（CDC 通知通道，NCM 0x82）
                for (i in 0 until comm.endpointCount) {
                    val e = comm.getEndpoint(i)
                    if (e.direction == UsbConstants.USB_DIR_IN &&
                        (e.attributes and UsbConstants.USB_ENDPOINT_XFERTYPE_MASK) ==
                        UsbConstants.USB_ENDPOINT_XFER_INT) {
                        s.epInt = e
                        break
                    }
                }
                val epInt = s.epInt
                if (epInt != null) {
                    LogHub.i("usb", "中断通知端点: " + hexEp(epInt) +
                        " mps=" + epInt.maxPacketSize +
                        "（内核驱动 open 时必挂此端点读，缺少它设备可能不发数据）")
                } else {
                    LogHub.w("usb", "控制接口上未找到中断 IN 端点")
                }
            }
        }
        if (!s.controlClaimed) {
            throw IOException("Associated NCM control interface not claimed")
        }
        LogHub.i("usb", "Both interfaces claimed; data activation deferred until capability negotiation")
    }

    private fun hexEp(e: UsbEndpoint): String =
        "0x" + Integer.toHexString(e.address) + "(" +
            (if ((e.attributes and UsbConstants.USB_ENDPOINT_XFERTYPE_MASK) ==
                UsbConstants.USB_ENDPOINT_XFER_INT) "intr" else "bulk") + ")"

    private fun describe(all: List<UsbInterface>): String {
        val sb = StringBuilder()
        for (f in all) {
            sb.append("  if").append(f.id).append("/alt").append(f.alternateSetting)
                .append(" cls=0x").append(Integer.toHexString(f.interfaceClass))
                .append(" eps=").append(f.endpointCount).append('\n')
        }
        return sb.toString()
    }

    /** 返回 {in, out}，没有成对的 bulk 端点则返回 null。 */
    private fun bulkPair(f: UsbInterface): Array<UsbEndpoint>? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (k in 0 until f.endpointCount) {
            val e = f.getEndpoint(k)
            if (e.type != 2) continue
            // bEndpointAddress 的 bit7 = 方向（1=IN）。用 UsbConstants 常量会依赖
            // 不同 API 级别的可用性，直接判位最稳。
            val isIn = (e.address and 0x80) != 0
            if (isIn && epIn == null) epIn = e
            if (!isIn && epOut == null) epOut = e
        }
        return if (epIn != null && epOut != null) arrayOf(epIn, epOut) else null
    }

    /* ---------------- 反射构造（隐藏 API 兜底） ---------------- */

    private fun makeEndpoint(addr: Int, attrs: Int, maxPacket: Int, interval: Int): UsbEndpoint? {
        return try {
            val cls = Class.forName("android.hardware.usb.UsbEndpoint")
            val ctor = cls.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val ep = ctor.newInstance(addr, attrs, maxPacket, interval) as UsbEndpoint
            LogHub.i("usb", "反射构造端点成功: " + ep)
            ep
        } catch (t: Throwable) {
            LogHub.throwable("usb", "反射构造 UsbEndpoint 失败（本机屏蔽了该隐藏构造器）", t)
            null
        }
    }

    private fun makeInterface(id: Int, alt: Int, name: String?, cls: Int, sub: Int, proto: Int): UsbInterface? {
        return try {
            val k = Class.forName("android.hardware.usb.UsbInterface")
            val ctor = k.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val f = ctor.newInstance(id, alt, name, cls, sub, proto) as UsbInterface
            LogHub.i("usb", "反射构造接口成功: if" + id + "/alt" + alt)
            f
        } catch (t: Throwable) {
            LogHub.w("usb", "反射构造 UsbInterface 失败: " + t)
            null
        }
    }

    /* ---------------- 便于界面显示的辅助 ---------------- */

    /** USB 速度等级，读 sysfs 得到；读不到就返回 null。 */
    @JvmStatic
    fun speedOf(d: UsbDevice): String? {
        try {
            val bd = busDevOf(d)!!.split("/")
            val bus = Integer.parseInt(bd[0])
            val devNum = Integer.parseInt(bd[1])
            val dir = File("/sys/bus/usb/devices")
            val list = dir.listFiles() ?: return null
            for (f in list) {
                if (!f.isDirectory) continue
                val busnum = readTrim(File(f, "busnum"))
                val devnum = readTrim(File(f, "devnum"))
                if (busnum == null || devnum == null) continue
                if (Integer.parseInt(busnum) == bus && Integer.parseInt(devnum) == devNum) {
                    val sp = readTrim(File(f, "speed"))
                    return if (sp == null) null else sp + " Mbps"
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun readTrim(f: File): String? {
        return try {
            FileInputStream(f).use { input ->
                val b = ByteArray(64)
                val n = input.read(b)
                if (n <= 0) null else javaTrim(String(b, 0, n, Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            null
        }
    }
}
