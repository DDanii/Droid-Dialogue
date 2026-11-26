package org.wyominglegacy.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls that only exist on newer Android versions, kept in one place and always behind an SDK_INT
 * check by the caller so the app still loads and runs on API 14.
 */
final class Compat {
    private Compat() {
    }

    /** Asks for the runtime permissions needed on Android 6+. Returns true if nothing is missing. */
    static boolean ensurePermissions(Activity a) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return ensureRuntimePermissions(a);
    }

    private static boolean ensureRuntimePermissions(Activity a) {
        List<String> need = new ArrayList<String>();
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (need.isEmpty()) {
            return true;
        }
        a.requestPermissions(need.toArray(new String[need.size()]), 1);
        return false;
    }

    static void startSatelliteService(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(c, i);
        } else {
            c.startService(i);
        }
    }

    private static void startForeground(Context c, Intent i) {
        c.startForegroundService(i);
    }
}
