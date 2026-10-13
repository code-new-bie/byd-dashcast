package com.byd.dashcast.phase1.ncm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 用户态轻量 TCP/IP 协议栈。
 *
 * <p><b>为什么必须有它：</b>车机内核即使把设备认成 usb0，也**没有合法途径**给它配 IP——
 * {@code ip addr add} 要 root；Java 侧 {@code NetworkInterface.addAddress} 是隐藏 API 且需
 * CAP_NET_ADMIN；{@code EthernetManager.setConfiguration} 要系统签名（实车已验证反射失败）。
 * 在「不 root、不 ADB、不系统签名」的约束下，唯一能配通这条链路的办法就是
 * <b>绕过内核网络栈，在用户态实现一份最小 IPv4 栈</b>：
 * <pre>
 *   App（ping / TCP / UDP）  →  Net（本类：ARP + IPv4 + ICMP + UDP + TCP）
 *                            →  Ncm（NTB 组帧）
 *                            →  UsbDeviceConnection.bulkTransfer（bulk 端点）
 * </pre>
 *
 * <p><b>覆盖：</b>ARP（静态绑定 + 动态学习）、IPv4（不做分片重组，收到分片会告警）、
 * ICMP echo（收发双向）、UDP（带校验和）、TCP（握手/收发/优雅关闭/超时重传）。
 * 足以跑通连通性验收，且每一步都有日志可查。
 */
public final class Net implements Ncm.Sink {

    private static final int ETH_IPV4 = 0x0800;
    private static final int ETH_ARP = 0x0806;

    private static final int IPPROTO_ICMP = 1;
    private static final int IPPROTO_TCP = 6;
    private static final int IPPROTO_UDP = 17;

    public interface Out {
        void send(byte[] frame, int len);
    }

    public interface UdpHandler {
        void onUdp(int srcIp, int srcPort, int dstPort, byte[] data, int len);
    }

    /** 一次 ping 的结果。字段公开，便于界面直接展示。 */
    public static final class Pinger {
        public int seq;
        public int payloadLen;
        public long sentAt;
        public volatile boolean done;
        public volatile int rttMs = -1;
        public volatile int ttl = -1;
        public volatile int replyBytes;
        public volatile String note = "无应答";

        public boolean ok() {
            return done && rttMs >= 0;
        }
    }

    /* ---------------- 配置 ---------------- */

    private volatile byte[] ourMac = Wire.mac(Prefs.DEF_MAC);
    private volatile byte[] peerMac = Wire.mac(Prefs.DEF_PEER_MAC);
    private volatile int ourIp = Wire.ip(Prefs.DEF_LOCAL_IP);
    private volatile int peerIp = Wire.ip(Prefs.DEF_PEER_IP);
    private volatile int prefix = 30;
    private volatile int mtu = 1500;
    private volatile boolean staticArp = true;
    public volatile boolean running;

    private final Out out;
    private ThreadPoolExecutor ticker;
    private final Random rnd = new Random();
    private final int pingId = rnd.nextInt(0x7FFF) + 1;

    /* ---------------- 表 ---------------- */

    static final class ArpEntry {
        byte[] mac;
        boolean isStatic;
        long at;
    }

    private final Map<Integer, ArpEntry> arpCache = new ConcurrentHashMap<Integer, ArpEntry>();
    private final Map<Integer, List<byte[]>> arpPending =
            Collections.synchronizedMap(new HashMap<Integer, List<byte[]>>());
    private final Map<Long, TcpConn> tcpConns = new ConcurrentHashMap<Long, TcpConn>();
    private final Map<Integer, UdpHandler> udpPorts = new ConcurrentHashMap<Integer, UdpHandler>();
    private final Map<Integer, Pinger> pingers = new ConcurrentHashMap<Integer, Pinger>();

    /* ---------------- 统计 ---------------- */

    public volatile long ethIn, ethOut, arpIn, arpOut, arpReqSent, arpRepRcvd, arpReqRcvd, arpRepSent;
    public volatile long ipIn, ipOut, ipNotForUs, ipFrag, ipBadCksum;
    public volatile long icmpIn, icmpOut, udpIn, udpOut, tcpIn, tcpOut, tcpNoConn;
    public volatile long loopDropped;
    public volatile String lastInbound = "";

