package com.byd.dashcast.phase1;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.byd.dashcast.phase1.ncm.LogHub;
import com.byd.dashcast.phase1.ncm.Ncm;
import com.byd.dashcast.phase1.ncm.Net;
import com.byd.dashcast.phase1.ncm.Prefs;
import com.byd.dashcast.phase1.ncm.TcpConn;
import com.byd.dashcast.phase1.ncm.Usb;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One explicitly authorized, user-space NCM session; no IP/route/system permissions.
 * @author Codex
 * @date 2026/10/10
 */
public final class UsbNcmSession implements Closeable {
    private final Usb.Session usb;
    private final Net network;
    private final Ncm ncm;

    private UsbNcmSession(Usb.Session usb, Net network, Ncm ncm) {
        this.usb = usb;
        this.network = network;
        this.ncm = ncm;
    }

    /** Descriptor-only qualification; the role policy checks target VID/PID separately. */
    static boolean isNcmDevice(UsbDevice device) {
        boolean ncm = false;
        boolean storage = false;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface item = device.getInterface(index);
            ncm |= item.getInterfaceClass() == 2 && item.getInterfaceSubclass() == 13;
            storage |= item.getInterfaceClass() == 8;
        }
        return ncm && !storage;
    }

    public static UsbDevice findDevice(Context context) throws IOException {
        UsbManager manager = Usb.mgr(context);
        if (manager == null) {
            throw new IOException("UsbManager unavailable");
        }
        UsbDevice selected = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (!isNcmDevice(device)) {
                continue;
            }
            if (selected != null) {
                throw new IOException("Multiple NCM devices; connect only the target instrument");
            }
            selected = device;
        }
        if (selected == null) {
            throw new IOException("No dedicated NCM device. Switch instrument and connect its USB first");
        }
        return selected;
    }

    public static UsbNcmSession open(Context context, AtomicBoolean cancelled) throws IOException {
        UsbDevice device = findDevice(context);
        Usb.Session usb = null;
        Net network = null;
        Ncm ncm = null;
        try {
            usb = Usb.open(context, device);
            final Ncm[] pump = new Ncm[1];
            network = new Net((frame, length) -> {
                if (pump[0] != null) {
                    pump[0].send(frame, length);
                }
            });
            network.configure(Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, Prefs.DEF_PEER_MAC,
                    Prefs.DEF_PEER_IP, 30, 1500, true);
            ncm = new Ncm(usb, network);
            pump[0] = ncm;
            network.start();
            ncm.start(cancelled);
            return new UsbNcmSession(usb, network, ncm);
        } catch (Exception error) {
            if (network != null) {
                network.stop();
            }
            if (ncm != null) {
                ncm.stop();
            }
            if (usb != null) {
                if (!usb.close()) {
                    error.addSuppressed(new IOException("USB_DATA_ALT0_CLEANUP_UNCONFIRMED; unplug before role change"));
                }
            }
            LogHub.throwable("session", "NCM_OPEN_FAILED before TCP/video", error);
            throw new IOException("USB NCM open failed: " + error.getMessage(), error);
        }
    }

    public Channel connect(int port, AtomicBoolean cancelled) throws IOException {
        if (port != NoAuthVideoProtocol.PORT) {
            throw new IOException("Only the DCV1 private USB video port is permitted");
        }
        if (cancelled == null || cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Video verification cancelled");
        }
        TcpConn connection = network.connect(network.peerIp(), port, 5000);
        if (!connection.isEstablished()) {
            String error = connection.error();
            connection.abort();
            throw new IOException("Instrument TCP port " + port + " unavailable: " + error);
        }
        return new Channel(connection, cancelled);
    }

    public String statistics() {
        return network.statsLine() + "\nNCM tx=" + ncm.txNtb + " rx=" + ncm.rxNtb
                + " invalid=" + ncm.rxBadNtb + " txErrors=" + ncm.txErr + " dropped=" + ncm.txDrop;
    }

    @Override
    public void close() throws IOException {
        network.stop();
        ncm.stop();
        boolean restored = usb.close();
        LogHub.i("session", "USB released; instrument role NOT automatically changed");
        if (!restored) {
            throw new IOException("USB data deactivation unconfirmed; stop testing and unplug before role change");
        }
    }

    /** Single bounded TCP stream over NCM, with no alternate network fallback. */
    public static final class Channel implements NoAuthVideoTransport {
        private final TcpConn connection;
        private final AtomicBoolean cancelled;
        private boolean cleanup;

        private Channel(TcpConn connection, AtomicBoolean cancelled) {
            this.connection = connection;
            this.cancelled = cancelled;
        }

        public void write(byte[] data) throws IOException {
            checkCancellation();
            if (connection.send(data, data.length, 5000) != data.length) {
                throw new IOException("NCM TCP write not acknowledged: " + connection.statusLine()
                        + " peer_error=" + connection.error());
            }
        }

        public byte[] readExact(int count) throws IOException {
            if (count < 1 || count > NoAuthVideoProtocol.OFFER_BYTES) {
                throw new IOException("DCV1 reply read limit");
            }
            // Error/STOP replies now follow owned media teardown (six bounded
            // 2s IPCs at worst). Never abandon USB before the cleanup reply.
            long deadline = System.nanoTime() + 15_000_000_000L;
            byte[] result = new byte[count];
            int offset = 0;
            while (offset < count && System.nanoTime() < deadline) {
                checkCancellation();
                int received = connection.read(result, offset, count - offset, 200);
                if (received < 0) {
                    throw new IOException("Instrument closed the NCM TCP connection");
                }
                if (received == 0) {
                    continue;
                }
                offset += received;
            }
            if (offset == count) {
                return result;
            }
            throw new IOException("DCV1 binary reply timeout");
        }

        @Override
        public void beginCleanup() {
            cleanup = true;
        }

        private void checkCancellation() throws IOException {
            if ((!cleanup && cancelled.get()) || Thread.currentThread().isInterrupted()) {
                throw new IOException("Video verification cancelled");
            }
        }

        @Override
        public void close() {
            connection.close(600);
        }
    }
}
