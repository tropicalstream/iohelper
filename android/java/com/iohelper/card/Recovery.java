package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * What to do when the adb link dies - which, in practice, means "the phone
 * rebooted".
 *
 * `adb tcpip 5555` does not survive a reboot, so an app speaking only the
 * classic (pre-TLS) ADB protocol goes deaf after a restart. {@link Wireless} is
 * the answer to that - Android's own Wireless debugging DOES persist - and
 * {@link BootReceiver} restarts the service that uses it, so the ordinary
 * reboot now heals itself with nothing on screen.
 *
 * This class is what is left over: the cases pairing cannot fix, mainly
 * Wireless debugging being switched off. Instead of failing silently, the
 * outage is made obvious and easy to fix: a notification that explains it in a
 * sentence and opens Developer options in one tap, plus a card on the glasses -
 * which still works, because posting a card is a plain notification and needs
 * no adb at all. AssistantService holds it back for the first ninety seconds
 * after a boot, so "Wi-Fi is not up yet" never gets announced as a fault.
 *
 * Announced ONCE per outage, and withdrawn automatically on reconnect.
 */
public final class Recovery {

    private static final String CHANNEL = "recovery";
    private static final int ID = 6;

    private Recovery() {
    }

    /** Turn an exception from the adb connect into a sentence worth reading. */
    public static String explain(Throwable e) {
        String s = String.valueOf(e);
        if (s.contains("ECONNREFUSED") || s.contains("Connection refused")) {
            return "Wireless adb is off (usual after a reboot).";
        }
        if (s.contains("ETIMEDOUT") || s.contains("timed out")) {
            return "adb did not respond.";
        }
        if (s.toLowerCase().contains("auth") || s.contains("rejected")) {
            return "adb refused this app's key - accept 'Allow debugging?'.";
        }
        return "adb link down.";
    }

    public static void announce(Context ctx, Throwable cause) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Needs reconnecting", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Shown when the assistant loses its adb link");
            nm.createNotificationChannel(ch);
        }
        String why = explain(cause);
        PendingIntent devOptions = PendingIntent.getActivity(ctx, 0,
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Lead with the durable fix. The USB route still works, but sending
        // someone back to a computer after every reboot is the problem, not the
        // answer - pairing with Wireless debugging is a one-time cost.
        String fix = Wireless.paired(ctx)
                ? "Turn Wireless debugging back on (Developer options). Already paired, "
                  + "so it reconnects on its own once it is enabled."
                : "Fix it for good: open the app and pair with Wireless debugging. "
                  + "One-off alternative: plug into a computer and run adb tcpip 5555.";
        nm.notify(ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(Cards.title(ctx) + " needs reconnecting")
                .setContentText(why + " " + fix)
                .setStyle(new Notification.BigTextStyle().bigText(why + "\n\n" + fix))
                .setContentIntent(devOptions)
                .setAutoCancel(false)
                .build());

        // ...and on the glasses, which still works: a card is just a
        // notification, so it needs no adb.
        Cards.post(ctx, Cards.title(ctx), "⚠ " + why + " Check your phone.", "answer");
    }

    public static void clear(Context ctx, boolean announceReturn) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(ID);
        }
        if (announceReturn) {
            Cards.post(ctx, Cards.title(ctx), "✓ Reconnected - listening again.", "answer");
        }
    }
}
