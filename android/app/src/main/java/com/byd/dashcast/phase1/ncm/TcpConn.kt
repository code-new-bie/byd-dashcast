package com.byd.dashcast.phase1.ncm

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * 精简 TCP 连接（主动打开方）。
 *
 * 设计取舍：本 App 是「连通性测试工具」，要的是**确定性**而非极致吞吐，
 * 因此只实现足以完成「握手 / 收发 / 优雅关闭 / 丢包自愈」的那一子集：
 *  - 不做乱序重组——USB NCM 链路本身不丢不乱序，真出现缺口就按缺 ACK 处理（会告警）；
 *  - 不做 SACK / 时间戳 / 窗口扩大；对端 SYN 里的 MSS 选项会被采纳；
 *  - 重传用「累积确认 + 回退 N」：收到 ACK 就裁剪未确认队列，超时或三重复 ACK 就重发队首起全部未确认段；
 *  - 发送窗口 = min(对端通告窗口, 拥塞窗口)；拥塞窗口从 1 段起，每个 ACK 翻倍到 32 段封顶，
 *    以容纳1920×720/30的高分辨率码率。
 *
 * 这些取舍在直连测试场景下足够，且行为可解释——出问题时日志能直接指出卡在哪一步。
 */
class TcpConn internal constructor(
    private val net: Net,
    @JvmField val dstIp: Int,
    @JvmField val dstPort: Int,
    @JvmField val srcPort: Int,
) {

    private class Seg {
        var seq = 0L
        var data = ByteArray(0)
        var sentAt = 0L
        var fin = false
    }

    private var state = CLOSED
    private var sndUna = 0L
    private var sndNxt = 0L
    private var rcvNxt = 0L
    private var sndWnd = 65535
    private var peerMss = MSS
    private var cwndSegs = 1
    private var rto = RTO_MIN

    private val unacked = ArrayDeque<Seg>()
    private val outQ = ArrayDeque<ByteArray>()
    private val inBuf = ByteArrayOutputStream()

    private var peerFin = false
    private var reset = false
    private var dupAcks = 0
    private var lastProgressAt = 0L
    private var closeStartedAt = 0L
    /** 已发出但未被确认的字节数。用计数而不是 sndNxt-sndUna，避开序号回绕的坑。 */
    private var outstanding = 0

    private var error: String? = null
    private var unexpectedHandshakePackets = 0
    @JvmField var connectLatencyMs = -1L
    @JvmField var establishedAt = -1L
    @JvmField var closedAt = -1L
    @JvmField var retransCount = 0
    @JvmField var txSegments = 0
    @JvmField var rxSegments = 0
    @JvmField var txBytes = 0L
    @JvmField var rxBytes = 0L

    @Synchronized
    fun state(): Int = state

    @Synchronized
    fun stateName(): String = when (state) {
        CLOSED -> "CLOSED"
        SYN_SENT -> "SYN_SENT"
        ESTABLISHED -> "ESTABLISHED"
        FIN_WAIT_1 -> "FIN_WAIT_1"
        FIN_WAIT_2 -> "FIN_WAIT_2"
        CLOSE_WAIT -> "CLOSE_WAIT"
        TIME_WAIT -> "TIME_WAIT"
        else -> "?"
    }

    @Synchronized
    fun isEstablished(): Boolean = state == ESTABLISHED

    @Synchronized
    fun isClosed(): Boolean = state == CLOSED

    @Synchronized
    fun error(): String? = error

    @Synchronized
    fun peerMssValue(): Int = peerMss

    @Synchronized
    fun available(): Int = inBuf.size()

    @Synchronized
    fun unackedBytes(): Int = outstanding

    @Synchronized
    fun statusLine(): String {
        val sb = StringBuilder()
        sb.append(stateName())
        sb.append(" sndUna=").append(sndUna and 0xFFFFFFFFL)
        sb.append(" sndNxt=").append(sndNxt and 0xFFFFFFFFL)
        sb.append(" rcvNxt=").append(rcvNxt and 0xFFFFFFFFL)
        sb.append(" win=").append(sndWnd)
        sb.append(" mss=").append(peerMss)
        sb.append(" 未确认=").append(unackedBytes()).append("B")
        sb.append(" 重传=").append(retransCount)
        sb.append(" tx=").append(txBytes).append("B rx=").append(rxBytes).append("B")
        if (error != null) sb.append(" 错误=").append(error)
        return sb.toString()
    }

    /* ---------------- 主动打开 ---------------- */

    @Synchronized
    fun startConnect(now: Long) {
        val isn = (now * 1000 + srcPort * 7) and 0x7FFFFFFFL
        sndUna = isn
        sndNxt = isn + 1
        state = SYN_SENT
        lastProgressAt = now
        net.sendTcp(this, F_SYN, isn, 0, null, 0, 0)
        LogHub.d("tcp", "-> SYN seq=" + (isn and 0xFFFFFFFFL) +
            " " + Wire.ip(dstIp) + ":" + dstPort)
    }

    /* ---------------- 收包 ---------------- */

    @Synchronized
    fun onPacket(flags: Int, seq: Long, ack: Long, win: Int,
        pl: ByteArray, off: Int, len: Int, mssOpt: Int, now: Long) {
        rxSegments++
        if ((flags and F_RST) != 0) {
            reset = true
            error = "对端 RST（" + Wire.ip(dstIp) + ":" + dstPort + "）"
            state = CLOSED
            closedAt = now
            monitorNotifyAll()
            LogHub.w("tcp", "<- RST：" + error)
            return
        }
        if ((flags and F_SYN) != 0 && (flags and F_ACK) != 0 && state == SYN_SENT) {
            val expect = sndNxt and 0xFFFFFFFFL
            if (ack != expect) {
                LogHub.w("tcp", "<- SYN/ACK 的 ack=" + ack + " 与期望 " + expect + " 不符，忽略")
                return
            }
            sndUna = sndNxt
            rcvNxt = (seq + 1) and 0xFFFFFFFFL
            if (mssOpt > 0) peerMss = minOf(MSS, maxOf(64, mssOpt))
            sndWnd = win
            state = ESTABLISHED
            establishedAt = now
            connectLatencyMs = now - lastProgressAt
            lastProgressAt = now
            sendAck()
            LogHub.i("tcp", "<- SYN/ACK 握手完成 " + connectLatencyMs + "ms  对端 MSS=" + peerMss)
            monitorNotifyAll()
            pump(now)
            return
        }

        if (state == SYN_SENT) {
            if (++unexpectedHandshakePackets <= 3) {
                LogHub.w("tcp", "TCP_HANDSHAKE_NON_SYN local_port=" + srcPort +
                    " flags=0x" + Integer.toHexString(flags) + " seq=" + seq +
                    " ack=" + ack + " len=" + len)
            }
            return
        }
        if (state == CLOSED) return

        var ackAdvanced = false
        if ((flags and F_ACK) != 0) {
            val u = sndUna and 0xFFFFFFFFL
            val a = ack and 0xFFFFFFFFL
            val diff = (a - u) and 0xFFFFFFFFL
            if (diff > outstanding && diff < 0x80000000L) {
                sendAck()
                return
            }
            sndWnd = win
            if (diff != 0L && diff < 0x80000000L && outstanding > 0) {
                // 累积确认把未确认队列裁剪掉
                var removed = 0
                while (!unacked.isEmpty()) {
                    val s = unacked.peekFirst()!!
                    val end = (s.seq + s.data.size + (if (s.fin) 1 else 0)) and 0xFFFFFFFFL
                    val d = (a - end) and 0xFFFFFFFFL
                    if (d == 0L || d < 0x80000000L) {
                        unacked.pollFirst()
                        removed += s.data.size + (if (s.fin) 1 else 0)
                    } else {
                        val partial = (a - s.seq) and 0xFFFFFFFFL
                        if (!s.fin && partial > 0 && partial < s.data.size) {
                            val consumed = partial.toInt()
                            s.seq = a
                            s.data = s.data.copyOfRange(consumed, s.data.size)
                            removed += consumed
                        }
                        break
                    }
                }
                outstanding -= removed
                if (outstanding < 0) outstanding = 0
                sndUna = a
                sndWnd = win
                if (removed > 0) {
                    rto = RTO_MIN
                    cwndSegs = minOf(CWND_MAX_SEGS, cwndSegs + 1)
                    ackAdvanced = true
                    dupAcks = 0
                    lastProgressAt = now
                }
                if (state == FIN_WAIT_1 && unacked.isEmpty()) {
                    state = FIN_WAIT_2
                }
                if (state == FIN_WAIT_2 && peerFin) {
                    state = TIME_WAIT
                    closedAt = now
                }
            } else if (diff == 0L && len == 0 && outstanding > 0) {
                dupAcks++
                if (dupAcks == 3) {
                    LogHub.w("tcp", "三重复 ACK，立即重传")
                    retransmitAll(now)
                }
            }
        }

        if (len > 0) {
            val rn = rcvNxt and 0xFFFFFFFFL
            val sq = seq and 0xFFFFFFFFL
            if (sq == rn) {
                if (len > 65536 - inBuf.size()) {
                    error = "TCP receive buffer limit"
                    reset = true
                    state = CLOSED
                    monitorNotifyAll()
                    return
                }
                rxBytes += len.toLong()
                inBuf.write(pl, off, len)
                rcvNxt = (seq + len) and 0xFFFFFFFFL
                sendAck()
                monitorNotifyAll()
            } else if (((sq - rn) and 0xFFFFFFFFL) < 0x80000000L) {
                // 已有数据的重复段：再确认一次，帮助对端推进
                sendAck()
            } else {
                // 前方出现缺口 —— NCM 链路上不该发生，记录以便定位
                LogHub.w("tcp", "收到乱序段 seq=" + sq + " 期望=" + rn + " len=" + len +
                    "（本实现不做乱序重组，按缺 ACK 处理）")
                sendAck()
            }
        }

        if ((flags and F_FIN) != 0 && ((seq + len) and 0xFFFFFFFFL) == rcvNxt && !peerFin) {
            rcvNxt = (rcvNxt + 1) and 0xFFFFFFFFL
            peerFin = true
            sendAck()
            if (state == ESTABLISHED) {
                state = CLOSE_WAIT
            } else if (state == FIN_WAIT_1) {
                state = TIME_WAIT
                closedAt = now
            } else if (state == FIN_WAIT_2) {
                state = TIME_WAIT
                closedAt = now
            }
            monitorNotifyAll()
            LogHub.d("tcp", "<- FIN，进入 " + stateName())
        }

        if (ackAdvanced || (!outQ.isEmpty() && len == 0)) pump(now)
    }

    /* ---------------- 发送 ---------------- */

    /** 把数据排入发送队列并推进窗口。返回 false 表示连接已不可用。 */
    @Synchronized
    fun enqueue(data: ByteArray?, len: Int): Boolean {
        if (state != ESTABLISHED && state != CLOSE_WAIT) return false
        if (data == null || len < 0 || len > data.size || len > 0x200018 || !outQ.isEmpty()) return false
        var off = 0
        while (off < len) {
            val n = minOf(peerMss, len - off)
            outQ.addLast(data.copyOfRange(off, off + n))
            off += n
        }
        return true
    }

    @Synchronized
    fun pump(now: Long) {
        if (state != ESTABLISHED && state != CLOSE_WAIT) return
        val budget = maxOf(1, minOf(cwndSegs, CWND_MAX_SEGS)) * peerMss
        var allowed = minOf(if (sndWnd <= 0) 0 else sndWnd, budget) - outstanding
        while (allowed > 0 && !outQ.isEmpty()) {
            val chunk = outQ.pollFirst()!!
            val n = minOf(chunk.size, minOf(allowed, peerMss))
            val s = Seg()
            s.seq = sndNxt
            s.data = chunk.copyOf(n)
            s.sentAt = now
            net.sendTcp(this, F_ACK or F_PSH, sndNxt, rcvNxt, s.data, 0, n)
            txSegments++
            txBytes += n.toLong()
            unacked.addLast(s)
            outstanding += n
            sndNxt = (sndNxt + n) and 0xFFFFFFFFL
            allowed -= n
            if (n < chunk.size) {
                outQ.addFirst(chunk.copyOfRange(n, chunk.size))
            }
        }
    }

    /** 阻塞发送：等所有字节被确认，或超时。返回实际入网字节数。 */
    fun send(data: ByteArray?, len: Int, timeoutMs: Int): Int {
        val deadline = Net.now() + timeoutMs
        synchronized(this) {
            if (state != ESTABLISHED) return -1
            if (!enqueue(data, len)) return -1
            pump(Net.now())
            while (true) {
                if (outQ.isEmpty() && unacked.isEmpty()) return len
                if (state == CLOSED || reset || peerFin) return -1
                val left = deadline - Net.now()
                if (left <= 0) {
                    LogHub.w("tcp", "send 超时：仍有 " + outQ.size + " 段待发 / " +
                        unackedBytes() + "B 未确认")
                    return -1
                }
                try {
                    monitorWait(minOf(left, 200L))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                pump(Net.now())
            }
        }
    }

    /** 阻塞读取，超时返回 0；返回 -1 表示连接已关闭且无剩余数据。 */
    fun read(buf: ByteArray, off: Int, max: Int, timeoutMs: Int): Int {
        val deadline = Net.now() + timeoutMs
        synchronized(this) {
            while (true) {
                if (inBuf.size() > 0) {
                    val all = inBuf.toByteArray()
                    inBuf.reset()
                    val n = minOf(max, all.size)
                    System.arraycopy(all, 0, buf, off, n)
                    if (n < all.size) inBuf.write(all, n, all.size - n)
                    return n
                }
                if (state == CLOSED || reset || peerFin) return -1
                val left = deadline - Net.now()
                if (left <= 0) return 0
                try {
                    monitorWait(minOf(left, 200L))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
        }
    }

    /** 把已收数据全部读出成字符串（用于抓一小段响应）。 */
    fun drainText(maxBytes: Int, quietMs: Int): String {
        val deadline = Net.now() + quietMs
        val sb = StringBuilder()
        val buf = ByteArray(2048)
        while (sb.length < maxBytes) {
            val r = read(buf, 0, minOf(buf.size, maxBytes - sb.length),
                maxOf(1L, deadline - Net.now()).toInt())
            if (r <= 0) break
            for (i in 0 until r) {
                val c = (buf[i].toInt() and 0xFF).toChar()
                sb.append(if (c.code >= 32 && c.code < 127) c else '.')
            }
            if (Net.now() >= deadline) break
        }
        return sb.toString()
    }

    /* ---------------- 关闭 ---------------- */

    fun close(timeoutMs: Int) {
        val now = Net.now()
        synchronized(this) {
            if (state == CLOSED || state == TIME_WAIT) {
                state = CLOSED
                closedAt = now
                monitorNotifyAll()
                net.forget(this)
                return
            }
            if (state == SYN_SENT || state == ESTABLISHED || state == CLOSE_WAIT) {
                net.sendTcp(this, F_FIN or F_ACK, sndNxt, rcvNxt, null, 0, 0)
                val s = Seg()
                s.seq = sndNxt
                s.data = ByteArray(0)
                s.fin = true
                s.sentAt = now
                unacked.addLast(s)
                outstanding += 1       // FIN 占一个序号，必须计入未确认
                sndNxt = (sndNxt + 1) and 0xFFFFFFFFL
                state = FIN_WAIT_1
                closeStartedAt = now
                LogHub.d("tcp", "-> FIN，进入 FIN_WAIT_1")
            }
            val deadline = now + timeoutMs
            while (state != CLOSED && state != TIME_WAIT && state != CLOSE_WAIT) {
                val left = deadline - Net.now()
                if (left <= 0) break
                try {
                    monitorWait(minOf(left, 200L))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            retransOff()
            state = CLOSED
            closedAt = Net.now()
            monitorNotifyAll()
        }
        net.forget(this)
    }

    fun retransOff() {
        // 主动关闭后若对端（指 QNX 侧）不回 FIN，也允许直接释放本地连接
        try {
            net.forget(this)
        } catch (ignored: Throwable) {
        }
    }

    /** 强制中止：发 RST 并就地释放。 */
    fun abort() {
        synchronized(this) {
            if (state != CLOSED) {
                net.sendTcp(this, F_RST or F_ACK, sndNxt, rcvNxt, null, 0, 0)
                LogHub.d("tcp", "-> RST（主动中止）")
            }
            state = CLOSED
            closedAt = Net.now()
            monitorNotifyAll()
        }
        net.forget(this)
    }

    /* ---------------- 定时器 ---------------- */

    fun onTick(now: Long) {
        synchronized(this) {
            var needRetrans = false
            if (!unacked.isEmpty()) {
                val first = unacked.peekFirst()!!
                if (now - first.sentAt >= rto) needRetrans = true
            }
            if (state == SYN_SENT && now - lastProgressAt >= rto) needRetrans = true
            if (state == CLOSED) return
            if (needRetrans) {
                if (state == SYN_SENT) {
                    val isn = sndUna and 0xFFFFFFFFL
                    net.sendTcp(this, F_SYN, isn, 0, null, 0, 0)
                    lastProgressAt = now
                    retransCount++
                    rto = minOf(RTO_MAX, rto * 2)
                    LogHub.w("tcp", "SYN 超时重传（第 " + retransCount + " 次）")
                } else {
                    retransmitAll(now)
                }
            }
            // 关闭阶段长时间无望则强制收尾，避免连接表泄漏
            if ((state == FIN_WAIT_1 || state == FIN_WAIT_2) && now - closeStartedAt > 8000) {
                LogHub.w("tcp", "关闭流程超时，强制释放（" + stateName() + "）")
                state = CLOSED
                closedAt = now
                monitorNotifyAll()
                net.forget(this)
            }
        }
    }

    private fun retransmitAll(now: Long) {
        if (unacked.isEmpty()) return
        retransCount++
        val copy = ArrayList(unacked)
        if (retransCount <= 3 || retransCount % 10 == 0) {
            LogHub.w("tcp", "超时重传 " + copy.size + " 段（第 " + retransCount + " 次）")
        }
        for (s in copy) {
            val flags = if (s.fin) F_FIN or F_ACK else F_ACK or (if (s.data.isNotEmpty()) F_PSH else 0)
            net.sendTcp(this, flags, s.seq, rcvNxt, s.data, 0, s.data.size)
            s.sentAt = now
        }
        rto = minOf(RTO_MAX, rto * 2)
        cwndSegs = 1
    }

    private fun sendAck() {
        net.sendTcp(this, F_ACK, sndNxt, rcvNxt, null, 0, 0)
    }

    companion object {
        const val CLOSED = 0
        const val SYN_SENT = 1
        const val ESTABLISHED = 2
        const val FIN_WAIT_1 = 3
        const val FIN_WAIT_2 = 4
        const val CLOSE_WAIT = 5
        const val TIME_WAIT = 6

        const val F_FIN = 0x01
        const val F_SYN = 0x02
        const val F_RST = 0x04
        const val F_PSH = 0x08
        const val F_ACK = 0x10

        private const val MSS = 1460
        private const val RTO_MIN = 400L
        private const val RTO_MAX = 4000L
        private const val CWND_MAX_SEGS = 32
    }
}
