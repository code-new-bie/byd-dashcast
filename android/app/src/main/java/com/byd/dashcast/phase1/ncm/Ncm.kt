package com.byd.dashcast.phase1.ncm

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CDC-NCM 载荷层：NTB 组帧 / 解析 + 双线程 USB 收发泵。
 *
 * **线格式（CDC NCM 1.0，NTB-16 + 不追加 CRC）：**
 * ```
 *   NTB:  [ NTH16 (12B) ][ NDP16 (@wNdpIndex, 12) ][ datagram... ]
 *   NTH16: dwSignature(4)=0x484D434E "NCMH"
 *          wHeaderLength(2)=12  wSequence(2)  wBlockLength(2)  wNdpIndex(2)
 *   NDP16: dwSignature(4)=0x304D434E "NCM0"(NOCRC) / 0x314D434E "NCM1"(CRC)
 *          wLength(2)=8+4*(n+1)   wNextNdpIndex(2)=0
 *          dpe16[n]: wDatagramIndex(2) wDatagramLength(2)，末尾 1 项为 0 终止符
 *   datagram 起点 = align4(12 + wLength)，每条按 4 字节对齐
 * ```
 * Only negotiated NTB16/no-CRC is accepted. Unsupported framing is counted and rejected.
 */
class Ncm(private val s: Usb.Session, private val sink: Sink?) {

    fun interface Sink {
        fun onFrame(frame: ByteArray, len: Int)
    }

    fun interface StateListener {
        fun onNcmState(text: String, running: Boolean)
    }

    private var pumps: ThreadPoolExecutor? = null
    @Volatile private var running = false

    private val txq = ArrayDeque<ByteArray>()

    /* ---- 由 GET_NTB_PARAMETERS 校准 ---- */
    private var ntbInMax = 16384
    private var ntbOutMax = 16384
    private var outMaxDatagrams = 8
    private var outAlign = 4
    private var outDivisor = 4
    private var outRemainder = 0
    private var seq = 0
    private var paramText = "(未读取 NTB 参数，使用保守默认值)"

    /* ---- 统计 ---- */
    @Volatile @JvmField var rxFrames = 0L
    @Volatile @JvmField var txFrames = 0L
    @Volatile @JvmField var rxBytes = 0L
    @Volatile @JvmField var txBytes = 0L
    @Volatile @JvmField var rxNtb = 0L
    @Volatile @JvmField var txNtb = 0L
    @Volatile @JvmField var rxZlp = 0L
    @Volatile @JvmField var rxBadNtb = 0L
    @Volatile @JvmField var rxErr = 0L
    /** bulk IN 返回负值但无数据可读的次数——绝大多数是超时，不是故障。 */
    @Volatile @JvmField var rxIdle = 0L
    @Volatile @JvmField var txErr = 0L
    @Volatile @JvmField var txDrop = 0L
    /** 中断通知端点（0x82）收到的通知条数——>0 说明设备侧协议栈在主动说话。 */
    @Volatile @JvmField var intNotifs = 0L
    @Volatile @JvmField var lastRxNote = ""
    @Volatile @JvmField var lastError = ""

    @Volatile private var listener: StateListener? = null

    fun setListener(l: StateListener?) {
        listener = l
    }

    fun paramText(): String = paramText

    fun ntbInMax(): Int = ntbInMax

    fun ntbOutMax(): Int = ntbOutMax

    fun queueDepth(): Int = synchronized(txq) { txq.size }

    /* ---------------- 启动 ---------------- */

    @Throws(IOException::class)
    fun start(cancelled: AtomicBoolean?) {
        if (running) return
        val parameters = NcmInitialization.initialize(s, s.descriptor, cancelled)
        ntbInMax = parameters.inBytes
        ntbOutMax = parameters.outBytes
        outMaxDatagrams = parameters.maxDatagrams
        outAlign = parameters.outAlignment
        outDivisor = parameters.outDivisor
        outRemainder = parameters.outRemainder
        paramText = "IN=" + ntbInMax + " OUT=" + ntbOutMax +
            " maxDg=" + outMaxDatagrams + " align=" + outAlign
        LogHub.i("ncm", "NTB bounds negotiated: " + paramText)

        running = true
        val executor = PumpExecutor.create("dashcast-ncm", 3)
        pumps = executor
        executor.execute { rxLoop() }
        executor.execute { txLoop() }
        if (s.epInt != null) {
            executor.execute { intLoop() }
        }
        notifyState("收发泵已启动", true)
        LogHub.i("ncm", "收发泵启动 rx buffer=" + (ntbInMax + 512) + "B")
    }

