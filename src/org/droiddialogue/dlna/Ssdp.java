package org.droiddialogue.dlna;

import org.droiddialogue.core.Dbg;
import org.droiddialogue.core.NetUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSDP, the discovery part of UPnP: answers M-SEARCH requests, announces the device with NOTIFY
 * alive messages and says goodbye when it stops. Home Assistant finds the speaker through this.
 */
public final class Ssdp {
    public static final String GROUP = "239.255.255.250";
    public static final int STANDARD_PORT = 1900;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final int MAX_AGE = 1800;
    private static final long RENEW_MS = 10 * 60 * 1000L;

    private final String uuid;
    private final int httpPort;
    private final int port;
    private final boolean multicast;
    private final String server;
    private final String[] types;
    private final AtomicInteger responders = new AtomicInteger();
    private final Random random = new Random();
    private volatile MulticastSocket socket;
    private volatile boolean running;

    /**
     * @param port      the SSDP port to listen on (1900, or 0 to pick one for tests)
     * @param multicast join the multicast group and announce; false for tests on loopback
     */
    public Ssdp(String uuid, int httpPort, int port, boolean multicast, String server) {
        this.uuid = uuid;
        this.httpPort = httpPort;
        this.port = port;
        this.multicast = multicast;
        this.server = server;
        this.types = new String[] {
            "upnp:rootdevice",
            "uuid:" + uuid,
            Descriptions.DEVICE_TYPE,
            Descriptions.AV_TRANSPORT.type,
            Descriptions.RENDERING_CONTROL.type,
            Descriptions.CONNECTION_MANAGER.type
        };
    }

    public String[] types() {
        return types.clone();
    }

    /** Starts listening and returns the port. Throws if the port is taken or the network is unusable. */
    public int start() throws IOException {
        MulticastSocket ms = new MulticastSocket(null);
        ms.setReuseAddress(true);
        ms.bind(new InetSocketAddress(port));
        if (multicast) {
            joinGroups(ms);
        }
        socket = ms;
        running = true;
        final MulticastSocket fs = ms;
        Thread t = new Thread(new Runnable() {
            public void run() {
                listen(fs);
            }
        }, "ssdp-listen");
        t.setDaemon(true);
        t.start();
        if (multicast) {
            Thread n = new Thread(new Runnable() {
                public void run() {
                    announceLoop();
                }
            }, "ssdp-notify");
            n.setDaemon(true);
            n.start();
        }
        return ms.getLocalPort();
    }