    public Net(Out out) {
        this.out = out;
    }

    /* ---------------- 配置 ---------------- */

    public void configure(String ourMacS, String ourIpS, String peerMacS, String peerIpS,
                          int prefix, int mtu, boolean staticArp) {
        byte[] m1 = Wire.mac(ourMacS);
        byte[] m2 = Wire.mac(peerMacS);
        int i1 = Wire.ip(ourIpS);
        int i2 = Wire.ip(peerIpS);
        if (m1 == null) throw new IllegalArgumentException("本端 MAC 格式错误: " + ourMacS);
        if (m2 == null) throw new IllegalArgumentException("对端 MAC 格式错误: " + peerMacS);
        if (i1 == 0) throw new IllegalArgumentException("本端 IP 格式错误: " + ourIpS);
        if (i2 == 0) throw new IllegalArgumentException("对端 IP 格式错误: " + peerIpS);
        this.ourMac = m1;
        this.peerMac = m2;
        this.ourIp = i1;
        this.peerIp = i2;
        this.prefix = prefix;
        this.mtu = Math.max(576, Math.min(mtu, 1500));
        this.staticArp = staticArp;
        arpCache.clear();
        arpPending.clear();
        if (staticArp) {
            ArpEntry e = new ArpEntry();
            e.mac = m2;
            e.isStatic = true;
            e.at = now();
            arpCache.put(i2, e);
        }
        LogHub.i("net", "配置  本端 " + Wire.mac(m1) + "  " + Wire.ip(i1) + "/" + prefix
                + "   对端 " + Wire.mac(m2) + "  " + Wire.ip(i2)
                + "   MTU=" + this.mtu + "   静态ARP=" + staticArp);
        if (!Wire.macEquals(m1, Wire.mac(Prefs.HARD_MAC))) {
            LogHub.w("net", "★ 源 MAC ≠ 固件硬绑定值 " + Prefs.HARD_MAC
                    + "：仪表 usbdnet 参数写死了对端 MAC，不匹配极可能被直接丢包。");
        }
        if (i1 != Wire.ip(Prefs.HARD_IP)) {
            LogHub.w("net", "★ 本端 IP ≠ 固件绑定值 " + Prefs.HARD_IP
                    + "：/30 网段只有 .1/.2 两个可用地址。");
        }
    }

    public String configLine() {
        return Wire.ip(ourIp) + "/" + prefix + "   " + Wire.mac(ourMac)
                + "   →   " + Wire.ip(peerIp) + "   " + Wire.mac(peerMac)
                + (staticArp ? "   [静态 ARP]" : "   [动态 ARP]");
    }

    public int ourIp() {
        return ourIp;
    }

    public int peerIp() {
        return peerIp;
    }

    public int mtu() {
        return mtu;
    }

    /** 本端 MAC 的副本（DHCP 组包要用）。 */
    public byte[] ourMacBytes() {
        byte[] b = new byte[6];
        System.arraycopy(ourMac, 0, b, 0, 6);
        return b;
    }

    public boolean isStaticArp() {
        return staticArp;
    }

    public void setUdpHandler(int port, UdpHandler h) {
        if (h == null) udpPorts.remove(port);
        else udpPorts.put(port, h);
    }

    /* ---------------- 启停 ---------------- */

    public void start() {
        if (running) return;
        running = true;
        ticker = PumpExecutor.create("dashcast-net", 1);
        ticker.execute(this::tickLoop);
        LogHub.i("net", "协议栈启动  配置: " + configLine());
    }

    public void stop() {
        running = false;
        if (ticker != null) ticker.shutdownNow();
        for (TcpConn c : new ArrayList<TcpConn>(tcpConns.values())) {
            try {
                c.abort();
            } catch (Throwable ignored) {
            }
        }
        tcpConns.clear();
        LogHub.i("net", "协议栈停止");
    }

    private void tickLoop() {
        while (running) {
            try {
                Thread.sleep(150);
            } catch (Throwable ignored) {
                if (!running) return;
            }
            if (!running) return;
            long n = now();
            for (TcpConn c : new ArrayList<TcpConn>(tcpConns.values())) {
                try {
                    c.onTick(n);
                } catch (Throwable t) {
                    LogHub.throwable("net", "TCP 定时器异常", t);
                }
            }
        }
    }