    fun stop() {
        if (!running) return
        running = false
        val executor = pumps
        if (executor != null) {
            executor.shutdownNow()
            try {
                if (!executor.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                    LogHub.w("ncm", "USB pumps did not terminate before session close")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        notifyState("收发泵已停止", false)
        LogHub.i("ncm", "收发泵停止。rx=" + rxFrames + " 帧/" + rxBytes +
            "B  tx=" + txFrames + " 帧/" + txBytes + "B  int=" + intNotifs)
    }

    fun isRunning(): Boolean = running

    /** A timeout warning is not proof that the owned pump workers terminated. */
    fun stoppedConfirmed(): Boolean {
        val executor = pumps
        return !running && (executor == null || executor.isTerminated)
    }

    /* ---------------- 发送 ---------------- */

    /** 由协议栈调用：把一帧以太网报文排进发送队列。 */
    fun send(frame: ByteArray?, len: Int) {
        if (!running || frame == null || len < 14 || len > 1514 || len > frame.size) {
            txDrop++
            return
        }
        val copy = frame.copyOf(len)
        synchronized(txq) {
            if (txq.size >= TXQ_CAP) {
                txq.pollFirst()     // 队满丢最老的，避免无界增长
                txDrop++
            }
            txq.addLast(copy)
            txq.monitorNotifyAll()
        }
    }

    private fun txLoop() {
        val conn: UsbDeviceConnection = s.conn
        val ep: UsbEndpoint = s.epOut!!
        val mps = maxOf(1, s.maxPacketOut)
        while (running) {
            val batch = ArrayList<ByteArray>()
            var budget = ntbOutMax - 512
            try {
                synchronized(txq) {
                    if (txq.isEmpty() && running) {
                        txq.monitorWait(20)
                    }
                    while (!txq.isEmpty() && batch.size < outMaxDatagrams) {
                        val sz = alignN(txq.peekFirst()!!.size, outDivisor) + 64
                        if (batch.isNotEmpty() && sz > budget) break
                        val f = txq.pollFirst()!!
                        batch.add(f)
                        budget -= sz
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (t: Throwable) {
                LogHub.throwable("ncm", "取发送队列异常", t)
            }
            if (batch.isEmpty()) {
                continue
            }
            val ntb: ByteArray
            try {
                ntb = buildNtb(batch)
            } catch (t: Throwable) {
                LogHub.throwable("ncm", "组帧异常", t)
                txErr++
                continue
            }
            val r: Int
            try {
                r = conn.bulkTransfer(ep, ntb, 0, ntb.size, 1000)
            } catch (t: Throwable) {
                LogHub.throwable("ncm", "bulkTransfer OUT 异常", t)
                txErr++
                sleep(50)
                continue
            }
            if (r == ntb.size) {
                txNtb++
                txFrames += batch.size.toLong()
                txBytes += ntb.size.toLong()
                lastError = ""
                // NCM 规定：整包长度是 maxPacketSize 整数倍时，需补一个零长包标记传输结束
                if (ntb.size % mps == 0) {
                    try {
                        conn.bulkTransfer(ep, ByteArray(0), 0, 0, 200)
                    } catch (ignored: Throwable) {
                    }
                }
            } else {
                txErr++
                lastError = "bulk OUT 返回 " + r + "（期望 " + ntb.size + "）"
                if (txErr <= 3 || txErr % 50 == 0L) LogHub.w("ncm", lastError)
                // 失败帧放回队首重试一次，避免直接丢
                synchronized(txq) {
                    for (i in batch.indices.reversed()) {
                        if (txq.size < TXQ_CAP) txq.addFirst(batch[i])
                    }
                }
                sleep(50)
            }
        }
    }

    @Throws(IOException::class)
    private fun buildNtb(frames: List<ByteArray>): ByteArray =
        Ntb16Codec.encode(frames, seq++, ntbOutMax, outAlign, outDivisor, outRemainder)

    /* ---------------- 接收 ---------------- */

    private fun rxLoop() {
        val conn: UsbDeviceConnection = s.conn
        val ep: UsbEndpoint = s.epIn!!
        val buf = ByteArray(ntbInMax + 512)
        var consecErr = 0
        var haltTried = false
        while (running) {
            val r: Int
            try {
                r = conn.bulkTransfer(ep, buf, 0, buf.size, 250)
            } catch (t: Throwable) {
                LogHub.throwable("ncm", "bulkTransfer IN 异常", t)
                rxErr++
                sleep(80)
                continue
            }
            if (r > 0) {
                consecErr = 0
                haltTried = false
                rxNtb++
                rxBytes += r.toLong()
                try {
                    parse(buf, r)
                } catch (t: Throwable) {
                    rxBadNtb++
                    LogHub.throwable("ncm", "NTB 解析异常 len=" + r, t)
                }
            } else if (r == 0) {
                rxZlp++
            } else {
                // Android 的 bulkTransfer 对超时与错误都返回 -1，无法区分。
                // 链路正常但暂时没数据是常态，因此这里记为「空闲」而不是「错误」，
                // 免得统计里一堆红字让人误判链路有问题。
                rxIdle++
                if (running) {
                    consecErr++
                    if (consecErr <= 3 || consecErr % 200 == 0) {
                        LogHub.d("ncm", "bulk IN 无数据 r=" + r + "（连续 " + consecErr + " 次）")
                    }
                    // 连续约 10s 一个字节都没有：按「端点可能 stall」处理一次
                    // clearHalt。若真是 halt，清完立即会恢复；若是纯空闲，这步无害。
                    if (consecErr >= 40 && !haltTried) {
                        haltTried = true
                        val ok = clearHalt(conn, ep)
                        LogHub.i("ncm", "bulk IN 连续 " + consecErr +
                            " 次无数据，clearHalt(IN)=" + ok + "（自救）")
                    }
                }
            }
        }
    }

    /**
     * 中断通知泵：持续挂一个读在控制接口的中断 IN 端点上。
     * 走 UsbRequest（usbfs 的 bulk ioctl 不收 interrupt 端点，UsbRequest 才是正路）。
     * 通知内容按 CDC 规范是 8 字节：bmRequestType/bNotification/wValue/wLength + data，
     * 典型为 NETWORK_CONNECTION(0x00) 与 CONNECTION_SPEED_CHANGE(0x2A)。
     */
    private fun intLoop() {
        val conn: UsbDeviceConnection = s.conn
        val ep: UsbEndpoint = s.epInt ?: return
        val mps = maxOf(8, ep.maxPacketSize)
        val bb = ByteBuffer.allocateDirect(mps)
        val req = UsbRequest()
        val inited: Boolean
        try {
            inited = req.initialize(conn, ep)
        } catch (t: Throwable) {
            LogHub.throwable("ncm", "UsbRequest.initialize(中断端点) 异常", t)
            return
        }
        if (!inited) {
            LogHub.w("ncm", "中断通知端点 UsbRequest.initialize 失败，通知泵退出")
            return
        }
        var queued = false
        while (running) {
            try {
                if (!queued) {
                    bb.clear()
                    if (!req.queue(bb)) {
                        sleep(120)
                        continue
                    }
                    queued = true
                }
                val done = conn.requestWait(300)
                if (done !== req) {
                    // 不是我们的请求完成（或出错返回 null），稍歇再重挂
                    sleep(60)
                    continue
                }
                queued = false
                intNotifs++
                val nb = ByteArray(minOf(bb.position(), mps))
                bb.position(0)
                bb.get(nb, 0, nb.size)
                if (intNotifs <= 6 || intNotifs % 50 == 0L) {
                    val kind = if (nb.size >= 2) "type=0x" + Integer.toHexString(nb[1].toInt() and 0xFF) else ""
                    LogHub.i("ncm", "CDC 通知 #" + intNotifs + " len=" + nb.size +
                        " " + kind + " " + hex(nb, minOf(8, nb.size)))
                }
            } catch (timeout: TimeoutException) {
                // A wait timeout does not complete/cancel the URB. Keep waiting
                // on it instead of re-queuing an outstanding buffer.
                if (!running) {
                    break
                }
            } catch (t: Throwable) {
                LogHub.throwable("ncm", "中断通知泵异常", t)
                sleep(200)
            }
        }
        try {
            req.cancel()
            req.close()
        } catch (ignored: Throwable) {
        }
    }

    private fun parse(bytes: ByteArray, length: Int) {
        try {
            for (frame in Ntb16Codec.decode(bytes, length)) {
                rxFrames++
                sink?.onFrame(frame, frame.size)
            }
        } catch (invalid: IOException) {
            rxBadNtb++
            if (rxBadNtb <= 3) {
                LogHub.w("ncm", invalid.message.toString())
            }
        }
    }

    /* ---------------- 小工具 ---------------- */

    private fun notifyState(text: String, run: Boolean) {
        val l = listener ?: return
        try {
            l.onNcmState(text, run)
        } catch (ignored: Throwable) {
        }
    }

    private companion object {
        const val TXQ_CAP = 512

        fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (ignored: Throwable) {
            }
        }

        /** Standard CLEAR_FEATURE(ENDPOINT_HALT); no hidden API is used. */
        fun clearHalt(conn: UsbDeviceConnection, ep: UsbEndpoint): Boolean {
            return try {
                conn.controlTransfer(0x02, 1, 0, ep.address, null, 0, 1000) == 0
            } catch (error: RuntimeException) {
                LogHub.throwable("ncm", "CLEAR_FEATURE endpoint halt rejected", error)
                false
            }
        }

        fun alignN(v: Int, n: Int): Int {
            if (n <= 1) return v
            return ((v + n - 1) / n) * n
        }

        fun hex(b: ByteArray, n: Int): String {
            val sb = StringBuilder()
            for (i in 0 until n) {
                if (i > 0) sb.append(' ')
                sb.append(String.format(Locale.US, "%02x", b[i].toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
