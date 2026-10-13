package com.byd.dashcast.phase1;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

/**
 * Pins RTP to the explicitly selected Android Ethernet Network. No Wi-Fi/cellular fallback.
 * A kernel/Framework USB NIC is required; this class does not force-claim a USB device.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class UsbRtpSender implements Closeable, RtpTransportPacketizer.DatagramSink {
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private final DatagramSocket socket;
    private final String description;
    private long packets;
    private long bytes;

    /** @param context application; @param interfaceName confirmed USB interface;
     * @param target literal same-subnet IPv4; @param port receiver port; @throws IOException on no safe route */
    public UsbRtpSender(Context context, String interfaceName, String target, int port) throws IOException {
        if (interfaceName == null || interfaceName.isEmpty() || !IPV4.matcher(target).matches()
                || port < 1024 || port > 65535) {
            throw new IOException("Specify USB interface, literal IPv4 and port 1024..65535");
        }
        String[] parts = target.split("\\.");
        byte[] octets = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            int octet = Integer.parseInt(parts[index]);
            if (octet > 255) {
                throw new IOException("Invalid IPv4 octet");
            }
            octets[index] = (byte)octet;
        }
        InetAddress destination = InetAddress.getByAddress(octets);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || destination.isLoopbackAddress() || destination.isMulticastAddress()
                || destination.isAnyLocalAddress()) {
            throw new IOException("No valid USB network target");
        }
        Network selected = null;
        LinkProperties properties = null;
        for (Network candidate : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
            LinkProperties links = manager.getLinkProperties(candidate);
            if (caps != null && links != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    && interfaceName.equals(links.getInterfaceName())) {
                selected = candidate;
                properties = links;
                break;
            }
        }
        if (selected == null || properties == null) {
            throw new IOException("No Framework Ethernet Network for " + interfaceName
                    + "; enumerate/configure USB NIC first. No alternative network used.");
        }
        InetAddress source = findDirectAddress(properties, destination);
        socket = new DatagramSocket(null);
        try {
            selected.bindSocket(socket);
            socket.bind(new InetSocketAddress(source, 0));
            socket.connect(destination, port);
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
        description = "Network=" + selected + " iface=" + interfaceName + " local=" + source.getHostAddress()
                + " target=" + target + ":" + port;
    }

    /** @return actual socket binding */
    public String description() {
        return description;
    }

    /** @return local send counters only; no receiver/display acknowledgement */
    public String statistics() {
        return "RTP datagrams sent=" + packets + " bytes=" + bytes + " (not receiver acknowledgement)";
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        socket.send(new DatagramPacket(data, length));
        packets++;
        bytes += length;
    }

    @Override
    public void close() {
        socket.close();
    }

    private static InetAddress findDirectAddress(LinkProperties properties, InetAddress destination) throws IOException {
        long remote = addressValue(destination);
        for (LinkAddress link : properties.getLinkAddresses()) {
            int prefix = link.getPrefixLength();
            if (!(link.getAddress() instanceof Inet4Address) || prefix < 1 || prefix > 30) {
                continue;
            }
            long local = addressValue(link.getAddress());
            long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            long host = remote & (~mask & 0xffffffffL);
            if ((local & mask) == (remote & mask) && local != remote && host != 0
                    && host != (~mask & 0xffffffffL)) {
                return link.getAddress();
            }
        }
        throw new IOException("Target is not a distinct unicast host on the selected USB subnet");
    }

    private static long addressValue(InetAddress address) {
        long value = 0;
        for (byte octet : address.getAddress()) {
            value = (value << 8) | (octet & 0xff);
        }
        return value;
    }
}