    /* ---------------- 收帧 ---------------- */

    @Override
    public void onFrame(byte[] f, int len) {
        if (len < 14) return;
        ethIn++;
        byte[] dst = new byte[6];
        byte[] src = new byte[6];
        System.arraycopy(f, 0, dst, 0, 6);
        System.arraycopy(f, 6, src, 0, 6);
        int type = Wire.be16(f, 12);

        if (!Wire.isBroadcast(dst) && !Wire.macEquals(dst, ourMac)) {
            loopDropped++;
            if (loopDropped <= 3) {
                LogHub.d("net", "目的 MAC " + Wire.mac(dst) + " 非本机，丢弃");
            }
            return;
        }
        if (type == ETH_IPV4 && len >= 34) {
            learn((int) Wire.be32(f, 26), src);
        }

        try {
            if (type == ETH_ARP) onArp(f, len);
            else if (type == ETH_IPV4) onIp(f, 14, len - 14);
            else lastInbound = "未知以太网类型 0x" + Integer.toHexString(type);
        } catch (Throwable t) {
            LogHub.throwable("net", "处理入帧异常 len=" + len, t);
        }
    }

    private void learn(int ip, byte[] mac) {
        if (ip == 0 || ip == -1) return;
        ArpEntry old = arpCache.get(ip);
        if (old != null && old.isStatic) return;
        ArpEntry e = new ArpEntry();
        e.mac = mac;
        e.isStatic = false;
        e.at = now();
        arpCache.put(ip, e);
    }

    /* ---------------- ARP ---------------- */

    private void onArp(byte[] f, int len) {
        if (len < 42) return;
        arpIn++;
        int htype = Wire.be16(f, 14);
        int ptype = Wire.be16(f, 16);
        int op = Wire.be16(f, 20);
        if (htype != 1 || ptype != ETH_IPV4) return;
        byte[] sha = new byte[6];
        System.arraycopy(f, 22, sha, 0, 6);
        int spa = (int) Wire.be32(f, 28);
        int tpa = (int) Wire.be32(f, 38);
        learn(spa, sha);

        if (op == 1) {
            arpReqRcvd++;
            if (tpa == ourIp) {
                sendArp(2, ourMac, ourIp, sha, spa);
                arpRepSent++;
                LogHub.d("arp", "应答 ARP 询问: " + Wire.ip(ourIp) + " 在本机");
            }
        } else if (op == 2) {
            arpRepRcvd++;
            LogHub.d("arp", "ARP 应答: " + Wire.ip(spa) + " = " + Wire.mac(sha));
            flushPending(spa);
        }
    }

    private void sendArp(int op, byte[] sha, int spa, byte[] tha, int tpa) {
        byte[] p = new byte[28];
        Wire.put16(p, 0, 1);
        Wire.put16(p, 2, ETH_IPV4);
        p[4] = 6;
        p[5] = 4;
        Wire.put16(p, 6, op);
        System.arraycopy(sha, 0, p, 8, 6);
        Wire.put32(p, 14, spa & 0xFFFFFFFFL);
        System.arraycopy(tha == null ? Wire.MAC_ZERO : tha, 0, p, 18, 6);
        Wire.put32(p, 24, tpa & 0xFFFFFFFFL);
        sendEth(Wire.MAC_BCAST, ETH_ARP, p, 28, true);
        arpOut++;
    }

    public void arpRequest(int ip) {
        sendArp(1, ourMac, ourIp, Wire.MAC_ZERO, ip);
        arpReqSent++;
    }

    public void arpAnnounce() {
        sendArp(1, ourMac, ourIp, Wire.MAC_BCAST, ourIp);
        arpReqSent++;
        LogHub.i("arp", "广播免费 ARP 宣告 " + Wire.ip(ourIp) + " = " + Wire.mac(ourMac));
    }

