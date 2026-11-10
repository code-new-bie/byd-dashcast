package com.byd.dashcast.phase1.ncm

import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 用户态轻量 TCP/IP 协议栈。
 *
 * **为什么必须有它：**车机内核即使把设备认成 usb0，也**没有合法途径**给它配 IP——
 * `ip addr add` 要 root；Java 侧 `NetworkInterface.addAddress` 是隐藏 API 且需
 * CAP_NET_ADMIN；`EthernetManager.setConfiguration` 要系统签名（实车已验证反射失败）。
 * 在「不 root、不 ADB、不系统签名」的约束下，唯一能配通这条链路的办法就是
 * **绕过内核网络栈，在用户态实现一份最小 IPv4 栈**：
 * ```
 *   App（ping / TCP / UDP）  →  Net（本类：ARP + IPv4 + ICMP + UDP + TCP）
 *                            →  Ncm（NTB 组帧）
 *                            →  UsbDeviceConnection.bulkTransfer（bulk 端点）
 * ```
 *
 * **覆盖：**ARP（静态绑定 + 动态学习）、IPv4（不做分片重组，收到分片会告警）、
 * ICMP echo（收发双向）、UDP（带校验和）、TCP（握手/收发/优雅关闭/超时重传）。
 * 足以跑通连通性验收，且每一步都有日志可查。
 */
class Net(private val out: Out?) : Ncm.Sink {

    fun interface Out {
        fun send(frame: ByteArray, len: Int)
    }

    fun interface UdpHandler {
        fun onUdp(srcIp: Int, srcPort: Int, dstPort: Int, data: ByteArray, len: Int)
    }

    /** 一次 ping 的结果。字段公开，便于界面直接展示。 */
    class Pinger {
        @JvmField var seq = 0
        @JvmField var payloadLen = 0
        @JvmField var sentAt = 0L
        @Volatile @JvmField var done = false
        @Volatile @JvmField var rttMs = -1
        @Volatile @JvmField var ttl = -1
        @Volatile @JvmField var replyBytes = 0
        @Volatile @JvmField var note = "无应答"

        fun ok(): Boolean = done && rttMs >= 0
    }

    /* ---------------- 配置 ---------------- */

    @Volatile private var ourMac: ByteArray = Wire.mac(Prefs.DEF_MAC)!!
    @Volatile private var peerMac: ByteArray = Wire.mac(Prefs.DEF_PEER_MAC)!!
    @Volatile private var ourIp = Wire.ip(Prefs.DEF_LOCAL_IP)
    @Volatile private var peerIp = Wire.ip(Prefs.DEF_PEER_IP)
    @Volatile private var prefix = 30
    @Volatile private var mtu = 1500
    @Volatile private var staticArp = true
    @Volatile @JvmField var running = false

    private var ticker: ThreadPoolExecutor? = null
    private val rnd = Random()
    private val pingId = rnd.nextInt(0x7FFF) + 1

    /* ---------------- 表 ---------------- */

    private class ArpEntry(val mac: ByteArray, val isStatic: Boolean, val at: Long)

    private val arpCache = ConcurrentHashMap<Int, ArpEntry>()
    private val arpPending: MutableMap<Int, MutableList<ByteArray>> =
        Collections.synchronizedMap(HashMap<Int, MutableList<ByteArray>>())
    private val tcpConns = ConcurrentHashMap<Long, TcpConn>()
    private val udpPorts = ConcurrentHashMap<Int, UdpHandler>()
    private val pingers = ConcurrentHashMap<Int, Pinger>()

    /* ---------------- 统计 ---------------- */

