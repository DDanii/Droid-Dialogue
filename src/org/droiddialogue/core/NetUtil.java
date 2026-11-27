package org.droiddialogue.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class NetUtil {
    private NetUtil() {
    }

    /** Non-loopback IPv4 addresses of this device, for showing the address to enter in Home Assistant. */
    public static List<String> localIPv4() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            // ignore, the list is only informational
        }
        return out;
    }
}