    /**
     * 阻塞式 ARP 探测：返回对端**实际**应答的 MAC。
     * 静态绑定不会被破坏——探完把静态表项放回去。
     */
    public byte[] arpProbe(int ip, int timeoutMs) {
        ArpEntry prev = arpCache.remove(ip);
        try {
            arpRequest(ip);
            long deadline = now() + timeoutMs;
            while (now() < deadline) {
                ArpEntry e = arpCache.get(ip);
                if (e != null) return e.mac;
                sleep(30);
            }
            return null;
        } finally {
            if (prev != null && prev.isStatic) {
                ArpEntry back = new ArpEntry();
                back.mac = prev.mac;
                back.isStatic = true;
                back.at = now();
                arpCache.put(ip, back);
            } else if (prev != null) {
                arpCache.put(ip, prev);
            }
        }
    }

    /** 只查表；未命中则触发一次 ARP 请求，不排队。 */
    private byte[] macFor(int ip) {
        if (ip == -1) return Wire.MAC_BCAST;
        if (staticArp && ip == peerIp) return peerMac;
        ArpEntry e = arpCache.get(ip);
        if (e != null) return e.mac;
        arpRequest(ip);
        return null;
    }

    private void queuePacket(int ip, byte[] pkt) {
        synchronized (arpPending) {
            List<byte[]> q = arpPending.get(ip);
            if (q == null) {
                q = new ArrayList<byte[]>();
                arpPending.put(ip, q);
            }
            if (q.size() < 16) q.add(pkt);
        }
    }

    private void flushPending(int ip) {
        List<byte[]> q;
        synchronized (arpPending) {
            q = arpPending.remove(ip);
        }
        if (q == null || q.isEmpty()) return;
        byte[] mac = macFor(ip);
        if (mac == null) return;
        LogHub.d("arp", "ARP 解析完成，补发 " + q.size() + " 个排队报文");
        for (byte[] p : q) sendEth(mac, ETH_IPV4, p, p.length, true);
    }

    /* ---------------- IPv4 ---------------- */

    private boolean forUs(int dstIp) {
        if (dstIp == ourIp || dstIp == -1) return true;
        int m = Wire.mask(prefix);
        if (m != 0 && dstIp == (ourIp | ~m)) return true;   // 子网广播
        return false;
    }

    private void onIp(byte[] f, int off, int len) {
        if (len < 20) return;
        int vihl = f[off] & 0xFF;
        if ((vihl >> 4) != 4) return;
        int ihl = (vihl & 0x0F) * 4;
        if (ihl < 20 || ihl > len) return;
        int total = Wire.be16(f, off + 2);
        if (total < ihl || total > len) return;

        int flagsFrag = Wire.be16(f, off + 6);
        if ((flagsFrag & 0x2000) != 0 || (flagsFrag & 0x1FFF) != 0) {
            ipFrag++;
            if (ipFrag <= 5) {
                LogHub.w("net", "收到 IP 分片，本实现不做重组，已丢弃"
                        + "（正常链路不该出现，说明对端未按我们的 MSS 分片）");
            }
            return;
        }

        int proto = f[off + 9] & 0xFF;
        int srcIp = (int) Wire.be32(f, off + 12);
        int dstIp = (int) Wire.be32(f, off + 16);

        if (Wire.verify(f, off, ihl, 0) != 0) {
            ipBadCksum++;
            if (ipBadCksum <= 5) LogHub.w("net", "IP 头校验和错，丢弃（源 " + Wire.ip(srcIp) + "）");
            return;
        }
        if (!forUs(dstIp)) {
            ipNotForUs++;
            if (ipNotForUs <= 5) {
                LogHub.d("net", "非本机 IP 报文 " + Wire.ip(srcIp) + " → " + Wire.ip(dstIp) + "，忽略");
            }
            return;
        }
        ipIn++;

        int payOff = off + ihl;
        int payLen = total - ihl;
        switch (proto) {
            case IPPROTO_ICMP:
                onIcmp(f, payOff, payLen, srcIp);
                break;
            case IPPROTO_UDP:
                onUdp(f, payOff, payLen, srcIp);
                break;
            case IPPROTO_TCP:
                onTcp(f, payOff, payLen, srcIp);
                break;
            default:
                if (ipIn <= 8) LogHub.d("net", "不支持的 IP 协议号 " + proto);
                break;
        }
    }

