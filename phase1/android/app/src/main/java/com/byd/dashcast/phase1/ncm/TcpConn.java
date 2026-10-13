package com.byd.dashcast.phase1.ncm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 精简 TCP 连接（主动打开方）。
 *
 * <p>设计取舍：本 App 是「连通性测试工具」，要的是**确定性**而非极致吞吐，
 * 因此只实现足以完成「握手 / 收发 / 优雅关闭 / 丢包自愈」的那一子集：
 * <ul>
 *   <li>不做乱序重组——USB NCM 链路本身不丢不乱序，真出现缺口就按缺 ACK 处理（会告警）；</li>
 *   <li>不做 SACK / 时间戳 / 窗口扩大；对端 SYN 里的 MSS 选项会被采纳；</li>
 *   <li>重传用「累积确认 + 回退 N」：收到 ACK 就裁剪未确认队列，超时或三重复 ACK 就重发队首起全部未确认段；</li>
 *   <li>发送窗口 = min(对端通告窗口, 拥塞窗口)；拥塞窗口从 1 段起，每个 ACK 翻倍到 8 段封顶。</li>
 * </ul>
 * 这些取舍在直连测试场景下足够，且行为可解释——出问题时日志能直接指出卡在哪一步。
 */
public final class TcpConn {

    public static final int CLOSED = 0;
    public static final int SYN_SENT = 1;
    public static final int ESTABLISHED = 2;
    public static final int FIN_WAIT_1 = 3;
    public static final int FIN_WAIT_2 = 4;
    public static final int CLOSE_WAIT = 5;
    public static final int TIME_WAIT = 6;

    public static final int F_FIN = 0x01;
    public static final int F_SYN = 0x02;
    public static final int F_RST = 0x04;
    public static final int F_PSH = 0x08;
    public static final int F_ACK = 0x10;

    private static final int MSS = 1460;
    private static final long RTO_MIN = 400;
    private static final long RTO_MAX = 4000;
    private static final int CWND_MAX_SEGS = 8;

    private static final class Seg {
        long seq;
        byte[] data;
        long sentAt;
        boolean fin;
    }

    private final Net net;
    public final int dstIp;
    public final int dstPort;
    public final int srcPort;

    private int state = CLOSED;
    private long sndUna, sndNxt, rcvNxt;
    private int sndWnd = 65535;
    private int peerMss = MSS;
    private int cwndSegs = 1;
    private long rto = RTO_MIN;

    private final ArrayDeque<Seg> unacked = new ArrayDeque<Seg>();
    private final ArrayDeque<byte[]> outQ = new ArrayDeque<byte[]>();
    private final ByteArrayOutputStream inBuf = new ByteArrayOutputStream();

    private boolean peerFin;
    private boolean reset;
    private int dupAcks;
    private long lastProgressAt;
    /** 已发出但未被确认的字节数。用计数而不是 sndNxt-sndUna，避开序号回绕的坑。 */
    private int outstanding;

    private String error;
    private int unexpectedHandshakePackets;
    public long connectLatencyMs = -1;
    public long establishedAt = -1;
    public long closedAt = -1;
    public int retransCount;
    public int txSegments;
    public int rxSegments;
    public long txBytes;
    public long rxBytes;

    TcpConn(Net net, int dstIp, int dstPort, int srcPort) {
        this.net = net;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
        this.srcPort = srcPort;
    }

    public synchronized int state() {
        return state;
    }

    public synchronized String stateName() {
        switch (state) {
            case CLOSED: return "CLOSED";
            case SYN_SENT: return "SYN_SENT";
            case ESTABLISHED: return "ESTABLISHED";
            case FIN_WAIT_1: return "FIN_WAIT_1";
            case FIN_WAIT_2: return "FIN_WAIT_2";
            case CLOSE_WAIT: return "CLOSE_WAIT";
            case TIME_WAIT: return "TIME_WAIT";
            default: return "?";
        }
    }

    public synchronized boolean isEstablished() {
        return state == ESTABLISHED;
    }

