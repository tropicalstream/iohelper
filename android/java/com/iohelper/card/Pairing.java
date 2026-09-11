package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/**
 * Getting the six digits out of Android's pairing dialog and into this app.
 *
 * THE PROBLEM. The pairing code does not exist until the user opens
 * Settings -> Developer options -> Wireless debugging -> "Pair device with
 * pairing code", and it lives only as long as that dialog: adbd advertises
 * _adb-tls-pairing._tcp while the dialog is up and stops when it goes away.
 * So "read the code, switch to this app, type it in" is not a flow that can be
 * relied on - leaving Settings is exactly the thing that can cancel the pairing
 * the code belongs to, and the failure looks like "no pairing service found"
 * rather than anything to do with switching apps.
 *
 * THE FIX. Ask for the code in a NOTIFICATION with a direct-reply field. The
 * shade pulls down OVER Settings without replacing it, so the dialog stays
 * where it is and the code stays valid. This is the same shape Shizuku uses for
 * the same reason. The in-app text box is kept as well, for anyone whose device
 * does leave the dialog alone - but this is the path that always works.
 *
 * The reply lands on {@link ShowReceiver} as {@link #ACTION_REPLY}.
 */
public final class Pairing {

    /** Separate from the "assistant" card channel: this is setup, not an answer. */
    static final String CHANNEL = "setup";
    static final int ID = 7;
    static final String KEY_CODE = "pairing_code";

    public static final String ACTION_REPLY = "com.iohelper.card.PAIRREPLY";

    private Pairing() {
    }

    /** The six digits the user typed into the notification, or null. */
    public static String codeFrom(Intent intent) {
        Bundle b = RemoteInput.getResultsFromIntent(intent);
        if (b == null) {
            return null;
        }
        CharSequence c = b.getCharSequence(KEY_CODE);
        return c == null ? null : c.toString().trim();
    }

    /** Post (or replace) the prompt. */
    public static void prompt(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        channel(nm);

        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel("6-digit pairing code")
                .build();

        // FLAG_MUTABLE is not optional: an immutable PendingIntent cannot carry
        // RemoteInput results back, and on Android 12+ omitting both flags is a
        // hard error at construction. The constant is inlined by javac, so
        // naming it here is safe on the minSdk 26 this app still builds for.
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, ShowReceiver.class).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_stat), "Pair", pi)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build();

        nm.notify(ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Waiting for a pairing code")
                .setContentText("Wireless debugging -> Pair device with pairing code, "
                        + "then type the six digits here")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "In Settings: Developer options -> Wireless debugging -> \"Pair device "
                        + "with pairing code\". LEAVE that dialog on screen, pull this shade "
                        + "down over it, and type the six digits below. Going back to the Jarvis "
                        + "app instead can close the dialog, which cancels the code."))
                .addAction(action)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /** Replace the prompt with what happened. */
    static void result(Context ctx, boolean ok, String detail) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        channel(nm);
        nm.notify(ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ok ? "Paired" : "Pairing failed")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setAutoCancel(true)
                .build());
    }

    private static void channel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // HIGH so it arrives as a heads-up banner ON TOP of the pairing
            // dialog: the user can reply without even opening the shade.
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Pairing and first-run steps");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }
}