    /* ---------------- ICMP ---------------- */

    private void onIcmp(byte[] f, int off, int len, int srcIp) {
        if (len < 8) return;
        icmpIn++;
        int type = f[off] & 0xFF;
        int code = f[off + 1] & 0xFF;
        int seq = Wire.be16(f, off + 6);

        if (type == 0) {
            Pinger p = pingers.get(seq);
            if (p != null && !p.done) {
                p.rttMs = (int) (now() - p.sentAt);
                p.replyBytes = len;
                p.ttl = 64;
                p.note = "正常";
                p.done = true;
                synchronized (p) {
                    p.notifyAll();
                }
            }
        } else if (type == 8) {
            byte[] echo = new byte[len];
            System.arraycopy(f, off, echo, 0, len);
            echo[0] = 0;
            echo[2] = 0;
            echo[3] = 0;
            Wire.put16(echo, 2, Wire.sum(echo, 0, len, 0));
            ipSend(srcIp, IPPROTO_ICMP, echo, len);
            icmpOut++;
            LogHub.d("icmp", "应答对端 ICMP echo —— 对端正在探测我们");
        } else {
            String n;
            if (type == 3) n = "不可达";
            else if (type == 5) n = "重定向";
            else if (type == 11) n = "超时";
            else n = "type" + type;
            LogHub.w("icmp", "收到 ICMP " + n + " code=" + code + " 来自 " + Wire.ip(srcIp));
            lastInbound = "ICMP " + n + " from " + Wire.ip(srcIp);
        }
    }

    /** 阻塞式 ping。返回对象里 `ok()` 为 true 表示收到应答。 */
    public Pinger ping(int ip, int payloadLen, int timeoutMs) {
        Pinger p = new Pinger();
        p.seq = rnd.nextInt(0x7FFF) + 1;
        p.payloadLen = Math.max(0, payloadLen);
        pingers.put(p.seq, p);

        byte[] mac = macFor(ip);
        if (mac == null) {
            p.note = "ARP 未解析";
            pingers.remove(p.seq);
            return p;
        }

        int len = 8 + p.payloadLen;
        byte[] ic = new byte[len];
        ic[0] = 8;
        Wire.put16(ic, 4, pingId);
        Wire.put16(ic, 6, p.seq);
        for (int i = 8; i < len; i++) ic[i] = (byte) i;
        Wire.put16(ic, 2, Wire.sum(ic, 0, len, 0));

        p.sentAt = now();
        ipSendTo(ip, mac, IPPROTO_ICMP, ic, len);
        icmpOut++;

        synchronized (p) {
            long deadline = now() + timeoutMs;
            while (!p.done) {
                long left = deadline - now();
                if (left <= 0) break;
                try {
                    p.wait(Math.min(left, 200));
                } catch (InterruptedException ignored) {
                }
            }
        }
        pingers.remove(p.seq);
        return p;
    }

    /* ---------------- UDP ---------------- */

    private void onUdp(byte[] f, int off, int len, int srcIp) {
        if (len < 8) return;
        int srcPort = Wire.be16(f, off);
        int dstPort = Wire.be16(f, off + 2);
        int ulen = Wire.be16(f, off + 4);
        if (ulen < 8 || ulen > len) ulen = len;
        int payLen = ulen - 8;
        udpIn++;
        byte[] data = new byte[payLen];
        if (payLen > 0) System.arraycopy(f, off + 8, data, 0, payLen);
        UdpHandler h = udpPorts.get(dstPort);
        if (h != null) {
            try {
                h.onUdp(srcIp, srcPort, dstPort, data, payLen);
            } catch (Throwable t) {
                LogHub.throwable("udp", "UDP 处理异常", t);
            }
        } else if (udpIn <= 8) {
            LogHub.d("udp", "UDP " + Wire.ip(srcIp) + ":" + srcPort + " → :" + dstPort
                    + "  " + payLen + "B（无监听）");
        }
    }

