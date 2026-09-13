package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbStream;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant loop, on the phone, with no PC.
 *
 *   glasses mic -> RayNeo ASR -> transcript in logcat
 *      -> (this service, reading logcat as SHELL via the phone's own adbd)
 *      -> wake gate -> SerpApi + LLM -> notification -> RNLink -> glasses
 *
 * Runs as a foreground service so Android keeps it alive, and reads the log
 * through LocalAdb because an ordinary app can only see its own lines.
 */
public class AssistantService extends Service {

    private static final String TAG = "iohelper";
    private static final String FG_CHANNEL = "service";
    private static final int FG_ID = 42;

    // Same transcript signatures the PC build watches for.
    private static final Pattern ASR_ALWAYSON = Pattern.compile(
            "onAlwaysOnResponse role=(host|guest) text=(.*?) roundId=(\\S+) finished=true");
    private static final Pattern ASR_ASSISTANT = Pattern.compile(
            "phone_asr_text.*?payload=(\\{.*?\\})\\s+eventTs=(\\d+)");
    private static final Pattern ASR_TEXT = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"");
    private static final Pattern FINAL_TRUE = Pattern.compile("\"final\"\\s*:\\s*true");

    /**
     * Decode the JSON escapes in a transcript.
     *
     * The text is pulled out of a JSON payload with a regex, so its escapes
     * arrive verbatim: "What's playing?" reaches us as "What\\u0027s playing?".
     * Every contraction is affected - what's, don't, I'm, how's - and the damage
     * is silent: the command patterns simply stop matching and the utterance
     * falls through to the model, which answers something else entirely.
     */
    static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i == s.length() - 1) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append("\\u");
                        }
                    }
                    break;
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                default:  out.append(n); break;      // \" \\ \/ and anything else
            }
        }
        return out.toString();
    }

    private volatile boolean running;
    private Thread worker;
    private Thread pusher;
    private final Wake.State state = new Wake.State();

    /**
     * Test + diagnostics surface. The app's own Log.i output does not show up in
     * logcat on this device, so internal state is kept here and reported through
     * a notification instead of being trusted to the log.
     */
    private static volatile AssistantService instance;
    static volatile String diagStatus = "not started";
    static volatile String diagLastTranscript = "-";
    static volatile String diagLastAnswer = "-";
    static volatile String diagLastError = "-";
    static volatile int diagLines;
    static volatile int diagHeard;
    static volatile int diagFired;
    static volatile String diagPush = "-";
    private boolean outage;          // an outage has been announced
    private int consecutiveFailures;

    /** Feed a phrase through the exact path a spoken transcript takes. */
    public static boolean inject(String text) {
        final AssistantService s = instance;
        if (s == null || !s.running) {
            return false;
        }
        // Unescape here too, so the test hook goes through the SAME normalization
        // a real transcript gets. Otherwise this hook quietly tests a different
        // code path than the one that runs when someone actually speaks.
        final String normalized = unescapeJson(text);
        new Thread(() -> {
            // With real speech, the REQUEST is visible on the lens too - it is
            // RayNeo's own live-transcript UI, driven by the mic/wake pipeline
            // this hook deliberately bypasses. Without it, a demo recorded off
            // this hook shows an answer with no visible prompt. Echo the
            // request as its own card, held briefly so it is readable/
            // recordable, then run the real handler exactly as before - this
            // only touches the inject() path, so real spoken commands (which
            // never call inject()) are unaffected.
            Cards.post(s, Cards.title(s), "🎤 " + Cards.sanitize(normalized), "heard");
            try {
                Thread.sleep(1400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            s.handle(normalized);
        }, "inject").start();
        return true;
    }

    public static String diag() {
        return "status=" + diagStatus + " lines=" + diagLines + " heard=" + diagHeard
                + " fired=" + diagFired + " | last=" + diagLastTranscript
                + " | ans=" + diagLastAnswer + " | err=" + diagLastError + " | push=" + diagPush;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            return START_STICKY;
        }
        running = true;
        instance = this;
        Prefs.put(this, Prefs.RUNNING, true);
        goForeground("starting...");
        worker = new Thread(this::loop, "assistant");
        worker.start();
        // Timers and calendar heads-ups fire on their own schedule, independent
        // of anything being spoken.
        pusher = new Thread(this::pushLoop, "proactive");
        pusher.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (calendarObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(calendarObserver);
            } catch (Exception ignored) {
            }
            calendarObserver = null;
        }
        running = false;
        instance = null;
        Prefs.put(this, Prefs.RUNNING, false);
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    private Notification foregroundNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(FG_CHANNEL, "Assistant service",
                    NotificationManager.IMPORTANCE_LOW);   // silent, no glasses card
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, FG_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(Prefs.trigger(this).substring(0, 1).toUpperCase()
                        + Prefs.trigger(this).substring(1) + " is listening")
                .setContentText(status)
                .setOngoing(true)
                .build();
    }

    private void status(String s) {
        diagStatus = s;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(FG_ID, foregroundNotification(s));
        }
        Log.i(TAG, s);
    }

    private android.database.ContentObserver calendarObserver;

    /**
     * Watch the calendar so an edit reaches the glasses in seconds.
     *
     * Registered for descendants of the whole provider, because an event
     * changing touches Events, Instances and Reminders and there is no single
     * URI that covers them all.
     */
    private void watchCalendar() {
        if (calendarObserver != null || checkSelfPermission(
                android.Manifest.permission.READ_CALENDAR)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        calendarObserver = new android.database.ContentObserver(
                new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                Mirror.onCalendarChanged();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    android.provider.CalendarContract.CONTENT_URI, true, calendarObserver);
            Log.i(TAG, "watching the calendar for changes");
        } catch (Exception e) {
            Log.w(TAG, "calendar observer: " + e);
            calendarObserver = null;
        }
    }

    /** Fires due timers and calendar heads-ups. Needs no adb - all local. */
    private void pushLoop() {
        watchCalendar();
        while (running) {
            try {
                String pushed = Proactive.poll(this);
                if (pushed != null) {
                    Log.i(TAG, "pushed " + pushed);
                }
            } catch (Exception e) {
                Log.w(TAG, "proactive: " + e);
            }
            try {
                // Five seconds, not twenty: the mirror's debounce is three, and
                // a calendar edit should reach the lens while you are still
                // looking at the phone you made it on.
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Set when the location type had to be dropped to start at all. */
    private volatile boolean locationTypeDropped;

    /**
     * Enter the foreground, keeping the location type only if it is allowed.
     *
     * ACCESS_FINE_LOCATION is a while-in-use permission, so holding it is not
     * enough: Android 14 also requires the app to be in an "eligible state"
     * when a location-typed foreground service starts, and being in the
     * background is not one. BootReceiver starts this service from exactly
     * there, so asking for the location type on the way back from a reboot
     * threw SecurityException and killed the process - the app crashed on every
     * restart while the notification, the link and the pairing were all fine.
     *
     * So the location bit is requested and given up rather than assumed. The
     * assistant runs either way; without it, location-dependent answers fall
     * back to the configured city until {@link #promoteLocationType} can put
     * the bit back, which is the same trade the app already makes when the
     * permission is refused outright.
     */
    private void goForeground(String text) {
        final int SPECIAL_USE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        final int LOCATION = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (Loc.permitted(this)) {
            try {
                startForeground(FG_ID, foregroundNotification(text), SPECIAL_USE | LOCATION);
                locationTypeDropped = false;
                return;
            } catch (SecurityException e) {
                // Background start. Fall through - and MUST still reach
                // startForeground, or the system kills us for not having.
                Log.i(TAG, "starting without the location type: " + e);
            }
        }
        startForeground(FG_ID, foregroundNotification(text), SPECIAL_USE);
        locationTypeDropped = Loc.permitted(this);
    }

    /**
     * Put the location type back, now that the app is visible.
     *
     * Called from MainActivity.onResume: an app in the foreground IS in the
     * eligible state, so the call that failed at boot succeeds here. Without
     * this, one reboot would silently cost location for the rest of the
     * service's life. Cheap and idempotent - it does nothing in the normal case
     * where the type was never dropped.
     */
    public static void promoteLocationType() {
        AssistantService s = instance;
        if (s == null || !s.locationTypeDropped) {
            return;
        }
        try {
            s.goForeground(diagStatus);
            Log.i(TAG, "location type restored");
        } catch (Exception e) {
            Log.w(TAG, "could not restore the location type: " + e);
        }
    }

    /** How long after a reboot to keep quiet about a failed connect. */
    private static final long BOOT_SETTLE_MS = 90_000L;

    /**
     * Is the device still coming up?
     *
     * BootReceiver starts the assistant the moment BOOT_COMPLETED lands, which
     * is well before Wi-Fi associates - and without Wi-Fi there is no mDNS, so
     * adbd cannot be found and the first few connects fail for a reason that
     * fixes itself. Without this guard every single reboot ended with a
     * "needs reconnecting" notification and a warning card on the glasses about
     * twenty seconds in, followed by a silent recovery - training the user to
     * ignore the one notification that matters.
     *
     * elapsedRealtime() is time since boot, so this is exactly the question
     * being asked and it costs no permission. A genuine outage still gets
     * announced, just on the retry after the window closes.
     */
    private static boolean stillBooting() {
        return android.os.SystemClock.elapsedRealtime() < BOOT_SETTLE_MS;
    }

    /** Reconnecting read loop over `shell:logcat`. */
    private void loop() {
        int backoff = 5;
        while (running) {
            AdbConnection conn = null;
            try {
                status("connecting to local adb...");
                conn = LocalAdb.connect(this);
                // -T 1 starts at "now" so a reconnect never replays old speech
                AdbStream stream = conn.open("shell:logcat -v time -T 1");
                status("listening (wake word: " + Prefs.trigger(this) + ")");
                backoff = 5;
                consecutiveFailures = 0;
                if (outage) {                       // came back on its own
                    outage = false;
                    Recovery.clear(this, true);
                }
                StringBuilder partial = new StringBuilder();
                while (running && !stream.isClosed()) {
                    String chunk = new String(stream.read(), "UTF-8");
                    partial.append(chunk);
                    int nl;
                    while ((nl = partial.indexOf("\n")) >= 0) {
                        String line = partial.substring(0, nl);
                        partial.delete(0, nl + 1);
                        diagLines++;
                        try {
                            onLine(line);
                        } catch (Exception e) {
                            Log.w(TAG, "line handler: " + e);
                        }
                    }
                }
            } catch (Exception e) {
                diagLastError = String.valueOf(e);
                status("adb error: " + e);
                // One failure is usually a blip; two means it is really gone.
                // Announce once, then keep retrying quietly so it self-heals
                // the moment the link is restored.
                if (++consecutiveFailures >= 2 && !outage && !stillBooting()) {
                    outage = true;
                    try {
                        Recovery.announce(this, e);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (running) {
                try {
                    Thread.sleep(backoff * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, 120);
            }
        }
    }

    private void onLine(String line) {
        if (line.contains("\"cmd\":\"screen_status\"") && line.contains("\"value\":1")) {
            Cards.repostOnWake(this);
            return;
        }
        String text = null;
        if ("alwayson".equals(Prefs.source(this))) {
            Matcher m = ASR_ALWAYSON.matcher(line);
            if (m.find()) {
                text = unescapeJson(m.group(2)).trim();
            }
        } else {
            Matcher m = ASR_ASSISTANT.matcher(line);
            if (m.find()) {
                String payload = m.group(1);
                if (FINAL_TRUE.matcher(payload).find()) {
                    Matcher t = ASR_TEXT.matcher(payload);
                    if (t.find()) {
                        text = unescapeJson(t.group(1)).trim();
                    }
                }
            }
        }
        if (text == null || text.length() < 4) {
            return;
        }
        handle(text);
    }

    private void handle(String text) {
        double now = System.currentTimeMillis() / 1000.0;
        if (text.equals(state.lastText) && now - state.lastTextAt < 20) {
            return;                                  // logcat re-emits finals
        }
        state.lastText = text;
        state.lastTextAt = now;
        diagHeard++;
        diagLastTranscript = text;
        Log.i(TAG, "heard: " + text);

        String trigger = Prefs.trigger(this);
        String query;
        if ("assistant".equals(Prefs.source(this))) {
            // The crown press IS the trigger on this channel; strip the wake
            // word if it was spoken anyway.
            query = Wake.triggerHit(trigger, text) ? Wake.stripTrigger(trigger, text) : text;
        } else {
            List<String> mangles = Prefs.mangles(this);
            Wake.Result r = Wake.classify(trigger, text, state, now, mangles, 45, 6, 25);
            if (!r.fires()) {
                Log.i(TAG, "  " + r.action);
                return;
            }
            query = r.query;
        }
        if (query == null || query.isEmpty()
                || Wake.duplicateRequest(state, query, now, 45)) {
            return;
        }
        answer(query);
    }

    private void answer(String query) {
        // Timers and to-dos are handled locally - no LLM, no network...
        final Commands.Cmd cmd = Commands.parse(query);
        // ...unless the utterance carries a SECOND request and a model with
        // tools is there to take it. The phrase patterns understand one request
        // each and swallow the rest into a label ("Timer set: kick off egg and
        // also tell weather"); the model does both halves. See Commands.compound.
        final boolean compound = Prefs.bool(this, Prefs.TOOLS, true)
                && Llm.toolsAvailable(this) && Commands.compound(query);
        if (compound) {
            Log.i(TAG, "  compound request" + (cmd == null ? "" : " (skipping " + cmd.kind + ")"));
        }
        if (cmd != null && !compound) {
            String line = Commands.run(this, cmd);
            Log.i(TAG, "  command: " + line);
            if (line != null) {
                if ("assistant".equals(Prefs.source(this))
                        && Prefs.bool(this, Prefs.DEFER_NATIVE, true)) {
                    Cards.waitNativeIdle(this);
                }
                Cards.post(this, Cards.title(this), line,
                        "timer".equals(cmd.kind) ? "timer" : "answer");
            }
            status("listening (wake word: " + Prefs.trigger(this) + ")");
            return;
        }
        // "what are my to-dos" is answered from the store, not the model
        if (!compound && query.toLowerCase().matches(".*\\b(to-?dos?|task list|my tasks)\\b.*")
                && query.toLowerCase().matches(".*\\b(what|list|show|any|read)\\b.*")) {
            Cards.post(this, Cards.title(this), Proactive.todoSummary(this));
            return;
        }

        status("thinking: " + query);
        String prompt = query;
        String ctxBlock = "";
        try {
            ctxBlock = Search.context(this, query);
        } catch (Exception e) {
            Log.w(TAG, "search: " + e);
        }
        String cal = "";
        try {
            // Windowed on what was ASKED. A question naming a day gets that
            // day, not a rolling window that fills with today's events before
            // it ever reaches the day in question.
            cal = Cal.snapshotFor(this, query);
        } catch (Exception e) {
            Log.w(TAG, "calendar: " + e);
        }
        // The assistant's own data, so it can answer across all of it: "is
        // there anything on my list I could do before the 3 o'clock", "what did
        // I note about the landlord". This is the whole reason for holding
        // these here rather than leaving them to RayNeo, which can display them
        // but cannot be asked anything.
        String todos = "";
        String notes = "";
        try {
            todos = Store.todoSnapshot(this);
            notes = Notes.snapshot(this);
        } catch (Exception e) {
            Log.w(TAG, "store: " + e);
        }
        if (!ctxBlock.isEmpty() || !cal.isEmpty() || !todos.isEmpty() || !notes.isEmpty()) {
            prompt = (ctxBlock.isEmpty() ? "" : ctxBlock + "\n\n")
                    + (cal.isEmpty() ? "" : cal + "\n\n")
                    + (todos.isEmpty() ? "" : todos + "\n\n")
                    + (notes.isEmpty() ? "" : notes + "\n\n")
                    + "Using the context above only when relevant, answer: " + query;
            Log.i(TAG, "  context: " + (ctxBlock.isEmpty() ? "" : "search ")
                    + (cal.isEmpty() ? "" : "calendar ")
                    + (todos.isEmpty() ? "" : "todos ")
                    + (notes.isEmpty() ? "" : "notes"));
        }
        String answer;
        String kind = "answer";
        try {
            if (Prefs.bool(this, Prefs.TOOLS, true)) {
                // The model may ACT here - set the timer, add the item, start
                // the music - through the same functions the regexes reach.
                // What comes back is already the line for the glasses: an
                // action's own words, with the model's prose only where a
                // question was answered too.
                Llm.Turn turn = Llm.askWithTools(this, prompt);
                answer = turn.render();
                kind = turn.cardKind();
                if (turn.calls > 0) {
                    Log.i(TAG, "  tools: " + turn.calls + " call(s), "
                            + turn.actions.size() + " action(s)");
                }
                if (answer.isEmpty() && turn.silent) {
                    // A hand-off that draws its own cards; nothing to add.
                    status("listening (wake word: " + Prefs.trigger(this) + ")");
                    return;
                }
            } else {
                answer = Llm.ask(this, prompt);
            }
        } catch (Exception e) {
            Log.w(TAG, "llm: " + e);
            if (cmd != null) {
                // The model was preferred for a compound request and could not
                // be reached. Do the part the phrase patterns understood rather
                // than nothing at all - a timer with a clumsy label beats no
                // timer.
                String line = Commands.run(this, cmd);
                Log.i(TAG, "  fallback command: " + line);
                if (line != null) {
                    Cards.post(this, Cards.title(this), line,
                            "timer".equals(cmd.kind) ? "timer" : "answer");
                }
                status("listening (wake word: " + Prefs.trigger(this) + ")");
                return;
            }
            diagLastError = String.valueOf(e.getMessage());
            status("llm error: " + e.getMessage());
            return;
        }
        answer = Cards.decorate(Cards.sanitize(answer));
        if (answer.isEmpty()) {
            // Nothing survived sanitising (an emoji-only reply); don't leave
            // the status saying "thinking" until the next question.
            status("listening (wake word: " + Prefs.trigger(this) + ")");
            return;
        }
        diagFired++;
        diagLastAnswer = answer;
        Log.i(TAG, "  answer: " + answer);
        // On the crown channel RayNeo answers too and its reply owns the
        // display, so hold ours until theirs has come and gone.
        if ("assistant".equals(Prefs.source(this))
                && Prefs.bool(this, Prefs.DEFER_NATIVE, true)) {
            Cards.waitNativeIdle(this);
        }
        // Paged: a longer answer arrives as consecutive cards rather than
        // being clipped at the first one.
        Cards.postSequence(this, Cards.title(this), answer, kind);
        status("listening (wake word: " + Prefs.trigger(this) + ")");
    }

    public static void start(Context c) {
        // Record the INTENT before the service exists, so a reboot between now
        // and onStartCommand still leaves BootReceiver something to act on.
        Prefs.put(c, Prefs.WANTED, true);
        Intent i = new Intent(c, AssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        // Only an explicit stop clears the intent. onDestroy deliberately does
        // not: it also runs when the system tears the process down, and
        // treating that as "the user wanted it off" is what would make the
        // assistant stay dead after a reboot.
        Prefs.put(c, Prefs.WANTED, false);
        c.stopService(new Intent(c, AssistantService.class));
    }
}
