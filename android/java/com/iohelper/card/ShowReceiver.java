package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Posts (or cancels) the assistant's notification. Driven entirely by adb:
 *
 *   am broadcast -n com.iohelper.card/.ShowReceiver -a com.iohelper.card.SHOW \
 *       --es title "Weather" --es body "68F and clear" [--es header "Jarvis"] [--ei id 1]
 *   am broadcast -n com.iohelper.card/.ShowReceiver -a com.iohelper.card.CANCEL [--ei id 1]
 *
 * The glasses show "<app label> / <title> / <body>"; because THIS app posts,
 * the label is "Jarvis" rather than "Shell". `header` additionally sets
 * android.substName, which overrides the displayed app name per notification.
 */
public class ShowReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "assistant";
    private static final int DEFAULT_ID = 1;
    /** Notification.EXTRA_SUBSTITUTE_APP_NAME is @hide, but the key is stable. */
    private static final String EXTRA_SUBSTITUTE_APP_NAME = "android.substName";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        // The channel is created by Cards.ensureChannel at post time, which is
        // also what decides whether it alerts. Re-creating an IMPORTANCE_HIGH
        // "assistant" here on every broadcast was what kept the phone popping
        // banners regardless of the app's quiet-cards switch.

        int id = intent.getIntExtra("id", DEFAULT_ID);
        String action = intent.getAction();
        if (action != null && action.endsWith("CANCEL")) {
            nm.cancel(id);
            return;
        }
        if (action != null && action.endsWith("SHOWX")) {
            postProbe(ctx, nm, intent);
            return;
        }
        if (action != null && action.endsWith("CARDREPLY")) {
            // A reply typed into (or dictated to) a probe card's RemoteInput -
            // from the shade, or from the glasses if the relay honours the
            // action. Logged AND echoed as a card, so the round trip is visible
            // on the lens itself.
            Bundle r = android.app.RemoteInput.getResultsFromIntent(intent);
            CharSequence t = r == null ? null : r.getCharSequence("card_reply");
            android.util.Log.i("iohelperProbe", "reply for card " + id + ": " + t);
            post(ctx, nm, 91, "Reply received", t == null ? "(empty)" : t.toString(), null);
            return;
        }
        if (action != null && action.endsWith("CAPPROBE")) {
            // Finding the caption node on a YouTube build whose view ids have
            // moved: run it with a captioned video on screen and read the
            // iohelperCaps tag.
            post(ctx, nm, 5, "Diagnostics", CaptionListener.probe(), null);
            return;
        }
        if (action != null && action.endsWith("DIAG")) {
            post(ctx, nm, 5, "Diagnostics", AssistantService.diag(), null);
            return;
        }
        if (action != null && action.endsWith("SAY")) {
            // Test hook: push a phrase through the same path a spoken
            // transcript takes, so the loop can be exercised without speaking.
            String text = intent.getStringExtra("text");
            boolean ok = text != null && AssistantService.inject(text);
            if (!ok) {
                post(ctx, nm, 5, "Diagnostics", "inject failed - service not running", null);
            }
            return;
        }
        if (action != null && action.endsWith("TALKTEST")) {
            // The live voice session, exercised from a desk: the phrase is
            // synthesised and fed into the session as if the microphone had
            // heard it, so speech -> transcript -> delegation -> tools ->
            // spoken answer -> card can be proven without anyone talking.
            //   am broadcast -n com.iohelper.card/.ShowReceiver -a <pkg>.TALKTEST --es text "..."
            final String text = intent.getStringExtra("text");
            final Context app = ctx.getApplicationContext();
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    TalkService.hear(app, text == null ? "" : text);
                    android.util.Log.i("iohelperTalk", "talktest: fed " + (text == null ? 0 : text.length()) + " chars");
                } catch (Throwable t) {
                    android.util.Log.i("iohelperTalk", "talktest failed: " + t);
                }
                pending.finish();
            }).start();
            return;
        }
        if (action != null && action.endsWith("TALK")) {
            // Toggle the live voice session. A microphone foreground service
            // cannot be started from a receiver (background), so go via the
            // activity, exactly as START does.
            Intent ui = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("talk", true);
            ctx.startActivity(ui);
            return;
        }
        if (action != null && action.endsWith("CONFIGFILE")) {
            // Provisioning SECRETS. The CONFIG action below puts the value on an
            // `am broadcast` command line, and adbd logs every shell request to
            // logcat - the very stream this assistant reads. An API key handed
            // over that way ends up inside the answer path. This reads a pushed
            // JSON file over the adb socket instead, then deletes it.
            //
            //   adb push keys.json /data/local/tmp/iohelper-config.json
            //   am broadcast -n com.iohelper.card/.ShowReceiver -a <pkg>.CONFIGFILE
            final Context app = ctx.getApplicationContext();
            final PendingResult pending = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String path = "/data/local/tmp/iohelper-config.json";
                    String result;
                    try {
                        String body = LocalAdb.shell(app, "cat " + path, 10000);
                        org.json.JSONObject o = new org.json.JSONObject(body);
                        int n = 0;
                        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                            String k = it.next();
                            Object v = o.get(k);
                            // getString() threw on a JSON boolean or number, and
                            // one bad value aborted the whole file. Take every
                            // value as text; putTyped re-types the booleans.
                            if (v == org.json.JSONObject.NULL) {
                                continue;
                            }
                            Prefs.putTyped(app, k, String.valueOf(v));
                            n++;
                        }
                        LocalAdb.shell(app, "rm -f " + path, 6000);
                        result = "provisioned " + n + " setting(s)";
                    } catch (Throwable t) {
                        result = "config file failed: " + t;
                    }
                    android.util.Log.i("iohelperConfig", result);
                    pending.finish();
                }
            }).start();
            return;
        }
        if (action != null && action.endsWith("CONFIG")) {
            // Provisioning from adb, so keys need not be typed on a phone
            // keyboard: --es k <pref-key> --es v <value>. Write-only - it can
            // set settings but never read them back. Do NOT use this for
            // secrets; use CONFIGFILE above.
            String k = intent.getStringExtra("k");
            String v = intent.getStringExtra("v");
            if (k != null && v != null) {
                Prefs.putTyped(ctx, k, v);
                post(ctx, nm, 4, "Config", k + " set (" + v.length() + " chars)", null);
            }
            return;
        }
        if (action != null && action.endsWith("START")) {
            // Android 12+ refuses startForegroundService() from a background
            // context, and a broadcast receiver IS background ("Background
            // started FGS: Disallowed ... code:DENIED"). So bring the activity
            // up and let IT start the service from the foreground.
            Intent ui = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("autostart", true);
            ctx.startActivity(ui);
            return;
        }
        if (action != null && action.endsWith("PAIRREPLY")) {
            // The six digits, typed into the notification's reply field while
            // Android's pairing dialog is still on screen. See Pairing.
            pairInBackground(ctx, goAsync(), Pairing.codeFrom(intent));
            return;
        }
        if (action != null && action.endsWith("PAIR")) {
            // Headless pairing, for testing the flow without driving the UI.
            // The six digits arrive in a pushed file rather than on the
            // `am broadcast` command line for the same reason CONFIGFILE
            // exists: adbd writes every shell request to logcat, which is the
            // stream this assistant reads back.
            //
            //   adb shell "echo 123456 > /data/local/tmp/iohelper-pair"
            //   am broadcast -n com.iohelper.card/.ShowReceiver -a <pkg>.PAIR
            final Context app = ctx.getApplicationContext();
            final PendingResult pending = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Always logged, even when the code is missing: it is the
                    // on-device proof that the ed25519 fix shipped, and it costs
                    // two scalar multiplications once per process.
                    android.util.Log.i("iohelperWireless", "SPAKE2 self-test: "
                            + (Wireless.spake2Works() ? "agrees" : "BROKEN"));
                    String path = "/data/local/tmp/iohelper-pair";
                    String code = null;
                    try {
                        code = LocalAdb.shell(app, "cat " + path, 10000).trim();
                        LocalAdb.shell(app, "rm -f " + path, 6000);
                    } catch (Throwable t) {
                        android.util.Log.i("iohelperWireless", "no pushed code: " + t);
                    }
                    if (code == null || code.length() != 6) {
                        // No code to work with, so ask for one the way the app
                        // does: a notification the user can reply to without
                        // leaving Android's pairing dialog.
                        Pairing.prompt(app);
                        android.util.Log.i("iohelperWireless", "posted the pairing prompt");
                    } else {
                        pairAndProve(app, code);
                    }
                    pending.finish();
                }
            }).start();
            return;
        }
        if (action != null && action.endsWith("WIRELESS")) {
            // Test hook for the reboot-proof path: discover adbd over mDNS,
            // connect with TLS, and prove the result by asking WHO WE ARE. A
            // socket that opens proves nothing; `id -un` returning "shell" is
            // the whole claim. The result goes to logcat and to a card, so it
            // can be read over adb without touching the screen.
            final Context app = ctx.getApplicationContext();
            final PendingResult pending = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String result;
                    com.cgutman.adblib.AdbConnection c = null;
                    try {
                        Wireless.Endpoint e = Wireless.discover(app,
                                io.github.muntashirakon.adb.android.AdbMdns.SERVICE_TYPE_TLS_CONNECT,
                                8000);
                        if (e == null) {
                            throw new IllegalStateException("no _adb-tls-connect._tcp advertised");
                        }
                        c = Wireless.connect(app, 8000);
                        android.util.Log.i("iohelperWireless", "opening shell stream");
                        com.cgutman.adblib.AdbStream st = c.open("shell:id -un");
                        android.util.Log.i("iohelperWireless", "stream opened; reading");
                        byte[] first = st.read();
                        android.util.Log.i("iohelperWireless",
                                "read " + (first == null ? -1 : first.length) + " bytes");
                        String who = new String(first, "UTF-8").trim();
                        st.close();
                        result = "OK " + e + " uid=" + who;
                    } catch (Throwable t) {
                        result = "FAIL " + t + " | sweep: " + Wireless.sweep(app);
                    } finally {
                        if (c != null) {
                            try {
                                c.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    android.util.Log.i("iohelperWireless", result);
                    try {
                        post(app, app.getSystemService(NotificationManager.class),
                                5, "Wireless", result, null);
                    } catch (Throwable ignored) {
                    }
                    pending.finish();
                }
            }).start();
            return;
        }
        if (action != null && action.endsWith("STOP")) {
            AssistantService.stop(ctx);      // stopping from the background is allowed
            return;
        }
        if (action != null && action.endsWith("ADBPROBE")) {
            // The real feasibility test: can THIS app, with nothing else
            // installed, reach shell-UID logcat through the phone's own adbd?
            final PendingResult pending = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String result;
                    try {
                        String out = LocalAdb.shell(ctx, "logcat -d -t 3000", 15000);
                        int lines = 0, asr = 0, rnlink = 0;
                        for (String l : out.split("\n")) {
                            lines++;
                            if (l.contains("onAlwaysOnResponse") || l.contains("phone_asr_text")
                                    || l.contains("VOICE_ASSISTANT")) {
                                asr++;
                            }
                            if (l.contains("CC-RNMessageManager")) {
                                rnlink++;
                            }
                        }
                        result = "adb lines=" + lines + " asr=" + asr + " rnlink=" + rnlink;
                    } catch (Exception e) {
                        result = "adb FAILED: " + e;
                    }
                    post(ctx, nm, 3, "Local adb probe", result, null);
                    pending.finish();
                }
            }).start();
            return;
        }
        if (action != null && action.endsWith("PROBE")) {
            // Feasibility self-test for running the whole assistant on-device:
            // with READ_LOGS granted (adb, one time), can THIS app read the
            // glasses transcripts out of logcat itself - no PC attached?
            post(ctx, nm, id, "Log probe", probeLogcat(), null);
            return;
        }

        post(ctx, nm, id, orEmpty(intent.getStringExtra("title")),
                orEmpty(intent.getStringExtra("body")), intent.getStringExtra("header"));
    }

    /**
     * Probe card: one optional extra per notification feature, so each can be
     * tested in isolation against the RayNeo relay, which logs exactly what it
     * forwards (`logcat | grep rayneonet_message_send_success`). Shell-posted
     * notifications are not on the relay's allowlist, so probes have to come
     * from this app.
     *
     *   sub -> setSubText          big -> BigTextStyle      lines -> InboxStyle ('|')
     *   conv/msgs -> MessagingStyle ("who:text|who:text")   cat -> setCategory
     *   reply -> RemoteInput action (answered via CARDREPLY) ongoing -> setOngoing
     *   len -> body of N digits, so the forwarded length can be read back
     */
    private static void postProbe(Context ctx, NotificationManager nm, Intent in) {
        int id = in.getIntExtra("id", 90);
        String title = orEmpty(in.getStringExtra("title"));
        String body = orEmpty(in.getStringExtra("body"));
        int len = in.getIntExtra("len", 0);
        if (len > 0) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() < len) {
                sb.append(sb.length() % 10);
            }
            body = sb.toString();
        }
        boolean ongoing = in.getBooleanExtra("ongoing", false);
        Notification.Builder b = new Notification.Builder(ctx, Cards.ensureChannel(ctx, nm))
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(false);
        String sub = in.getStringExtra("sub");
        if (sub != null) {
            b.setSubText(sub);
        }
        // The relay's frame has a "subtitle" slot that subText does not fill;
        // these are the two remaining Android extras that could.
        if (in.getStringExtra("summary") != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(body)
                    .setSummaryText(in.getStringExtra("summary")));
        }
        if (in.getStringExtra("info") != null) {
            b.setContentInfo(in.getStringExtra("info"));
        }
        String big = in.getStringExtra("big");
        if (big != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big));
        }
        String lines = in.getStringExtra("lines");
        if (lines != null) {
            Notification.InboxStyle st = new Notification.InboxStyle();
            for (String l : lines.split("\\|")) {
                st.addLine(l);
            }
            b.setStyle(st);
        }
        String msgs = in.getStringExtra("msgs");
        if (msgs != null && Build.VERSION.SDK_INT >= 28) {
            Notification.MessagingStyle st = new Notification.MessagingStyle(
                    new android.app.Person.Builder().setName("Me").build());
            String conv = in.getStringExtra("conv");
            if (conv != null) {
                st.setConversationTitle(conv);
            }
            for (String m : msgs.split("\\|")) {
                int c = m.indexOf(':');
                st.addMessage(c > 0 ? m.substring(c + 1) : m, System.currentTimeMillis(),
                        new android.app.Person.Builder()
                                .setName(c > 0 ? m.substring(0, c) : "Someone").build());
            }
            b.setStyle(st);
        }
        String cat = in.getStringExtra("cat");
        if (cat != null) {
            b.setCategory(cat);
        }
        if (in.getBooleanExtra("reply", false)) {
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(ctx, id,
                    new Intent(ctx, ShowReceiver.class)
                            .setAction("com.iohelper.card.CARDREPLY").putExtra("id", id),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_MUTABLE);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat),
                    "Reply", pi)
                    .addRemoteInput(new android.app.RemoteInput.Builder("card_reply")
                            .setLabel("Reply").build())
                    .build());
        }
        String header = in.getStringExtra("header");
        if (header != null && !header.isEmpty()) {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_SUBSTITUTE_APP_NAME, header);
            b.addExtras(extras);
        }
        nm.notify(id, b.build());
    }

    private static void post(Context ctx, NotificationManager nm, int id,
                             String title, String body, String header) {
        Notification.Builder b = new Notification.Builder(ctx, Cards.ensureChannel(ctx, nm))
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                // reposting the same id must re-alert, or the glasses never
                // re-render an updated card
                .setOnlyAlertOnce(false);
        if (header != null && !header.isEmpty()) {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_SUBSTITUTE_APP_NAME, header);
            b.addExtras(extras);
        }
        nm.notify(id, b.build());
    }

    /** Pair off the caller's thread, then report through the notification. */
    private static void pairInBackground(Context ctx, final PendingResult pending,
                                         final String code) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                pairAndProve(app, code);
                pending.finish();
            }
        }).start();
    }

    /**
     * Pair, then PROVE it by connecting and asking who we are.
     *
     * "Paired" on its own is not worth reporting: the whole class of bug this
     * came out of was every layer returning success while the result was
     * useless. `id -un` answering "shell" is the actual claim.
     */
    private static void pairAndProve(Context app, String code) {
        boolean ok = false;
        String detail;
        com.cgutman.adblib.AdbConnection c = null;
        try {
            if (code == null || code.length() != 6) {
                throw new IllegalArgumentException("Six digits, no spaces - got "
                        + (code == null ? "nothing" : "\"" + code + "\""));
            }
            Wireless.pair(app, code);
            c = Wireless.connect(app, 12000);
            com.cgutman.adblib.AdbStream st = c.open("shell:id -un");
            String who = new String(st.read(), "UTF-8").trim();
            st.close();
            ok = true;
            detail = "Connected as " + who + ". Survives a reboot - no computer needed.";
        } catch (Throwable t) {
            detail = explain(t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        android.util.Log.i("iohelperWireless", (ok ? "pair OK: " : "pair FAILED: ") + detail);
        Pairing.result(app, ok, detail);
    }

    /** Turn the three failures that actually happen into something actionable. */
    private static String explain(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (m.contains("_adb-tls-pairing")) {
            return "Android was not offering a pairing code. Turn Wireless debugging ON, "
                    + "tap \"Pair device with pairing code\", and leave that dialog on screen "
                    + "while you type here.";
        }
        if (m.contains("_adb-tls-connect")) {
            return "Paired, but Wireless debugging is not advertising a connect port. "
                    + "Check the toggle is still on.";
        }
        if (t instanceof java.io.IOException && m.contains("Exchanging message")) {
            return "The daemon rejected the code. It changes every time the dialog opens - "
                    + "close it, open it again and use the new one.";
        }
        return String.valueOf(t);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Read our own logcat and count the lines that matter to the assistant. */
    private static String probeLogcat() {
        int total = 0, asr = 0, rnlink = 0;
        String sample = "";
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "4000")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            for (String line; (line = r.readLine()) != null; ) {
                total++;
                if (line.contains("onAlwaysOnResponse") || line.contains("phone_asr_text")
                        || line.contains("VOICE_ASSISTANT")) {
                    asr++;
                    if (sample.isEmpty() && line.length() > 40) {
                        sample = line.substring(line.length() - 40);
                    }
                }
                if (line.contains("CC-RNMessageManager")) {
                    rnlink++;
                }
            }
            r.close();
            p.waitFor();
        } catch (Exception e) {
            return "logcat FAILED: " + e;
        }
        return "lines=" + total + " asr=" + asr + " rnlink=" + rnlink
                + (sample.isEmpty() ? "" : " | " + sample);
    }
}
