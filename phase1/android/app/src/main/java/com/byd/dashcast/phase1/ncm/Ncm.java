package com.byd.dashcast.phase1.ncm;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CDC-NCM 载荷层：NTB 组帧 / 解析 + 双线程 USB 收发泵。
 *
 * <p><b>线格式（CDC NCM 1.0，NTB-16 + 不追加 CRC）：</b>
 * <pre>
 *   NTB:  [ NTH16 (12B) ][ NDP16 (@wNdpIndex, 12) ][ datagram... ]
 *   NTH16: dwSignature(4)=0x484D434E "NCMH"
 *          wHeaderLength(2)=12  wSequence(2)  wBlockLength(2)  wNdpIndex(2)
 *   NDP16: dwSignature(4)=0x304D434E "NCM0"(NOCRC) / 0x314D434E "NCM1"(CRC)
 *          wLength(2)=8+4*(n+1)   wNextNdpIndex(2)=0
 *          dpe16[n]: wDatagramIndex(2) wDatagramLength(2)，末尾 1 项为 0 终止符
 *   datagram 起点 = align4(12 + wLength)，每条按 4 字节对齐
 * </pre>
 * Only negotiated NTB16/no-CRC is accepted. Unsupported framing is counted and rejected.
 */
public final class Ncm {

    private static final int NTH16_SIGN = 0x484D434E;      // "NCMH"
    private static final int NTH32_SIGN = 0x686D636E;      // "ncmh"
    private static final int NDP16_NOCRC_SIGN = 0x304D434E; // "NCM0"
    private static final int NDP16_CRC_SIGN = 0x314D434E;   // "NCM1"
    private static final int NDP32_NOCRC_SIGN = 0x306D636E; // "ncm0"
    private static final int NDP32_CRC_SIGN = 0x316D636E;   // "ncm1"

    public interface Sink {
        void onFrame(byte[] frame, int len);
    }

    public interface StateListener {
        void onNcmState(String text, boolean running);
    }

    private final Usb.Session s;
    private final Sink sink;

    private ThreadPoolExecutor pumps;
    private volatile boolean running;

    private final ArrayDeque<byte[]> txq = new ArrayDeque<byte[]>();
    private static final int TXQ_CAP = 512;

    /* ---- 由 GET_NTB_PARAMETERS 校准 ---- */
    private int ntbInMax = 16384;
    private int ntbOutMax = 16384;
    private int outMaxDatagrams = 8;
    private int outAlign = 4;
    private int outDivisor = 4;
    private int outRemainder;
    private int seq;
    private String paramText = "(未读取 NTB 参数，使用保守默认值)";

    /* ---- 统计 ---- */
    public volatile long rxFrames;
    public volatile long txFrames;
    public volatile long rxBytes;
    public volatile long txBytes;
    public volatile long rxNtb;
    public volatile long txNtb;
    public volatile long rxZlp;
    public volatile long rxBadNtb;
    public volatile long rxErr;
    /** bulk IN 返回负值但无数据可读的次数——绝大多数是超时，不是故障。 */
    public volatile long rxIdle;
    public volatile long txErr;
    public volatile long txDrop;
    /** 中断通知端点（0x82）收到的通知条数——>0 说明设备侧协议栈在主动说话。 */
    public volatile long intNotifs;
    public volatile String lastRxNote = "";
    public volatile String lastError = "";

    private volatile StateListener listener;

    public Ncm(Usb.Session s, Sink sink) {
        this.s = s;
        this.sink = sink;
    }

    public void setListener(StateListener l) {
        listener = l;
    }

    public String paramText() {
        return paramText;
    }

    public int ntbInMax() {
        return ntbInMax;
    }

    public int ntbOutMax() {
        return ntbOutMax;
    }

    public int queueDepth() {
        synchronized (txq) {
            return txq.size();
        }
    }

    /* ---------------- 启动 ---------------- */