    public synchronized boolean isClosed() {
        return state == CLOSED;
    }

    public synchronized String error() {
        return error;
    }

    public synchronized int peerMssValue() {
        return peerMss;
    }

    public synchronized int available() {
        return inBuf.size();
    }

    public synchronized int unackedBytes() {
        return outstanding;
    }

    public synchronized String statusLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(stateName());
        sb.append(" sndUna=").append(sndUna & 0xFFFFFFFFL);
        sb.append(" sndNxt=").append(sndNxt & 0xFFFFFFFFL);
        sb.append(" rcvNxt=").append(rcvNxt & 0xFFFFFFFFL);
        sb.append(" win=").append(sndWnd);
        sb.append(" mss=").append(peerMss);
        sb.append(" 未确认=").append(unackedBytes()).append("B");
        sb.append(" 重传=").append(retransCount);
        sb.append(" tx=").append(txBytes).append("B rx=").append(rxBytes).append("B");
        if (error != null) sb.append(" 错误=").append(error);
        return sb.toString();
    }

    /* ---------------- 主动打开 ---------------- */

    synchronized void startConnect(long now) {
        long isn = (now * 1000 + srcPort * 7) & 0x7FFFFFFFL;
        sndUna = isn;
        sndNxt = isn + 1;
        state = SYN_SENT;
        lastProgressAt = now;
        net.sendTcp(this, F_SYN, isn, 0, null, 0, 0);
        LogHub.d("tcp", "-> SYN seq=" + (isn & 0xFFFFFFFFL)
                + " " + Wire.ip(dstIp) + ":" + dstPort);
    }

    /* ---------------- 收包 ---------------- */

    synchronized void onPacket(int flags, long seq, long ack, int win,
                               byte[] pl, int off, int len, int mssOpt, long now) {
        rxSegments++;
        if ((flags & F_RST) != 0) {
            reset = true;
            error = "对端 RST（" + Wire.ip(dstIp) + ":" + dstPort + "）";
            state = CLOSED;
            closedAt = now;
            notifyAll();
            LogHub.w("tcp", "<- RST：" + error);
            return;
        }
        if ((flags & F_SYN) != 0 && (flags & F_ACK) != 0 && state == SYN_SENT) {
            long expect = (sndNxt) & 0xFFFFFFFFL;
            if (ack != expect) {
                LogHub.w("tcp", "<- SYN/ACK 的 ack=" + ack + " 与期望 " + expect + " 不符，忽略");
                return;
            }
            sndUna = sndNxt;
            rcvNxt = (seq + 1) & 0xFFFFFFFFL;
            if (mssOpt > 0) peerMss = Math.min(MSS, Math.max(64, mssOpt));
            sndWnd = win;
            state = ESTABLISHED;
            establishedAt = now;
            connectLatencyMs = now - lastProgressAt;
            lastProgressAt = now;
            sendAck();
            LogHub.i("tcp", "<- SYN/ACK 握手完成 " + connectLatencyMs + "ms  对端 MSS=" + peerMss);
            notifyAll();
            pump(now);
            return;
        }

        if (state == SYN_SENT) {
            if (++unexpectedHandshakePackets <= 3) {
                LogHub.w("tcp", "TCP_HANDSHAKE_NON_SYN local_port=" + srcPort
                        + " flags=0x" + Integer.toHexString(flags) + " seq=" + seq
                        + " ack=" + ack + " len=" + len);
            }
            return;
        }
        if (state == CLOSED) return;

        boolean ackAdvanced = false;
        if ((flags & F_ACK) != 0) {
            long u = sndUna & 0xFFFFFFFFL;
            long a = ack & 0xFFFFFFFFL;
            long diff = ((a - u) & 0xFFFFFFFFL);
            if (diff > outstanding && diff < 0x80000000L) {
                sendAck();
                return;
            }
            sndWnd = win;
            if (diff != 0 && diff < 0x80000000L && outstanding > 0) {
                // 累积确认把未确认队列裁剪掉
                int removed = 0;
                while (!unacked.isEmpty()) {
                    Seg s = unacked.peekFirst();
                    long end = (s.seq + s.data.length + (s.fin ? 1 : 0)) & 0xFFFFFFFFL;
                    long d = ((a - end) & 0xFFFFFFFFL);
                    if (d == 0 || d < 0x80000000L) {
                        unacked.pollFirst();
                        removed += s.data.length + (s.fin ? 1 : 0);
                    } else {
                        long partial = (a - s.seq) & 0xFFFFFFFFL;
                        if (!s.fin && partial > 0 && partial < s.data.length) {
                            int consumed = (int)partial;
                            byte[] rest = new byte[s.data.length - consumed];
                            System.arraycopy(s.data, consumed, rest, 0, rest.length);
                            s.seq = a;
                            s.data = rest;
                            removed += consumed;
                        }
                        break;
                    }
                }
                outstanding -= removed;
                if (outstanding < 0) outstanding = 0;
                sndUna = a;
                sndWnd = win;
                if (removed > 0) {
                    rto = RTO_MIN;
                    cwndSegs = Math.min(CWND_MAX_SEGS, cwndSegs + 1);
                    ackAdvanced = true;
                    dupAcks = 0;
                    lastProgressAt = now;
                }
                if ((state == FIN_WAIT_1) && unacked.isEmpty()) {
                    state = FIN_WAIT_2;
                }
                if (state == FIN_WAIT_2 && peerFin) {
                    state = TIME_WAIT;
                    closedAt = now;
                }
            } else if (diff == 0 && len == 0 && outstanding > 0) {
                dupAcks++;
                if (dupAcks == 3) {
                    LogHub.w("tcp", "三重复 ACK，立即重传");
                    retransmitAll(now);
                }
            }
        }

        if (len > 0) {
            long rn = rcvNxt & 0xFFFFFFFFL;
            long sq = seq & 0xFFFFFFFFL;
            if (sq == rn) {
                if (len > 65536 - inBuf.size()) {
                    error = "TCP receive buffer limit";
                    reset = true;
                    state = CLOSED;
                    notifyAll();
                    return;
                }
                rxBytes += len;
                inBuf.write(pl, off, len);
                rcvNxt = (seq + len) & 0xFFFFFFFFL;
                sendAck();
                notifyAll();
            } else if (((sq - rn) & 0xFFFFFFFFL) < 0x80000000L) {
                // 已有数据的重复段：再确认一次，帮助对端推进
                sendAck();
            } else {
                // 前方出现缺口 —— NCM 链路上不该发生，记录以便定位
                LogHub.w("tcp", "收到乱序段 seq=" + sq + " 期望=" + rn + " len=" + len
                        + "（本实现不做乱序重组，按缺 ACK 处理）");
                sendAck();
            }
        }

        if ((flags & F_FIN) != 0 && ((seq + len) & 0xFFFFFFFFL) == rcvNxt && !peerFin) {
            rcvNxt = (rcvNxt + 1) & 0xFFFFFFFFL;
            peerFin = true;
            sendAck();
            if (state == ESTABLISHED) {
                state = CLOSE_WAIT;
            } else if (state == FIN_WAIT_1) {
                state = TIME_WAIT;
                closedAt = now;
            } else if (state == FIN_WAIT_2) {
                state = TIME_WAIT;
                closedAt = now;
            }
            notifyAll();
            LogHub.d("tcp", "<- FIN，进入 " + stateName());
        }

        if (ackAdvanced || (!outQ.isEmpty() && len == 0)) pump(now);
    }

    /* ---------------- 发送 ---------------- */

    /** 把数据排入发送队列并推进窗口。返回 false 表示连接已不可用。 */
    synchronized boolean enqueue(byte[] data, int len) {
        if (state != ESTABLISHED && state != CLOSE_WAIT) return false;
        if (data == null || len < 0 || len > data.length || len > 0x200018 || !outQ.isEmpty()) return false;
        int off = 0;
        while (off < len) {
            int n = Math.min(peerMss, len - off);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            outQ.addLast(chunk);
            off += n;
        }
        return true;
    }

    synchronized void pump(long now) {
        if (state != ESTABLISHED && state != CLOSE_WAIT) return;
        int budget = Math.max(1, Math.min(cwndSegs, CWND_MAX_SEGS)) * peerMss;
        int allowed = Math.min(sndWnd <= 0 ? 0 : sndWnd, budget) - outstanding;
        while (allowed > 0 && !outQ.isEmpty()) {
            byte[] chunk = outQ.pollFirst();
            int n = Math.min(chunk.length, Math.min(allowed, peerMss));
            Seg s = new Seg();
            s.seq = sndNxt;
            s.data = new byte[n];
            System.arraycopy(chunk, 0, s.data, 0, n);
            s.sentAt = now;
            net.sendTcp(this, F_ACK | F_PSH, sndNxt, rcvNxt, s.data, 0, n);
            txSegments++;
            txBytes += n;
            unacked.addLast(s);
            outstanding += n;
            sndNxt = (sndNxt + n) & 0xFFFFFFFFL;
            allowed -= n;
            if (n < chunk.length) {
                byte[] rest = new byte[chunk.length - n];
                System.arraycopy(chunk, n, rest, 0, rest.length);
                outQ.addFirst(rest);
            }
        }
    }

    /** 阻塞发送：等所有字节被确认，或超时。返回实际入网字节数。 */
    public int send(byte[] data, int len, int timeoutMs) {
        long deadline = Net.now() + timeoutMs;
        synchronized (this) {
            if (state != ESTABLISHED) return -1;
            if (!enqueue(data, len)) return -1;
            pump(Net.now());
            while (true) {
                if (outQ.isEmpty() && unacked.isEmpty()) return len;
                if (state == CLOSED || reset || peerFin) return -1;
                long left = deadline - Net.now();
                if (left <= 0) {
                    LogHub.w("tcp", "send 超时：仍有 " + outQ.size() + " 段待发 / "
                            + unackedBytes() + "B 未确认");
                    return -1;
                }
                try {
                    wait(Math.min(left, 200));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                pump(Net.now());
            }
        }
    }

    /** 阻塞读取，超时返回 0；返回 -1 表示连接已关闭且无剩余数据。 */
    public int read(byte[] buf, int off, int max, int timeoutMs) {
        long deadline = Net.now() + timeoutMs;
        synchronized (this) {
            while (true) {
                if (inBuf.size() > 0) {
                    byte[] all = inBuf.toByteArray();
                    inBuf.reset();
                    int n = Math.min(max, all.length);
                    System.arraycopy(all, 0, buf, off, n);
                    if (n < all.length) inBuf.write(all, n, all.length - n);
                    return n;
                }
                if (state == CLOSED || reset || peerFin) return -1;
                long left = deadline - Net.now();
                if (left <= 0) return 0;
                try {
                    wait(Math.min(left, 200));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
    }

    /** 把已收数据全部读出成字符串（用于抓一小段响应）。 */
    public String drainText(int maxBytes, int quietMs) {
        long deadline = Net.now() + quietMs;
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[2048];
        while (sb.length() < maxBytes) {
            int r = read(buf, 0, Math.min(buf.length, maxBytes - sb.length()),
                    (int) Math.max(1, deadline - Net.now()));
            if (r <= 0) break;
            for (int i = 0; i < r; i++) {
                char c = (char) (buf[i] & 0xFF);
                sb.append(c >= 32 && c < 127 ? c : '.');
            }
            if (Net.now() >= deadline) break;
        }
        return sb.toString();
    }

    /* ---------------- 关闭 ---------------- */

    public void close(int timeoutMs) {
        long now = Net.now();
        synchronized (this) {
            if (state == CLOSED || state == TIME_WAIT) {
                state = CLOSED;
                closedAt = now;
                notifyAll();
                net.forget(this);
                return;
            }
            if (state == SYN_SENT || state == ESTABLISHED || state == CLOSE_WAIT) {
                net.sendTcp(this, F_FIN | F_ACK, sndNxt, rcvNxt, null, 0, 0);
                Seg s = new Seg();
                s.seq = sndNxt;
                s.data = new byte[0];
                s.fin = true;
                s.sentAt = now;
                unacked.addLast(s);
                outstanding += 1;       // FIN 占一个序号，必须计入未确认
                sndNxt = (sndNxt + 1) & 0xFFFFFFFFL;
                state = FIN_WAIT_1;
                LogHub.d("tcp", "-> FIN，进入 FIN_WAIT_1");
            }
            long deadline = now + timeoutMs;
            while (state != CLOSED && state != TIME_WAIT && state != CLOSE_WAIT) {
                long left = deadline - Net.now();
                if (left <= 0) break;
                try {
                    wait(Math.min(left, 200));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            retransOff();
            state = CLOSED;
            closedAt = Net.now();
            notifyAll();
        }
        net.forget(this);
    }

    void retransOff() {
        // 主动关闭后若对端（指 QNX 侧）不回 FIN，也允许直接释放本地连接
        try {
            net.forget(this);
        } catch (Throwable ignored) {
        }
    }

    /** 强制中止：发 RST 并就地释放。 */
    public void abort() {
        synchronized (this) {
            if (state != CLOSED) {
                net.sendTcp(this, F_RST | F_ACK, sndNxt, rcvNxt, null, 0, 0);
                LogHub.d("tcp", "-> RST（主动中止）");
            }
            state = CLOSED;
            closedAt = Net.now();
            notifyAll();
        }
        net.forget(this);
    }

    /* ---------------- 定时器 ---------------- */

    void onTick(long now) {
        synchronized (this) {
            boolean needRetrans = false;
            if (!unacked.isEmpty()) {
                Seg first = unacked.peekFirst();
                if (now - first.sentAt >= rto) needRetrans = true;
            }
            if (state == SYN_SENT && now - lastProgressAt >= rto) needRetrans = true;
            if (state == CLOSED) return;
            if (needRetrans) {
                if (state == SYN_SENT) {
                    long isn = sndUna & 0xFFFFFFFFL;
                    net.sendTcp(this, F_SYN, isn, 0, null, 0, 0);
                    lastProgressAt = now;
                    retransCount++;
                    rto = Math.min(RTO_MAX, rto * 2);
                    LogHub.w("tcp", "SYN 超时重传（第 " + retransCount + " 次）");
                } else {
                    retransmitAll(now);
                }
            }
            // 关闭阶段长时间无望则强制收尾，避免连接表泄漏
            if ((state == FIN_WAIT_1 || state == FIN_WAIT_2) && now - lastProgressAt > 8000) {
                LogHub.w("tcp", "关闭流程超时，强制释放（" + stateName() + "）");
                state = CLOSED;
                closedAt = now;
                notifyAll();
                net.forget(this);
            }
        }
    }

    private void retransmitAll(long now) {
        if (unacked.isEmpty()) return;
        retransCount++;
        List<Seg> copy = new ArrayList<Seg>(unacked);
        if (retransCount <= 3 || retransCount % 10 == 0) {
            LogHub.w("tcp", "超时重传 " + copy.size() + " 段（第 " + retransCount + " 次）");
        }
        for (Seg s : copy) {
            int flags;
            if (s.fin) flags = F_FIN | F_ACK;
            else flags = F_ACK | (s.data.length > 0 ? F_PSH : 0);
            net.sendTcp(this, flags, s.seq, rcvNxt, s.data, 0, s.data.length);
            s.sentAt = now;
        }
        rto = Math.min(RTO_MAX, rto * 2);
        cwndSegs = 1;
    }

    private void sendAck() {
        net.sendTcp(this, F_ACK, sndNxt, rcvNxt, null, 0, 0);
    }
}
