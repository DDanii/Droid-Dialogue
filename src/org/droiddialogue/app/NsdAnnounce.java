package org.droiddialogue.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.droiddialogue.core.Dbg;

/**
 * Announces the satellite on the network (mDNS, _wyoming._tcp) so Home Assistant can discover it.
 * NsdManager exists from Android 4.1 (API 16); on older versions add the satellite by IP address.
 * Only create this class when Build.VERSION.SDK_INT >= 16.
 */
final class NsdAnnounce {
    private NsdManager manager;
    private NsdManager.RegistrationListener listener;

    void register(Context ctx, String name, int port) {
        try {
            manager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType("_wyoming._tcp.");
            info.setPort(port);
            listener = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo i, int code) {
                    Dbg.d("mDNS registration failed: " + code);
                }

                public void onUnregistrationFailed(NsdServiceInfo i, int code) {
                    Dbg.d("mDNS unregistration failed: " + code);
                }

                public void onServiceRegistered(NsdServiceInfo i) {
                    Dbg.d("mDNS registered as " + i.getServiceName());
                }

                public void onServiceUnregistered(NsdServiceInfo i) {
                    Dbg.d("mDNS unregistered");
                }
            };
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            Dbg.d("mDNS announce skipped: " + t);
            manager = null;
            listener = null;
        }
    }

    void unregister() {
        try {
            if (manager != null && listener != null) {
                manager.unregisterService(listener);
            }
        } catch (Throwable t) {
            // ignore, the service is going away
        }
        manager = null;
        listener = null;
    }
}