    private void joinGroups(MulticastSocket ms) throws IOException {
        InetAddress group = InetAddress.getByName(GROUP);
        boolean joined = false;
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                try {
                    if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast() && hasIPv4(ni)) {
                        ms.joinGroup(new InetSocketAddress(group, ms.getLocalPort()), ni);
                        joined = true;
                    }
                } catch (IOException e) {
                    Dbg.d("SSDP: cannot join on " + ni.getName() + ": " + e);
                }
            }
        } catch (IOException e) {
            Dbg.d("SSDP: cannot list interfaces: " + e);
        }
        if (!joined) {
            ms.joinGroup(group); // throws if there is really no way
        }
    }

    private static boolean hasIPv4(NetworkInterface ni) {
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            if (addrs.nextElement() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    /** Says goodbye (in the background, this may be called on the UI thread) and closes the socket. */
    public void stop() {
        running = false;
        final MulticastSocket ms = socket;
        socket = null;
        Thread t = new Thread(new Runnable() {
            public void run() {
                if (multicast) {
                    try {
                        sendNotify("ssdp:byebye");
                    } catch (RuntimeException e) {
                        // best effort
                    }
                }
                if (ms != null) {
                    ms.close();
                }
            }
        }, "ssdp-stop");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ answering searches

    private void listen(MulticastSocket ms) {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                ms.receive(p);
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            try {
                handle(new String(p.getData(), p.getOffset(), p.getLength(), LATIN1), p.getAddress(), p.getPort());
            } catch (RuntimeException e) {
                Dbg.d("SSDP: bad packet ignored: " + e);
            }
        }
    }

    private void handle(String message, final InetAddress from, final int fromPort) {
        if (!message.startsWith("M-SEARCH")) {
            return;
        }
        String man = header(message, "man");
        String st = header(message, "st");
        if (man == null || st == null || !man.replace("\"", "").trim().equalsIgnoreCase("ssdp:discover")) {
            return;
        }
        int mx = 1;
        try {
            mx = Integer.parseInt(String.valueOf(header(message, "mx")).trim());
        } catch (NumberFormatException e) {
            // keep 1
        }
        mx = Math.max(1, Math.min(5, mx));
        final List<Integer> wanted = new ArrayList<Integer>();
        String want = st.trim();
        for (int i = 0; i < types.length; i++) {
            if (want.equalsIgnoreCase("ssdp:all") || want.equalsIgnoreCase(types[i])) {
                wanted.add(Integer.valueOf(i));
            }
        }
        if (wanted.isEmpty() || responders.incrementAndGet() > 4) {
            if (!wanted.isEmpty()) {
                responders.decrementAndGet();
            }
            return;
        }
        final long delay = random.nextInt(mx * 150 + 1);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(delay);
                    reply(from, fromPort, wanted);
                } catch (InterruptedException e) {
                    // stopping
                } catch (IOException e) {
                    Dbg.d("SSDP: reply failed: " + e);
                } finally {
                    responders.decrementAndGet();
                }
            }
        }, "ssdp-reply");
        t.setDaemon(true);
        t.start();
    }

    private static String header(String message, String name) {
        String[] lines = message.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return null;
    }

    private void reply(InetAddress to, int toPort, List<Integer> wanted) throws IOException {
        DatagramSocket out = new DatagramSocket();
        try {
            String ip = localIpFor(to);
            for (int k = 0; k < wanted.size(); k++) {
                int i = wanted.get(k).intValue();
                String msg = "HTTP/1.1 200 OK\r\n"
                        + "CACHE-CONTROL: max-age=" + MAX_AGE + "\r\n"
                        + "EXT:\r\n"
                        + "LOCATION: http://" + ip + ":" + httpPort + "/description.xml\r\n"
                        + "SERVER: " + server + "\r\n"
                        + "ST: " + types[i] + "\r\n"
                        + "USN: " + usn(i) + "\r\n\r\n";
                byte[] b = msg.getBytes(LATIN1);
                out.send(new DatagramPacket(b, b.length, to, toPort));
            }
        } finally {
            out.close();
        }
    }

    private String usn(int i) {
        return i == 1 ? "uuid:" + uuid : "uuid:" + uuid + "::" + types[i];
    }

    /** The address of this device that the peer can reach: the one in the same subnet. */
    static String localIpFor(InetAddress peer) {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                List<InterfaceAddress> list = ni.getInterfaceAddresses();
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    InetAddress a = list.get(i).getAddress();
                    if (a instanceof Inet4Address && peer instanceof Inet4Address
                            && sameSubnet(a.getAddress(), peer.getAddress(), list.get(i).getNetworkPrefixLength())) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        List<String> all = NetUtil.localIPv4();
        return all.isEmpty() ? "127.0.0.1" : all.get(0);
    }

    static boolean sameSubnet(byte[] a, byte[] b, int prefix) {
        if (a.length != 4 || b.length != 4 || prefix < 0 || prefix > 32) {
            return false;
        }
        int bits = prefix;
        for (int i = 0; i < 4 && bits > 0; i++) {
            int mask = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
            if ((a[i] & mask) != (b[i] & mask)) {
                return false;
            }
            bits -= 8;
        }
        return true;
    }

    // ------------------------------------------------------------------ announcing

    private void announceLoop() {
        try {
            Thread.sleep(500);
            while (running) {
                sendNotify("ssdp:alive");
                Thread.sleep(1000);
                if (!running) {
                    return;
                }
                sendNotify("ssdp:alive"); // once more, UDP gets lost
                Thread.sleep(RENEW_MS);
            }
        } catch (InterruptedException e) {
            // stopping
        }
    }

    private void sendNotify(String nts) {
        List<String> ips = NetUtil.localIPv4();
        for (int k = 0; k < ips.size(); k++) {
            MulticastSocket ms = null;
            try {
                ms = new MulticastSocket();
                ms.setInterface(InetAddress.getByName(ips.get(k)));
                ms.setTimeToLive(2);
                InetAddress group = InetAddress.getByName(GROUP);
                for (int i = 0; i < types.length; i++) {
                    StringBuilder sb = new StringBuilder(300);
                    sb.append("NOTIFY * HTTP/1.1\r\nHOST: ").append(GROUP).append(':').append(STANDARD_PORT).append("\r\n");
                    if (nts.equals("ssdp:alive")) {
                        sb.append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\n");
                        sb.append("LOCATION: http://").append(ips.get(k)).append(':').append(httpPort)
                                .append("/description.xml\r\n");
                        sb.append("SERVER: ").append(server).append("\r\n");
                    }
                    sb.append("NT: ").append(types[i]).append("\r\nNTS: ").append(nts.toLowerCase(Locale.US))
                            .append("\r\nUSN: ").append(usn(i)).append("\r\n\r\n");
                    byte[] b = sb.toString().getBytes(LATIN1);
                    ms.send(new DatagramPacket(b, b.length, group, STANDARD_PORT));
                }
            } catch (IOException e) {
                Dbg.d("SSDP: " + nts + " on " + ips.get(k) + " failed: " + e);
            } finally {
                if (ms != null) {
                    ms.close();
                }
            }
        }
    }
}
