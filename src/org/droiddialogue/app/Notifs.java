package org.droiddialogue.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Builds the foreground service notification on every Android version from 4.0 up. */
final class Notifs {
    static final int ID = 1;
    private static final String CHANNEL = "satellite";

    private Notifs() {
    }

    static Notification build(Context ctx, String text) {
        Intent open = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);
        if (Build.VERSION.SDK_INT >= 26) {
            return buildWithChannel(ctx, text, pi);
        }
        return buildLegacy(ctx, text, pi);
    }

    @SuppressWarnings("deprecation")
    private static Notification buildLegacy(Context ctx, String text, PendingIntent pi) {
        Notification.Builder b = new Notification.Builder(ctx)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Wyoming satellite")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 16) {
            return b.build();
        }
        return b.getNotification();
    }

    private static Notification buildWithChannel(Context ctx, String text, PendingIntent pi) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Satellite",
                    NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Wyoming satellite")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