    @Volatile @JvmField var ethIn = 0L
    @Volatile @JvmField var ethOut = 0L
    @Volatile @JvmField var arpIn = 0L
    @Volatile @JvmField var arpOut = 0L
    @Volatile @JvmField var arpReqSent = 0L
    @Volatile @JvmField var arpRepRcvd = 0L
    @Volatile @JvmField var arpReqRcvd = 0L
    @Volatile @JvmField var arpRepSent = 0L
    @Volatile @JvmField var ipIn = 0L
    @Volatile @JvmField var ipOut = 0L
    @Volatile @JvmField var ipNotForUs = 0L
    @Volatile @JvmField var ipFrag = 0L
    @Volatile @JvmField var ipBadCksum = 0L
    @Volatile @JvmField var icmpIn = 0L
    @Volatile @JvmField var icmpOut = 0L
    @Volatile @JvmField var udpIn = 0L
    @Volatile @JvmField var udpOut = 0L
    @Volatile @JvmField var tcpIn = 0L
    @Volatile @JvmField var tcpOut = 0L
    @Volatile @JvmField var tcpNoConn = 0L
    @Volatile @JvmField var loopDropped = 0L
    @Volatile @JvmField var lastInbound: String? = ""

    /* ---------------- 配置 ---------------- */

    fun configure(ourMacS: String?, ourIpS: String?, peerMacS: String?, peerIpS: String?,
        prefix: Int, mtu: Int, staticArp: Boolean) {
        val m1 = Wire.mac(ourMacS) ?: throw IllegalArgumentException("本端 MAC 格式错误: " + ourMacS)
        val m2 = Wire.mac(peerMacS) ?: throw IllegalArgumentException("对端 MAC 格式错误: " + peerMacS)
        val i1 = Wire.ip(ourIpS)
        val i2 = Wire.ip(peerIpS)
        if (i1 == 0) throw IllegalArgumentException("本端 IP 格式错误: " + ourIpS)
        if (i2 == 0) throw IllegalArgumentException("对端 IP 格式错误: " + peerIpS)
        this.ourMac = m1
        this.peerMac = m2
        this.ourIp = i1
        this.peerIp = i2
        this.prefix = prefix
        this.mtu = maxOf(576, minOf(mtu, 1500))
        this.staticArp = staticArp
        arpCache.clear()
        arpPending.clear()
        if (staticArp) {
            arpCache[i2] = ArpEntry(m2, true, now())
        }
        LogHub.i("net", "配置  本端 " + Wire.mac(m1) + "  " + Wire.ip(i1) + "/" + prefix +
            "   对端 " + Wire.mac(m2) + "  " + Wire.ip(i2) +
            "   MTU=" + this.mtu + "   静态ARP=" + staticArp)
        if (!Wire.macEquals(m1, Wire.mac(Prefs.HARD_MAC))) {
            LogHub.w("net", "★ 源 MAC ≠ 固件硬绑定值 " + Prefs.HARD_MAC +
                "：仪表 usbdnet 参数写死了对端 MAC，不匹配极可能被直接丢包。")
        }
        if (i1 != Wire.ip(Prefs.HARD_IP)) {
            LogHub.w("net", "★ 本端 IP ≠ 固件绑定值 " + Prefs.HARD_IP +
                "：/30 网段只有 .1/.2 两个可用地址。")
        }
    }

    fun configLine(): String =
        Wire.ip(ourIp) + "/" + prefix + "   " + Wire.mac(ourMac) +
            "   →   " + Wire.ip(peerIp) + "   " + Wire.mac(peerMac) +
            (if (staticArp) "   [静态 ARP]" else "   [动态 ARP]")

    fun ourIp(): Int = ourIp

    fun peerIp(): Int = peerIp

    fun mtu(): Int = mtu

    /** 本端 MAC 的副本（DHCP 组包要用）。 */
    fun ourMacBytes(): ByteArray = ourMac.copyOfRange(0, 6)

    fun isStaticArp(): Boolean = staticArp

    fun setUdpHandler(port: Int, h: UdpHandler?) {
        if (h == null) udpPorts.remove(port)
        else udpPorts[port] = h
    }

    /* ---------------- 启停 ---------------- */

    fun start() {
        if (running) return
        running = true
        val executor = PumpExecutor.create("dashcast-net", 1)
        ticker = executor
        executor.execute { tickLoop() }
        LogHub.i("net", "协议栈启动  配置: " + configLine())
    }

    fun stop() {
        running = false
        ticker?.shutdownNow()
        for (c in ArrayList(tcpConns.values)) {
            try {
                c.abort()
            } catch (ignored: Throwable) {
            }
        }
        tcpConns.clear()
        LogHub.i("net", "协议栈停止")
    }