    public boolean sendUdp(int dstIp, int dstPort, int srcPort, byte[] data, int len) {
        byte[] seg = new byte[8 + len];
        Wire.put16(seg, 0, srcPort);
        Wire.put16(seg, 2, dstPort);
        Wire.put16(seg, 4, 8 + len);
        if (len > 0) System.arraycopy(data, 0, seg, 8, len);
        int ck = l4Checksum(ourIp, dstIp, IPPROTO_UDP, seg, 0, seg.length);
        Wire.put16(seg, 6, ck == 0 ? 0xFFFF : ck);
        udpOut++;
        return ipSend(dstIp, IPPROTO_UDP, seg, seg.length);
    }

    /* ---------------- TCP ---------------- */

    private void onTcp(byte[] f, int off, int len, int srcIp) {
        if (len < 20) return;
        if (l4Checksum(srcIp, ourIp, IPPROTO_TCP, f, off, len) != 0) {
            ipBadCksum++;
            return;
        }
        int srcPort = Wire.be16(f, off);
        int dstPort = Wire.be16(f, off + 2);
        long seq = Wire.be32(f, off + 4);
        long ack = Wire.be32(f, off + 8);
        int offFlags = Wire.be16(f, off + 12);
        int dataOff = ((offFlags >> 12) & 0x0F) * 4;
        int flags = offFlags & 0x01FF;
        int win = Wire.be16(f, off + 14);
        if (dataOff < 20 || dataOff > len) return;
        int payLen = len - dataOff;

        tcpIn++;
        TcpConn c = findConn(srcIp, srcPort, dstPort);
        if (c == null) {
            tcpNoConn++;
            if (tcpNoConn <= 10) {
                LogHub.d("tcp", "无匹配连接 " + Wire.ip(srcIp) + ":" + srcPort
                        + " → :" + dstPort + " flags=0x" + Integer.toHexString(flags)
                        + " len=" + payLen);
            }
            if ((flags & TcpConn.F_RST) == 0) replyRst(srcIp, srcPort, dstPort, seq, payLen, flags);
            return;
        }

        int mssOpt = 0;
        if ((flags & TcpConn.F_SYN) != 0) {
            int i = off + 20;
            while (i + 1 < off + dataOff) {
                int kind = f[i] & 0xFF;
                if (kind == 0) break;
                if (kind == 1) {
                    i++;
                    continue;
                }
                int ol = f[i + 1] & 0xFF;
                if (ol < 2 || i + ol > off + dataOff) break;
                if (kind == 2 && ol == 4) mssOpt = Wire.be16(f, i + 2);
                i += ol;
            }
        }
        c.onPacket(flags, seq, ack, win, f, off + dataOff, payLen, mssOpt, now());
    }

    private void replyRst(int dstIp, int dstPort, int ourPort, long theirSeq, int payLen, int flags) {
        byte[] seg = new byte[20];
        Wire.put16(seg, 0, ourPort);
        Wire.put16(seg, 2, dstPort);
        int outFlags = TcpConn.F_RST;
        if ((flags & TcpConn.F_ACK) != 0) {
            Wire.put32(seg, 4, theirSeq & 0xFFFFFFFFL);
            Wire.put32(seg, 8, 0);
        } else {
            Wire.put32(seg, 8, (theirSeq + payLen) & 0xFFFFFFFFL);
            outFlags |= TcpConn.F_ACK;
        }
        Wire.put16(seg, 12, (5 << 12) | outFlags);
        Wire.put16(seg, 16, l4Checksum(ourIp, dstIp, IPPROTO_TCP, seg, 0, 20));
        ipSend(dstIp, IPPROTO_TCP, seg, 20);
    }

    private TcpConn findConn(int dstIp, int dstPort, int srcPort) {
        return tcpConns.get(key(dstIp, dstPort, srcPort));
    }

    private static long key(int ip, int dstPort, int srcPort) {
        return ((long) (ip & 0xFFFFFFFFL) << 32)
                | ((long) (dstPort & 0xFFFF) << 16) | (srcPort & 0xFFFF);
    }

    void forget(TcpConn c) {
        if (tcpConns.remove(key(c.dstIp, c.dstPort, c.srcPort), c)) {
            TcpPortAllocator.PROCESS.release(c.srcPort);
        }
    }

    public int activeTcpCount() {
        return tcpConns.size();
    }