    public void start(AtomicBoolean cancelled) throws IOException {
        if (running) return;
        NcmInitialization.Parameters parameters = NcmInitialization.initialize(s, s.descriptor, cancelled);
        ntbInMax = parameters.inBytes;
        ntbOutMax = parameters.outBytes;
        outMaxDatagrams = parameters.maxDatagrams;
        outAlign = parameters.outAlignment;
        outDivisor = parameters.outDivisor;
        outRemainder = parameters.outRemainder;
        paramText = "IN=" + ntbInMax + " OUT=" + ntbOutMax
                + " maxDg=" + outMaxDatagrams + " align=" + outAlign;
        LogHub.i("ncm", "NTB bounds negotiated: " + paramText);

        running = true;
        pumps = PumpExecutor.create("dashcast-ncm", 3);
        pumps.execute(this::rxLoop);
        pumps.execute(this::txLoop);
        if (s.epInt != null) {
            pumps.execute(this::intLoop);
        }
        notifyState("收发泵已启动", true);
        LogHub.i("ncm", "收发泵启动 rx buffer=" + (ntbInMax + 512) + "B");
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (pumps != null) {
            pumps.shutdownNow();
            try {
                if (!pumps.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    LogHub.w("ncm", "USB pumps did not terminate before session close");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        notifyState("收发泵已停止", false);
        LogHub.i("ncm", "收发泵停止。rx=" + rxFrames + " 帧/" + rxBytes
                + "B  tx=" + txFrames + " 帧/" + txBytes + "B  int=" + intNotifs);
    }

    public boolean isRunning() {
        return running;
    }

    /* ---------------- 发送 ---------------- */

    /** 由协议栈调用：把一帧以太网报文排进发送队列。 */
    public void send(byte[] frame, int len) {
        if (!running || frame == null || len < 14 || len > 1514 || len > frame.length) {
            txDrop++;
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(frame, 0, copy, 0, len);
        synchronized (txq) {
            if (txq.size() >= TXQ_CAP) {
                txq.pollFirst();     // 队满丢最老的，避免无界增长
                txDrop++;
            }
            txq.addLast(copy);
        }
    }

    private void txLoop() {
        UsbDeviceConnection conn = s.conn;
        UsbEndpoint ep = s.epOut;
        final int mps = Math.max(1, s.maxPacketOut);
        while (running) {
            List<byte[]> batch = new ArrayList<byte[]>();
            int budget = ntbOutMax - 512;
            try {
                synchronized (txq) {
                    while (!txq.isEmpty() && batch.size() < outMaxDatagrams) {
                        int sz = alignN(txq.peekFirst().length, outDivisor) + 64;
                        if (!batch.isEmpty() && sz > budget) break;
                        byte[] f = txq.pollFirst();
                        batch.add(f);
                        budget -= sz;
                    }
                }
            } catch (Throwable t) {
                LogHub.throwable("ncm", "取发送队列异常", t);
            }
            if (batch.isEmpty()) {
                sleep(20);
                continue;
            }
            byte[] ntb;
            try {
                ntb = buildNtb(batch);
            } catch (Throwable t) {
                LogHub.throwable("ncm", "组帧异常", t);
                txErr++;
                continue;
            }
            int r;
            try {
                r = conn.bulkTransfer(ep, ntb, 0, ntb.length, 1000);
            } catch (Throwable t) {
                LogHub.throwable("ncm", "bulkTransfer OUT 异常", t);
                txErr++;
                sleep(50);
                continue;
            }
            if (r == ntb.length) {
                txNtb++;
                txFrames += batch.size();
                txBytes += ntb.length;
                lastError = "";
                // NCM 规定：整包长度是 maxPacketSize 整数倍时，需补一个零长包标记传输结束
                if (ntb.length % mps == 0) {
                    try {
                        conn.bulkTransfer(ep, new byte[0], 0, 0, 200);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                txErr++;
                lastError = "bulk OUT 返回 " + r + "（期望 " + ntb.length + "）";
                if (txErr <= 3 || txErr % 50 == 0) LogHub.w("ncm", lastError);
                // 失败帧放回队首重试一次，避免直接丢
                synchronized (txq) {
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        if (txq.size() < TXQ_CAP) txq.addFirst(batch.get(i));
                    }
                }
                sleep(50);
            }
        }
    }

    private byte[] buildNtb(List<byte[]> frames) throws IOException {
        return Ntb16Codec.encode(frames, seq++, ntbOutMax, outAlign, outDivisor, outRemainder);
    }

    /* ---------------- 接收 ---------------- */

    private void rxLoop() {
        UsbDeviceConnection conn = s.conn;
        UsbEndpoint ep = s.epIn;
        byte[] buf = new byte[ntbInMax + 512];
        int consecErr = 0;
        boolean haltTried = false;
        while (running) {
            int r;
            try {
                r = conn.bulkTransfer(ep, buf, 0, buf.length, 250);
            } catch (Throwable t) {
                LogHub.throwable("ncm", "bulkTransfer IN 异常", t);
                rxErr++;
                sleep(80);
                continue;
            }
            if (r > 0) {
                consecErr = 0;
                haltTried = false;
                rxNtb++;
                rxBytes += r;
                try {
                    parse(buf, r);
                } catch (Throwable t) {
                    rxBadNtb++;
                    LogHub.throwable("ncm", "NTB 解析异常 len=" + r, t);
                }
            } else if (r == 0) {
                rxZlp++;
            } else {
                // Android 的 bulkTransfer 对超时与错误都返回 -1，无法区分。
                // 链路正常但暂时没数据是常态，因此这里记为「空闲」而不是「错误」，
                // 免得统计里一堆红字让人误判链路有问题。
                rxIdle++;
                if (running) {
                    consecErr++;
                    if (consecErr <= 3 || consecErr % 200 == 0) {
                        LogHub.d("ncm", "bulk IN 无数据 r=" + r + "（连续 " + consecErr + " 次）");
                    }
                    // 连续约 10s 一个字节都没有：按「端点可能 stall」处理一次
                    // clearHalt。若真是 halt，清完立即会恢复；若是纯空闲，这步无害。
                    if (consecErr >= 40 && !haltTried) {
                        haltTried = true;
                        boolean ok = clearHalt(conn, ep);
                        LogHub.i("ncm", "bulk IN 连续 " + consecErr
                                + " 次无数据，clearHalt(IN)=" + ok + "（自救）");
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
    private void intLoop() {
        UsbDeviceConnection conn = s.conn;
        UsbEndpoint ep = s.epInt;
        int mps = Math.max(8, ep.getMaxPacketSize());
        ByteBuffer bb = ByteBuffer.allocateDirect(mps);
        UsbRequest req = new UsbRequest();
        boolean inited;
        try {
            inited = req.initialize(conn, ep);
        } catch (Throwable t) {
            LogHub.throwable("ncm", "UsbRequest.initialize(中断端点) 异常", t);
            return;
        }
        if (!inited) {
            LogHub.w("ncm", "中断通知端点 UsbRequest.initialize 失败，通知泵退出");
            return;
        }
        boolean queued = false;
        while (running) {
            try {
                if (!queued) {
                    bb.clear();
                    if (!req.queue(bb)) {
                        sleep(120);
                        continue;
                    }
                    queued = true;
                }
                UsbRequest done = conn.requestWait(300);
                if (done != req) {
                    // 不是我们的请求完成（或出错返回 null），稍歇再重挂
                    sleep(60);
                    continue;
                }
                queued = false;
                intNotifs++;
                byte[] nb = new byte[Math.min(bb.position(), mps)];
                bb.position(0);
                bb.get(nb, 0, nb.length);
                if (intNotifs <= 6 || intNotifs % 50 == 0) {
                    String kind = nb.length >= 2
                            ? "type=0x" + Integer.toHexString(nb[1] & 0xFF) : "";
                    LogHub.i("ncm", "CDC 通知 #" + intNotifs + " len=" + nb.length
                            + " " + kind + " " + hex(nb, Math.min(8, nb.length)));
                }
            } catch (TimeoutException timeout) {
                // A wait timeout does not complete/cancel the URB. Keep waiting
                // on it instead of re-queuing an outstanding buffer.
                if (!running) {
                    break;
                }
            } catch (Throwable t) {
                LogHub.throwable("ncm", "中断通知泵异常", t);
                sleep(200);
            }
        }
        try {
            req.cancel();
            req.close();
        } catch (Throwable ignored) {
        }
    }

    private void parse(byte[] bytes, int length) {
        try {
            for (byte[] frame : Ntb16Codec.decode(bytes, length)) {
                rxFrames++;
                if (sink != null) {
                    sink.onFrame(frame, frame.length);
                }
            }
        } catch (IOException invalid) {
            rxBadNtb++;
            if (rxBadNtb <= 3) {
                LogHub.w("ncm", invalid.getMessage());
            }
        }
    }

    /* ---------------- 小工具 ---------------- */

    private void notifyState(final String text, final boolean run) {
        final StateListener l = listener;
        if (l == null) return;
        try {
            l.onNcmState(text, run);
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    /** Standard CLEAR_FEATURE(ENDPOINT_HALT); no hidden API is used. */
    private static boolean clearHalt(UsbDeviceConnection conn, UsbEndpoint ep) {
        try {
            return conn.controlTransfer(0x02, 1, 0, ep.getAddress(), null, 0, 1000) == 0;
        } catch (RuntimeException error) {
            LogHub.throwable("ncm", "CLEAR_FEATURE endpoint halt rejected", error);
            return false;
        }
    }

    private static int align4(int v) {
        return (v + 3) & ~3;
    }

    private static int alignN(int v, int n) {
        if (n <= 1) return v;
        return ((v + n - 1) / n) * n;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