    /** Local worker proof, used only after physical detach; no remote-role claim. */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        if (running) { return false }
        val executor = ticker ?: return true
        return try {
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(150)
            } catch (ignored: Throwable) {
                if (!running) return
            }
            if (!running) return
            val n = now()
            for (c in ArrayList(tcpConns.values)) {
                try {
                    c.onTick(n)
                } catch (t: Throwable) {
                    LogHub.throwable("net", "TCP 定时器异常", t)
                }
            }
        }
    }

    /* ---------------- 收帧 ---------------- */

    override fun onFrame(frame: ByteArray, len: Int) {
        val f = frame
        if (len < 14) return
        ethIn++
        val dst = f.copyOfRange(0, 6)
        val src = f.copyOfRange(6, 12)
        val type = Wire.be16(f, 12)

        if (!Wire.isBroadcast(dst) && !Wire.macEquals(dst, ourMac)) {
            loopDropped++
            if (loopDropped <= 3) {
                LogHub.d("net", "目的 MAC " + Wire.mac(dst) + " 非本机，丢弃")
            }
            return
        }
        if (type == ETH_IPV4 && len >= 34) {
            learn(Wire.be32(f, 26).toInt(), src)
        }

        try {
            if (type == ETH_ARP) onArp(f, len)
            else if (type == ETH_IPV4) onIp(f, 14, len - 14)
            else lastInbound = "未知以太网类型 0x" + Integer.toHexString(type)
        } catch (t: Throwable) {
            LogHub.throwable("net", "处理入帧异常 len=" + len, t)
        }
    }

    private fun learn(ip: Int, mac: ByteArray) {
        if (ip == 0 || ip == -1) return
        val old = arpCache[ip]
        if (old != null && old.isStatic) return
        arpCache[ip] = ArpEntry(mac, false, now())
    }

    /* ---------------- ARP ---------------- */

    private fun onArp(f: ByteArray, len: Int) {
        if (len < 42) return
        arpIn++
        val htype = Wire.be16(f, 14)
        val ptype = Wire.be16(f, 16)
        val op = Wire.be16(f, 20)
        if (htype != 1 || ptype != ETH_IPV4) return
        val sha = f.copyOfRange(22, 28)
        val spa = Wire.be32(f, 28).toInt()
        val tpa = Wire.be32(f, 38).toInt()
        learn(spa, sha)

        if (op == 1) {
            arpReqRcvd++
            if (tpa == ourIp) {
                sendArp(2, ourMac, ourIp, sha, spa)
                arpRepSent++
                LogHub.d("arp", "应答 ARP 询问: " + Wire.ip(ourIp) + " 在本机")
            }
        } else if (op == 2) {
            arpRepRcvd++
            LogHub.d("arp", "ARP 应答: " + Wire.ip(spa) + " = " + Wire.mac(sha))
            flushPending(spa)
        }
    }

    private fun sendArp(op: Int, sha: ByteArray, spa: Int, tha: ByteArray?, tpa: Int) {
        val p = ByteArray(28)
        Wire.put16(p, 0, 1)
        Wire.put16(p, 2, ETH_IPV4)
        p[4] = 6
        p[5] = 4
        Wire.put16(p, 6, op)
        System.arraycopy(sha, 0, p, 8, 6)
        Wire.put32(p, 14, spa.toLong() and 0xFFFFFFFFL)
        System.arraycopy(tha ?: Wire.MAC_ZERO, 0, p, 18, 6)
        Wire.put32(p, 24, tpa.toLong() and 0xFFFFFFFFL)
        sendEth(Wire.MAC_BCAST, ETH_ARP, p, 28, true)
        arpOut++
    }

    fun arpRequest(ip: Int) {
        sendArp(1, ourMac, ourIp, Wire.MAC_ZERO, ip)
        arpReqSent++
    }

    fun arpAnnounce() {
        sendArp(1, ourMac, ourIp, Wire.MAC_BCAST, ourIp)
        arpReqSent++
        LogHub.i("arp", "广播免费 ARP 宣告 " + Wire.ip(ourIp) + " = " + Wire.mac(ourMac))
    }

    /**
     * 阻塞式 ARP 探测：返回对端**实际**应答的 MAC。
     * 静态绑定不会被破坏——探完把静态表项放回去。
     */
    fun arpProbe(ip: Int, timeoutMs: Int): ByteArray? {
        val prev = arpCache.remove(ip)
        try {
            arpRequest(ip)
            val deadline = now() + timeoutMs
            while (now() < deadline) {
                val e = arpCache[ip]
                if (e != null) return e.mac
                sleep(30)
            }
            return null
        } finally {
            if (prev != null && prev.isStatic) {
                arpCache[ip] = ArpEntry(prev.mac, true, now())
            } else if (prev != null) {
                arpCache[ip] = prev
            }
        }
    }

    /** 只查表；未命中则触发一次 ARP 请求，不排队。 */
    private fun macFor(ip: Int): ByteArray? {
        if (ip == -1) return Wire.MAC_BCAST
        if (staticArp && ip == peerIp) return peerMac
        val e = arpCache[ip]
        if (e != null) return e.mac
        arpRequest(ip)
        return null
    }

    private fun queuePacket(ip: Int, pkt: ByteArray) {
        synchronized(arpPending) {
            val q = arpPending.getOrPut(ip) { ArrayList() }
            if (q.size < 16) q.add(pkt)
        }
    }

    private fun flushPending(ip: Int) {
        val q: List<ByteArray>? = synchronized(arpPending) {
            arpPending.remove(ip)
        }
        if (q.isNullOrEmpty()) return
        val mac = macFor(ip) ?: return
        LogHub.d("arp", "ARP 解析完成，补发 " + q.size + " 个排队报文")
        for (p in q) sendEth(mac, ETH_IPV4, p, p.size, true)
    }

    /* ---------------- IPv4 ---------------- */

    private fun forUs(dstIp: Int): Boolean {
        if (dstIp == ourIp || dstIp == -1) return true
        val m = Wire.mask(prefix)
        if (m != 0 && dstIp == (ourIp or m.inv())) return true   // 子网广播
        return false
    }

    private fun onIp(f: ByteArray, off: Int, len: Int) {
        if (len < 20) return
        val vihl = f[off].toInt() and 0xFF
        if ((vihl shr 4) != 4) return
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || ihl > len) return
        val total = Wire.be16(f, off + 2)
        if (total < ihl || total > len) return

        val flagsFrag = Wire.be16(f, off + 6)
        if ((flagsFrag and 0x2000) != 0 || (flagsFrag and 0x1FFF) != 0) {
            ipFrag++
            if (ipFrag <= 5) {
                LogHub.w("net", "收到 IP 分片，本实现不做重组，已丢弃" +
                    "（正常链路不该出现，说明对端未按我们的 MSS 分片）")
            }
            return
        }

        val proto = f[off + 9].toInt() and 0xFF
        val srcIp = Wire.be32(f, off + 12).toInt()
        val dstIp = Wire.be32(f, off + 16).toInt()

        if (Wire.verify(f, off, ihl, 0) != 0) {
            ipBadCksum++
            if (ipBadCksum <= 5) LogHub.w("net", "IP 头校验和错，丢弃（源 " + Wire.ip(srcIp) + "）")
            return
        }
        if (!forUs(dstIp)) {
            ipNotForUs++
            if (ipNotForUs <= 5) {
                LogHub.d("net", "非本机 IP 报文 " + Wire.ip(srcIp) + " → " + Wire.ip(dstIp) + "，忽略")
            }
            return
        }
        ipIn++

        val payOff = off + ihl
        val payLen = total - ihl
        when (proto) {
            IPPROTO_ICMP -> onIcmp(f, payOff, payLen, srcIp)
            IPPROTO_UDP -> onUdp(f, payOff, payLen, srcIp)
            IPPROTO_TCP -> onTcp(f, payOff, payLen, srcIp)
            else -> if (ipIn <= 8) LogHub.d("net", "不支持的 IP 协议号 " + proto)
        }
    }

    /* ---------------- ICMP ---------------- */

    private fun onIcmp(f: ByteArray, off: Int, len: Int, srcIp: Int) {
        if (len < 8) return
        icmpIn++
        val type = f[off].toInt() and 0xFF
        val code = f[off + 1].toInt() and 0xFF
        val seq = Wire.be16(f, off + 6)

        if (type == 0) {
            val p = pingers[seq]
            if (p != null && !p.done) {
                p.rttMs = (now() - p.sentAt).toInt()
                p.replyBytes = len
                p.ttl = 64
                p.note = "正常"
                p.done = true
                synchronized(p) {
                    p.monitorNotifyAll()
                }
            }
        } else if (type == 8) {
            val echo = f.copyOfRange(off, off + len)
            echo[0] = 0
            echo[2] = 0
            echo[3] = 0
            Wire.put16(echo, 2, Wire.sum(echo, 0, len, 0))
            ipSend(srcIp, IPPROTO_ICMP, echo, len)
            icmpOut++
            LogHub.d("icmp", "应答对端 ICMP echo —— 对端正在探测我们")
        } else {
            val n = when (type) {
                3 -> "不可达"
                5 -> "重定向"
                11 -> "超时"
                else -> "type" + type
            }
            LogHub.w("icmp", "收到 ICMP " + n + " code=" + code + " 来自 " + Wire.ip(srcIp))
            lastInbound = "ICMP " + n + " from " + Wire.ip(srcIp)
        }
    }

    /** 阻塞式 ping。返回对象里 `ok()` 为 true 表示收到应答。 */
    fun ping(ip: Int, payloadLen: Int, timeoutMs: Int): Pinger {
        val p = Pinger()
        p.seq = rnd.nextInt(0x7FFF) + 1
        p.payloadLen = maxOf(0, payloadLen)
        pingers[p.seq] = p

        val mac = macFor(ip)
        if (mac == null) {
            p.note = "ARP 未解析"
            pingers.remove(p.seq)
            return p
        }

        val len = 8 + p.payloadLen
        val ic = ByteArray(len)
        ic[0] = 8
        Wire.put16(ic, 4, pingId)
        Wire.put16(ic, 6, p.seq)
        for (i in 8 until len) ic[i] = i.toByte()
        Wire.put16(ic, 2, Wire.sum(ic, 0, len, 0))

        p.sentAt = now()
        ipSendTo(ip, mac, IPPROTO_ICMP, ic, len)
        icmpOut++

        synchronized(p) {
            val deadline = now() + timeoutMs
            while (!p.done) {
                val left = deadline - now()
                if (left <= 0) break
                try {
                    p.monitorWait(minOf(left, 200L))
                } catch (ignored: InterruptedException) {
                }
            }
        }
        pingers.remove(p.seq)
        return p
    }

    /* ---------------- UDP ---------------- */

    private fun onUdp(f: ByteArray, off: Int, len: Int, srcIp: Int) {
        if (len < 8) return
        val srcPort = Wire.be16(f, off)
        val dstPort = Wire.be16(f, off + 2)
        var ulen = Wire.be16(f, off + 4)
        if (ulen < 8 || ulen > len) ulen = len
        val payLen = ulen - 8
        udpIn++
        val data = ByteArray(payLen)
        if (payLen > 0) System.arraycopy(f, off + 8, data, 0, payLen)
        val h = udpPorts[dstPort]
        if (h != null) {
            try {
                h.onUdp(srcIp, srcPort, dstPort, data, payLen)
            } catch (t: Throwable) {
                LogHub.throwable("udp", "UDP 处理异常", t)
            }
        } else if (udpIn <= 8) {
            LogHub.d("udp", "UDP " + Wire.ip(srcIp) + ":" + srcPort + " → :" + dstPort +
                "  " + payLen + "B（无监听）")
        }
    }

    fun sendUdp(dstIp: Int, dstPort: Int, srcPort: Int, data: ByteArray?, len: Int): Boolean {
        val seg = ByteArray(8 + len)
        Wire.put16(seg, 0, srcPort)
        Wire.put16(seg, 2, dstPort)
        Wire.put16(seg, 4, 8 + len)
        if (len > 0) System.arraycopy(data!!, 0, seg, 8, len)
        val ck = l4Checksum(ourIp, dstIp, IPPROTO_UDP, seg, 0, seg.size)
        Wire.put16(seg, 6, if (ck == 0) 0xFFFF else ck)
        udpOut++
        return ipSend(dstIp, IPPROTO_UDP, seg, seg.size)
    }

    /* ---------------- TCP ---------------- */

    private fun onTcp(f: ByteArray, off: Int, len: Int, srcIp: Int) {
        if (len < 20) return
        if (l4Checksum(srcIp, ourIp, IPPROTO_TCP, f, off, len) != 0) {
            ipBadCksum++
            return
        }
        val srcPort = Wire.be16(f, off)
        val dstPort = Wire.be16(f, off + 2)
        val seq = Wire.be32(f, off + 4)
        val ack = Wire.be32(f, off + 8)
        val offFlags = Wire.be16(f, off + 12)
        val dataOff = ((offFlags shr 12) and 0x0F) * 4
        val flags = offFlags and 0x01FF
        val win = Wire.be16(f, off + 14)
        if (dataOff < 20 || dataOff > len) return
        val payLen = len - dataOff

        tcpIn++
        val c = findConn(srcIp, srcPort, dstPort)
        if (c == null) {
            tcpNoConn++
            if (tcpNoConn <= 10) {
                LogHub.d("tcp", "无匹配连接 " + Wire.ip(srcIp) + ":" + srcPort +
                    " → :" + dstPort + " flags=0x" + Integer.toHexString(flags) +
                    " len=" + payLen)
            }
            if ((flags and TcpConn.F_RST) == 0) replyRst(srcIp, srcPort, dstPort, seq, payLen, flags)
            return
        }

        var mssOpt = 0
        if ((flags and TcpConn.F_SYN) != 0) {
            var i = off + 20
            while (i + 1 < off + dataOff) {
                val kind = f[i].toInt() and 0xFF
                if (kind == 0) break
                if (kind == 1) {
                    i++
                    continue
                }
                val ol = f[i + 1].toInt() and 0xFF
                if (ol < 2 || i + ol > off + dataOff) break
                if (kind == 2 && ol == 4) mssOpt = Wire.be16(f, i + 2)
                i += ol
            }
        }
        c.onPacket(flags, seq, ack, win, f, off + dataOff, payLen, mssOpt, now())
    }

    private fun replyRst(dstIp: Int, dstPort: Int, ourPort: Int, theirSeq: Long, payLen: Int, flags: Int) {
        val seg = ByteArray(20)
        Wire.put16(seg, 0, ourPort)
        Wire.put16(seg, 2, dstPort)
        var outFlags = TcpConn.F_RST
        if ((flags and TcpConn.F_ACK) != 0) {
            Wire.put32(seg, 4, theirSeq and 0xFFFFFFFFL)
            Wire.put32(seg, 8, 0L)
        } else {
            Wire.put32(seg, 8, (theirSeq + payLen) and 0xFFFFFFFFL)
            outFlags = outFlags or TcpConn.F_ACK
        }
        Wire.put16(seg, 12, (5 shl 12) or outFlags)
        Wire.put16(seg, 16, l4Checksum(ourIp, dstIp, IPPROTO_TCP, seg, 0, 20))
        ipSend(dstIp, IPPROTO_TCP, seg, 20)
    }

    private fun findConn(dstIp: Int, dstPort: Int, srcPort: Int): TcpConn? =
        tcpConns[key(dstIp, dstPort, srcPort)]

    fun forget(c: TcpConn) {
        if (tcpConns.remove(key(c.dstIp, c.dstPort, c.srcPort), c)) {
            TcpPortAllocator.PROCESS.release(c.srcPort)
        }
    }

    fun activeTcpCount(): Int = tcpConns.size

    /** 主动连接；超时返回时状态为 CLOSED。 */
    @Throws(IOException::class)
    fun connect(dstIp: Int, dstPort: Int, timeoutMs: Int): TcpConn {
        val srcPort = TcpPortAllocator.PROCESS.reserve()
        val c = TcpConn(this, dstIp, dstPort, srcPort)
        tcpConns[key(dstIp, dstPort, srcPort)] = c
        LogHub.i("tcp", "TCP_CONNECT " + Wire.ip(ourIp) + ":" + srcPort +
            " -> " + Wire.ip(dstIp) + ":" + dstPort)

        val t0 = now()
        c.startConnect(t0)
        val deadline = t0 + timeoutMs
        while (now() < deadline) {
            if (!running || Thread.currentThread().isInterrupted) {
                c.abort()
                return c
            }
            if (c.isEstablished()) return c
            if (c.state() == TcpConn.CLOSED) {
                forget(c)
                return c
            }
            sleep(15)
        }
        LogHub.w("tcp", "连接 " + Wire.ip(dstIp) + ":" + dstPort +
            " 超时 " + timeoutMs + "ms 未建立，已中止; local_port=" + srcPort +
            " received_segments=" + c.rxSegments + " " + c.statusLine())
        c.abort()
        forget(c)
        return c
    }

    fun sendTcp(c: TcpConn, flags: Int, seq: Long, ack: Long, data: ByteArray?, off: Int, len: Int) {
        val seg = ByteArray(20 + len)
        Wire.put16(seg, 0, c.srcPort)
        Wire.put16(seg, 2, c.dstPort)
        Wire.put32(seg, 4, seq and 0xFFFFFFFFL)
        Wire.put32(seg, 8, ack and 0xFFFFFFFFL)
        Wire.put16(seg, 12, (5 shl 12) or (flags and 0x01FF))
        // 本实现来者不拒地缓冲，故固定通告一个大窗口；不做窗口扩大（避免协商复杂度）
        Wire.put16(seg, 14, 65535)
        if (len > 0) System.arraycopy(data!!, off, seg, 20, len)
        Wire.put16(seg, 16, l4Checksum(ourIp, c.dstIp, IPPROTO_TCP, seg, 0, seg.size))
        tcpOut++
        ipSend(c.dstIp, IPPROTO_TCP, seg, seg.size)
    }

    /* ---------------- 出方向 ---------------- */

    private fun l4Checksum(sIp: Int, dIp: Int, proto: Int, payload: ByteArray, off: Int, len: Int): Int {
        var a = Wire.acc(payload, off, len, 0)
        a += ((sIp ushr 16) and 0xFFFF) + (sIp and 0xFFFF)
        a += ((dIp ushr 16) and 0xFFFF) + (dIp and 0xFFFF)
        a += proto and 0xFF
        a += len and 0xFFFF
        return Wire.finish(a)
    }

    fun ipSend(dstIp: Int, proto: Int, payload: ByteArray?, len: Int): Boolean {
        val pkt = buildIp(0, dstIp, proto, payload, len)
        val mac = macFor(dstIp)
        if (mac == null) {
            queuePacket(dstIp, pkt)
            return false
        }
        return sendEth(mac, ETH_IPV4, pkt, pkt.size, true)
    }

    /**
     * 以指定源 IP（可以是 0.0.0.0）向广播地址发一个 UDP 报文。
     * DHCP 这类「还没有地址」的场景必须走这条路：源 IP 只能填 0。
     */
    fun sendUdpBroadcast(srcIp: Int, dstPort: Int, srcPort: Int, data: ByteArray?, len: Int): Boolean {
        val seg = ByteArray(8 + len)
        Wire.put16(seg, 0, srcPort)
        Wire.put16(seg, 2, dstPort)
        Wire.put16(seg, 4, 8 + len)
        if (len > 0) System.arraycopy(data!!, 0, seg, 8, len)
        val ck = l4Checksum(srcIp, -1, IPPROTO_UDP, seg, 0, seg.size)
        Wire.put16(seg, 6, if (ck == 0) 0xFFFF else ck)
        val pkt = buildIp(srcIp, -1, IPPROTO_UDP, seg, seg.size)
        udpOut++
        return sendEth(Wire.MAC_BCAST, ETH_IPV4, pkt, pkt.size, true)
    }

    private fun ipSendTo(dstIp: Int, dstMac: ByteArray, proto: Int, payload: ByteArray, len: Int): Boolean {
        val pkt = buildIp(0, dstIp, proto, payload, len)
        return sendEth(dstMac, ETH_IPV4, pkt, pkt.size, true)
    }

    private fun buildIp(srcIp: Int, dstIp: Int, proto: Int, payload: ByteArray?, len: Int): ByteArray {
        val ip = ByteArray(20 + len)
        ip[0] = 0x45
        Wire.put16(ip, 2, 20 + len)
        Wire.put16(ip, 4, (now() and 0xFFFF).toInt())
        Wire.put16(ip, 6, 0x4000)        // DF：我们自己不分片
        ip[8] = 64
        ip[9] = proto.toByte()
        Wire.put32(ip, 12, (if (srcIp == 0) ourIp else srcIp).toLong() and 0xFFFFFFFFL)
        Wire.put32(ip, 16, dstIp.toLong() and 0xFFFFFFFFL)
        Wire.put16(ip, 10, Wire.sum(ip, 0, 20, 0))
        if (len > 0) System.arraycopy(payload!!, 0, ip, 20, len)
        return ip
    }

    /**
     * 以太网组帧 + 出栈。小于 60 字节补齐（NCM 的 datagram 就是无 FCS 的以太帧，
     * 部分接收侧对短帧不容忍，ARP 请求只有 42 字节）。
     */
    private fun sendEth(dstMac: ByteArray, type: Int, payload: ByteArray, len: Int, countIp: Boolean): Boolean {
        if (!running) return false
        if (14 + len > mtu + 14 && type == ETH_IPV4) {
            LogHub.w("net", "报文超过 MTU 未发出: " + (14 + len))
            return false
        }
        val padded = maxOf(14 + len, 60)
        val f = ByteArray(padded)
        System.arraycopy(dstMac, 0, f, 0, 6)
        System.arraycopy(ourMac, 0, f, 6, 6)
        Wire.put16(f, 12, type)
        if (len > 0) System.arraycopy(payload, 0, f, 14, len)
        val o = out ?: return false
        ethOut++
        if (type == ETH_IPV4 && countIp) ipOut++
        return try {
            o.send(f, padded)
            true
        } catch (t: Throwable) {
            LogHub.throwable("net", "出帧失败", t)
            false
        }
    }

    /* ---------------- 汇总 ---------------- */

    fun statsLine(): String =
        "入  eth=" + ethIn + "  ip=" + ipIn + "  icmp=" + icmpIn +
            "  udp=" + udpIn + "  tcp=" + tcpIn + "\n" +
            "出  eth=" + ethOut + "  ip=" + ipOut + "  icmp=" + icmpOut +
            "  udp=" + udpOut + "  tcp=" + tcpOut + "\n" +
            "ARP 请求发=" + arpReqSent + "  应答收=" + arpRepRcvd +
            "  请求收=" + arpReqRcvd + "  应答发=" + arpRepSent + "\n" +
            "丢弃  非本机IP=" + ipNotForUs + "  分片=" + ipFrag + "  错校验=" + ipBadCksum +
            "  无连接TCP=" + tcpNoConn + "  非本机MAC=" + loopDropped

    fun arpTable(): String {
        if (arpCache.isEmpty()) return "(ARP 表为空)"
        val sb = StringBuilder()
        for ((ip, entry) in LinkedHashMap(arpCache)) {
            sb.append(Wire.ip(ip)).append("   ").append(Wire.mac(entry.mac))
                .append(if (entry.isStatic) "   [静态配置]" else "   [动态学习]")
                .append('\n')
        }
        return sb.toString()
    }

    fun clearDynamicArp() {
        for (k in ArrayList(arpCache.keys)) {
            val e = arpCache[k]
            if (e != null && !e.isStatic) arpCache.remove(k)
        }
        LogHub.i("arp", "已清空动态 ARP 表项")
    }

    fun pingIdHex(): String = String.format(Locale.US, "%04x", pingId)

    fun describeInbound(): String {
        val inbound = lastInbound
        return if (inbound == null || inbound.isEmpty()) "(暂无)" else inbound
    }

    companion object {
        private const val ETH_IPV4 = 0x0800
        private const val ETH_ARP = 0x0806

        private const val IPPROTO_ICMP = 1
        private const val IPPROTO_TCP = 6
        private const val IPPROTO_UDP = 17

        @JvmStatic
        fun now(): Long = System.nanoTime() / 1000000L

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun key(ip: Int, dstPort: Int, srcPort: Int): Long =
            ((ip.toLong() and 0xFFFFFFFFL) shl 32) or
                ((dstPort and 0xFFFF).toLong() shl 16) or (srcPort and 0xFFFF).toLong()
    }
}