    /** 主动连接；超时返回时状态为 CLOSED。 */
    public TcpConn connect(int dstIp, int dstPort, int timeoutMs) throws IOException {
        int srcPort = TcpPortAllocator.PROCESS.reserve();
        TcpConn c = new TcpConn(this, dstIp, dstPort, srcPort);
        tcpConns.put(key(dstIp, dstPort, srcPort), c);
        LogHub.i("tcp", "TCP_CONNECT " + Wire.ip(ourIp) + ":" + srcPort
                + " -> " + Wire.ip(dstIp) + ":" + dstPort);

        long t0 = now();
        c.startConnect(t0);
        long deadline = t0 + timeoutMs;
        while (now() < deadline) {
            if (!running || Thread.currentThread().isInterrupted()) {
                c.abort();
                return c;
            }
            if (c.isEstablished()) return c;
            if (c.state() == TcpConn.CLOSED) {
                forget(c);
                return c;
            }
            sleep(15);
        }
        LogHub.w("tcp", "连接 " + Wire.ip(dstIp) + ":" + dstPort
                + " 超时 " + timeoutMs + "ms 未建立，已中止; local_port=" + srcPort
                + " received_segments=" + c.rxSegments + " " + c.statusLine());
        c.abort();
        forget(c);
        return c;
    }

    void sendTcp(TcpConn c, int flags, long seq, long ack, byte[] data, int off, int len) {
        byte[] seg = new byte[20 + len];
        Wire.put16(seg, 0, c.srcPort);
        Wire.put16(seg, 2, c.dstPort);
        Wire.put32(seg, 4, seq & 0xFFFFFFFFL);
        Wire.put32(seg, 8, ack & 0xFFFFFFFFL);
        Wire.put16(seg, 12, (5 << 12) | (flags & 0x01FF));
        // 本实现来者不拒地缓冲，故固定通告一个大窗口；不做窗口扩大（避免协商复杂度）
        Wire.put16(seg, 14, 65535);
        if (len > 0) System.arraycopy(data, off, seg, 20, len);
        Wire.put16(seg, 16, l4Checksum(ourIp, c.dstIp, IPPROTO_TCP, seg, 0, seg.length));
        tcpOut++;
        ipSend(c.dstIp, IPPROTO_TCP, seg, seg.length);
    }

    /* ---------------- 出方向 ---------------- */

    private int l4Checksum(int sIp, int dIp, int proto, byte[] payload, int off, int len) {
        int a = Wire.acc(payload, off, len, 0);
        a += ((sIp >>> 16) & 0xFFFF) + (sIp & 0xFFFF);
        a += ((dIp >>> 16) & 0xFFFF) + (dIp & 0xFFFF);
        a += proto & 0xFF;
        a += len & 0xFFFF;
        return Wire.finish(a);
    }

    public boolean ipSend(int dstIp, int proto, byte[] payload, int len) {
        byte[] pkt = buildIp(0, dstIp, proto, payload, len);
        byte[] mac = macFor(dstIp);
        if (mac == null) {
            queuePacket(dstIp, pkt);
            return false;
        }
        return sendEth(mac, ETH_IPV4, pkt, pkt.length, true);
    }

    /**
     * 以指定源 IP（可以是 0.0.0.0）向广播地址发一个 UDP 报文。
     * DHCP 这类「还没有地址」的场景必须走这条路：源 IP 只能填 0。
     */
    public boolean sendUdpBroadcast(int srcIp, int dstPort, int srcPort,
                                    byte[] data, int len) {
        byte[] seg = new byte[8 + len];
        Wire.put16(seg, 0, srcPort);
        Wire.put16(seg, 2, dstPort);
        Wire.put16(seg, 4, 8 + len);
        if (len > 0) System.arraycopy(data, 0, seg, 8, len);
        int ck = l4Checksum(srcIp, -1, IPPROTO_UDP, seg, 0, seg.length);
        Wire.put16(seg, 6, ck == 0 ? 0xFFFF : ck);
        byte[] pkt = buildIp(srcIp, -1, IPPROTO_UDP, seg, seg.length);
        udpOut++;
        return sendEth(Wire.MAC_BCAST, ETH_IPV4, pkt, pkt.length, true);
    }

