package com.iohelper.card;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * Bring the assistant back after a reboot, with no cable and no tapping.
 *
 * WHY THIS IS THE LAST PIECE. Everything else about surviving a restart was
 * already solved: Wireless debugging persists across reboots where
 * `adb tcpip 5555` does not, adbd re-advertises itself over mDNS, and
 * {@link Wireless} authenticates on a certificate over a key adbd already
 * trusts. But none of that runs if nothing is running - Android kills the
 * foreground service at shutdown and starts nothing back up, so the assistant
 * came back only when someone opened the app. "Reconnects on its own" was true
 * of the LINK and false of the APP.
 *
 * WHAT IT KEYS OFF. {@link Prefs#WANTED}, not {@link Prefs#RUNNING}. RUNNING is
 * cleared by AssistantService.onDestroy, which also runs on an orderly
 * shutdown, so by the time this receiver fires it would read false and restart
 * nothing. WANTED is written only by the explicit start/stop calls, so someone
 * who deliberately stopped the assistant does not get it back uninvited.
 *
 * ANDROID 12+ BACKGROUND-START RULES. Starting a foreground service from the
 * background normally throws; receiving BOOT_COMPLETED is one of the listed
 * exemptions, which is why this is a manifest receiver rather than anything
 * cleverer. The start is still wrapped, because a throw here is invisible - it
 * would look exactly like the reboot problem this class exists to fix.
 *
 * MY_PACKAGE_REPLACED is handled the same way, so reinstalling the app over
 * itself does not silently leave the assistant off either.
 *
 * NOTE the receiver does not fire at all while the app is in the "stopped"
 * state Android puts it in between install and first launch, or after a
 * force-stop. Opening the app once is what arms it - which the setup steps
 * already require.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "iohelperBoot";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null
                || !(Intent.ACTION_BOOT_COMPLETED.equals(action)
                     || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))) {
            return;
        }
        // Nav relay is a NotificationListenerService, and Android drops its
        // binding when the app is updated (and does not restore it until reboot
        // on many builds) - and build.py reinstalls constantly. Ask for it back.
        // Independent of whether the assistant is WANTED, so do it before that
        // early return. No-op if notification access was never granted.
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(ctx, NavListener.class));
        } catch (Throwable t) {
            Log.w(TAG, action + ": nav rebind request failed: " + t);
        }

        if (!Prefs.bool(ctx, Prefs.WANTED, false)) {
            Log.i(TAG, action + ": assistant was stopped on purpose, leaving it off");
            return;
        }
        try {
            AssistantService.start(ctx.getApplicationContext());
            Log.i(TAG, action + ": assistant restarted");
        } catch (Throwable t) {
            // Nothing useful to do from here, but say so: the alternative is an
            // assistant that is silently dead after every restart.
            Log.w(TAG, action + ": could not restart the assistant: " + t);
        }
    }
}