    private boolean ipSendTo(int dstIp, byte[] dstMac, int proto, byte[] payload, int len) {
        byte[] pkt = buildIp(0, dstIp, proto, payload, len);
        return sendEth(dstMac, ETH_IPV4, pkt, pkt.length, true);
    }

    private byte[] buildIp(int srcIp, int dstIp, int proto, byte[] payload, int len) {
        byte[] ip = new byte[20 + len];
        ip[0] = 0x45;
        Wire.put16(ip, 2, 20 + len);
        Wire.put16(ip, 4, (int) (now() & 0xFFFF));
        Wire.put16(ip, 6, 0x4000);        // DF：我们自己不分片
        ip[8] = 64;
        ip[9] = (byte) proto;
        Wire.put32(ip, 12, (srcIp == 0 ? ourIp : srcIp) & 0xFFFFFFFFL);
        Wire.put32(ip, 16, dstIp & 0xFFFFFFFFL);
        Wire.put16(ip, 10, Wire.sum(ip, 0, 20, 0));
        if (len > 0) System.arraycopy(payload, 0, ip, 20, len);
        return ip;
    }

    /**
     * 以太网组帧 + 出栈。小于 60 字节补齐（NCM 的 datagram 就是无 FCS 的以太帧，
     * 部分接收侧对短帧不容忍，ARP 请求只有 42 字节）。
     */
    private boolean sendEth(byte[] dstMac, int type, byte[] payload, int len, boolean countIp) {
        if (!running) return false;
        if (14 + len > mtu + 14 && type == ETH_IPV4) {
            LogHub.w("net", "报文超过 MTU 未发出: " + (14 + len));
            return false;
        }
        int padded = Math.max(14 + len, 60);
        byte[] f = new byte[padded];
        System.arraycopy(dstMac, 0, f, 0, 6);
        System.arraycopy(ourMac, 0, f, 6, 6);
        Wire.put16(f, 12, type);
        if (len > 0) System.arraycopy(payload, 0, f, 14, len);
        Out o = out;
        if (o == null) return false;
        ethOut++;
        if (type == ETH_IPV4 && countIp) ipOut++;
        try {
            o.send(f, padded);
            return true;
        } catch (Throwable t) {
            LogHub.throwable("net", "出帧失败", t);
            return false;
        }
    }

    /* ---------------- 汇总 ---------------- */

    public String statsLine() {
        return "入  eth=" + ethIn + "  ip=" + ipIn + "  icmp=" + icmpIn
                + "  udp=" + udpIn + "  tcp=" + tcpIn + "\n"
                + "出  eth=" + ethOut + "  ip=" + ipOut + "  icmp=" + icmpOut
                + "  udp=" + udpOut + "  tcp=" + tcpOut + "\n"
                + "ARP 请求发=" + arpReqSent + "  应答收=" + arpRepRcvd
                + "  请求收=" + arpReqRcvd + "  应答发=" + arpRepSent + "\n"
                + "丢弃  非本机IP=" + ipNotForUs + "  分片=" + ipFrag + "  错校验=" + ipBadCksum
                + "  无连接TCP=" + tcpNoConn + "  非本机MAC=" + loopDropped;
    }

    public String arpTable() {
        if (arpCache.isEmpty()) return "(ARP 表为空)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, ArpEntry> e :
                new LinkedHashMap<Integer, ArpEntry>(arpCache).entrySet()) {
            sb.append(Wire.ip(e.getKey())).append("   ").append(Wire.mac(e.getValue().mac))
                    .append(e.getValue().isStatic ? "   [静态配置]" : "   [动态学习]")
                    .append('\n');
        }
        return sb.toString();
    }

    public void clearDynamicArp() {
        for (Integer k : new ArrayList<Integer>(arpCache.keySet())) {
            ArpEntry e = arpCache.get(k);
            if (e != null && !e.isStatic) arpCache.remove(k);
        }
        LogHub.i("arp", "已清空动态 ARP 表项");
    }

    public static long now() {
        return System.nanoTime() / 1000000L;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public String pingIdHex() {
        return String.format(Locale.US, "%04x", pingId);
    }

    public String describeInbound() {
        return lastInbound == null || lastInbound.isEmpty() ? "(暂无)" : lastInbound;
    }
}
