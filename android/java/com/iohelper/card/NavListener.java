package com.iohelper.card;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Relays Google Maps' live turn-by-turn onto the glasses as iohelper cards.
 *
 * This replaces the old habit of asking the model to INVENT turn-by-turn
 * directions, which fabricated streets nowhere near the route ("55th St"). The
 * card now carries Maps' EXACT instruction text, nothing made up.
 *
 * Maps posts ONE ongoing navigation notification (id 1, category=navigation)
 * and updates it every second or two as the distance and ETA tick, so
 * onNotificationPosted fires constantly for the SAME maneuver. A lens card is
 * posted only when the maneuver text (android.title) actually CHANGES - the
 * ETA/distance churn produces zero cards.
 *
 * No feedback loop: the FIRST thing checked is the package, so iohelper's own
 * posted card (com.iohelper.card) is dropped before anything else runs.
 *
 * The system binds and unbinds this; it needs no foreground service and runs
 * whether or not the assistant is up. Android drops the binding when the app is
 * updated, so {@link BootReceiver} requestRebind()s on MY_PACKAGE_REPLACED.
 */
public class NavListener extends NotificationListenerService {

    private static final String TAG = "iohelperNav";
    private static final String MAPS = "com.google.android.apps.maps";
    /** A reroute removes then re-posts the nav notification within ~1s; wait it out. */
    private static final long END_DEBOUNCE_MS = 2000;

    /**
     * How often the card may refresh while only the distance ticks. OFF.
     *
     * Maps steps the distance down as you approach (0.5 mi, 0.4, 0.3...), and
     * with a refresh interval every one of those steps redrew the card. On a
     * real drive that meant the same turn onto the same street arriving again
     * and again, starting from the moment it became the next maneuver - a mile
     * of being told about one turn. Cards should correspond to something
     * happening, and the counter moving is not something happening.
     *
     * What remains is the two that ARE events: the maneuver becoming current,
     * which is when Maps itself starts showing it, and the turn going
     * imminent. Set nav.update_ms above 0 to get the old ticking behaviour.
     */
    private static final int DEFAULT_UPDATE_MS = 0;
    /**
     * Inside this many metres the turn is IMMINENT: refresh at once rather than
     * waiting for the throttle. This is the "coming up" prompt - Maps counts a
     * turn down as you approach, and that countdown is most of the value of a
     * heads-up display. Without it the card was drawn once, when the maneuver
     * first appeared (often half a mile back), and never again at the moment
     * you actually had to turn.
     */
    private static final double CLOSE_M = 120;

    /** The maneuver with its leading distance removed: the turn's IDENTITY, so
     *  a shrinking counter does not read as a new turn every second. */
    private static volatile String lastManeuver;
    /** The last INSTRUCTION drawn (glyph + distance + maneuver), so an
     *  unchanged turn is never redrawn just because the ETA drifted. */
    private static volatile String lastBody;
    private static volatile long lastPostAt;
    /** Whether the imminent-turn refresh has already fired for this maneuver. */
    private static volatile boolean closeAlerted;
    /** True while a trip is actually running, so the rest of the app can get
     *  out of the way of it. See {@link #navigating()}. */
    private static volatile boolean navActive;
    /** The bound listener, so "end navigation" can reach the live notification. */
    private static volatile NavListener instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingEnd;

    /**
     * Maps prefixes the maneuver with a shrinking distance ("500 ft - Turn left
     * onto X", then 400, 300...). Captured now rather than thrown away: the
     * distance is the most actionable thing on the card, and its VALUE is what
     * tells us the turn is imminent.
     */
    private static final java.util.regex.Pattern DIST = java.util.regex.Pattern.compile(
            "(?i)^(?:in\\s+)?([\\d.,]+)\\s*(ft|feet|mi|miles?|m|meters?|km|yd|yards?)\\b"
            + "\\s*[·:,\\-]?\\s*");

    /**
     * Whether turn-by-turn is live right now. Used by {@link Cards} to hold
     * back unprompted cards while someone is driving: a calendar heads-up or a
     * "now playing" wiping the turn you are about to take is the one moment the
     * glasses must not be chatty.
     */
    static boolean navigating() {
        return navActive;
    }

    /**
     * Forget the last trip. A newly launched navigation must not be deduped
     * against the one before it: switching travel mode updates the SAME
     * notification in place, and two trips leaving the same spot open with
     * identical text ("Head toward Main St") - which the dedup swallowed,
     * leaving the PREVIOUS trip's stale card sitting on the lens.
     */
    static void newTrip() {
        navActive = false;
        lastManeuver = null;
        lastBody = null;
        closeAlerted = false;
        lastPostAt = 0;
    }

    /** Maps' own units, in metres, for the imminent-turn test. */
    private static double metersOf(String value, String unit) {
        try {
            double n = Double.parseDouble(value.replace(",", ""));
            switch (unit.toLowerCase(java.util.Locale.US)) {
                case "ft": case "feet":
                    return n * 0.3048;
                case "yd": case "yard": case "yards":
                    return n * 0.9144;
                case "mi": case "mile": case "miles":
                    return n * 1609.344;
                case "km":
                    return n * 1000;
                default:
                    return n;                        // m / meter / meters
            }
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Also here, not only in onListenerConnected: after an app update the
        // service is recreated and can be handed events before the connected
        // callback lands, and "end navigation" arriving in that window found a
        // null instance and silently fell through to force-stopping Maps.
        instance = this;
        listenerReadyAt = System.currentTimeMillis();   // guard the relay from the boot burst
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        // Fires on grant, boot, and every rebind. The dedup state is NOT reset
        // here: a rebind within a live process - and the system does drop and
        // re-bind listeners under memory pressure - would otherwise replay the
        // current maneuver as if new, re-announcing a turn already on the lens.
        // After a real restart the statics are empty anyway, so a trip under
        // way is still picked up once. Logged, so a binding flap is visible in
        // the relay log rather than a mystery repeat.
        listenerReadyAt = System.currentTimeMillis();   // start of the re-post storm
        Log.i(TAG, "connected; replaying live");
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    relay(sbn);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onListenerConnected: " + e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        relay(sbn);                                  // Maps turn-by-turn
        relayApp(sbn);                               // allowlisted apps, in full
    }

    /** Per-notification-key content hash, so an unchanged repost is not redrawn. */
    private static final java.util.Map<String, Integer> lastRelayed =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** When the listener last (re)connected - the start of the boot re-post storm. */
    private static volatile long listenerReadyAt;
    /**
     * After a (re)connect every app re-posts its whole backlog at once. On a
     * battery-death reboot a busy app's standing items all arrive as fresh
     * onNotificationPosted calls and, with the in-RAM de-dup wiped by the
     * restart, every one looks new and floods the lens. For this long after
     * connect, relayApp LEARNS the backlog (records its hashes so later reposts
     * are deduped) but shows nothing; only items that arrive after the window -
     * genuinely new ones - reach the glasses.
     */
    private static final long STARTUP_QUIET_MS = 60000;

    /**
     * Relay an allowlisted app's notification to the glasses IN FULL.
     *
     * RayNeo's mirror keeps the title and a clipped line and drops the rest, so
     * a notification whose detail runs past ~115 characters arrives on the lens
     * with the important part cut off and its action buttons gone. iohelper
     * reads the whole notification and pages it with {@link Cards#postSequence},
     * so the full text reaches the lens across cards.
     *
     * Only apps the user names in {@link Prefs#NOTIFY_RELAY} are touched - the
     * default is EMPTY, so this does nothing until a package is set, which keeps
     * any one person's private app list out of the shipped code. iohelper's own
     * notifications are never relayed, and a group SUMMARY is skipped: it
     * re-ticks with no detail, while the individual items under it carry it.
     */
    private void relayApp(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) {
            return;                                  // no self-loop
        }
        String allow = Prefs.str(this, Prefs.NOTIFY_RELAY, "");
        if (allow.isEmpty() || !inList(allow, pkg)) {
            return;                                  // empty default: off until set
        }
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;                                  // the count-ticker, not a job
        }
        Bundle ex = n.extras;
        if (ex == null) {
            return;
        }
        String title = extra(ex, Notification.EXTRA_TITLE);
        String big = extra(ex, Notification.EXTRA_BIG_TEXT);
        String text = big.isEmpty() ? extra(ex, Notification.EXTRA_TEXT) : big;
        String content = text.isEmpty() ? title : text;
        if (content.isEmpty()) {
            return;
        }
        // Dedup per notification key: an app reposts the same item as its list
        // refreshes, and without this every refresh would redraw it.
        String key = sbn.getKey();
        int hash = (title + "" + text).hashCode();
        Integer prev = lastRelayed.get(key);
        if (prev != null && prev == hash) {
            return;
        }
        if (lastRelayed.size() > 200) {
            lastRelayed.clear();                     // unbounded guard
        }
        lastRelayed.put(key, hash);
        // Swallow the opening backlog burst: learn the hash above, show nothing
        // until the connect has settled. This is what stops a reboot (or any
        // listener rebind) from dumping every standing job onto the lens.
        if (System.currentTimeMillis() - listenerReadyAt < STARTUP_QUIET_MS) {
            return;
        }
        // "push" kind: uninvited, so nav focus can hold it back while driving,
        // exactly like a calendar heads-up.
        String line2 = title.isEmpty() ? Cards.title(this) : title;
        Cards.postSequence(this, line2, content, "push");
        Log.i(TAG, "relayApp " + pkg + ": " + content.length() + " chars");
    }

    /** Whether a comma/space-separated allowlist contains an exact package. */
    private static boolean inList(String list, String pkg) {
        for (String p : list.split("[,\\s]+")) {
            if (p.trim().equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isMapsNav(sbn)) {
            return;
        }
        // Navigation ended - or Maps is swapping the notification during a
        // reroute. Debounce: show "Navigation ended" only if nothing re-posts
        // within a couple of seconds. Never claim "Arrived".
        cancelEnd();
        pendingEnd = new Runnable() {
            @Override
            public void run() {
                newTrip();                           // next trip's first maneuver posts
                if (Prefs.bool(NavListener.this, Prefs.NAV_RELAY, true)) {
                    Cards.post(NavListener.this, Cards.title(NavListener.this),
                            "Navigation ended", "nav");
                }
            }
        };
        main.postDelayed(pendingEnd, END_DEBOUNCE_MS);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        // Self-heal: ask the system to bind us again (no-op if access is off).
        try {
            requestRebind(new ComponentName(this, NavListener.class));
        } catch (Exception ignored) {
        }
    }

    /**
     * End the trip the way tapping Maps' own "Exit" does.
     *
     * Maps hangs an Exit/Stop action off its ongoing navigation notification,
     * and a PendingIntent can be fired by whoever holds it - so sending that one
     * IS the button press: Maps tears the trip down itself, cleanly, and clears
     * its own notification. That matters because the obvious alternative,
     * force-stopping Maps, kills the process with the notification still posted
     * and leaves an inert husk in the shade that nothing will ever update.
     */
    static String stopNavigation(android.content.Context ctx) {
        NavListener s = instance;
        boolean dismissed = false;
        int seen = 0;
        if (s == null) {
            Log.i(TAG, "stopNavigation: no bound listener, falling back");
        } else {
            try {
                StatusBarNotification[] active = s.getActiveNotifications();
                for (StatusBarNotification sbn : active == null
                        ? new StatusBarNotification[0] : active) {
                    if (!isMapsNav(sbn)) {
                        continue;
                    }
                    seen++;
                    Notification n = sbn.getNotification();
                    if (n.actions != null) {
                        for (Notification.Action a : n.actions) {
                            String ti = a.title == null ? ""
                                    : a.title.toString().toLowerCase(java.util.Locale.US);
                            if (a.actionIntent != null && (ti.contains("exit")
                                    || ti.contains("stop") || ti.contains("end")
                                    || ti.contains("close"))) {
                                a.actionIntent.send();
                                newTrip();
                                Log.i(TAG, "stopNavigation: fired Maps' own action");
                                return "⌖ Navigation ended";
                            }
                        }
                    }
                    s.cancelNotification(sbn.getKey());
                    dismissed = true;
                }
                Log.i(TAG, "stopNavigation: nav notifications=" + seen);
            } catch (Exception e) {
                Log.w(TAG, "stopNavigation via notification: " + e);
            }
        }
        try {
            LocalAdb.shell(ctx, "am force-stop com.google.android.apps.maps", 8000);
            newTrip();
            return "⌖ Navigation ended";
        } catch (Exception e) {
            newTrip();
            return dismissed ? "⌖ Navigation cleared from the glasses"
                             : "Could not end navigation (" + e + ")";
        }
    }

    private static boolean isMapsNav(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName())) {
            return false;                            // PACKAGE FIRST - no self-loop
        }
        Notification n = sbn.getNotification();
        if (n == null) {
            return false;
        }
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) {
            return true;
        }
        // A transit step may not carry category=navigation, but Maps groups its
        // navigation notifications under "navigation_status_notification_group";
        // accept that so departure/arrival/vehicle steps relay too - still
        // nav-specific, so a "traffic near you" promo does not match.
        String g = n.getGroup();
        return g != null && g.toLowerCase(java.util.Locale.US).contains("navigation");
    }

    private void relay(StatusBarNotification sbn) {
        if (!isMapsNav(sbn)) {
            return;
        }
        if (!Prefs.bool(this, Prefs.NAV_RELAY, true)) {
            return;                                  // user gate
        }
        Bundle ex = sbn.getNotification().extras;
        if (ex == null) {
            return;
        }
        // android.title = the maneuver ("Head toward Mountain Blvd"), sometimes
        // a SpannableString -> toString(). subText = the ETA ("Arrive 11:57 AM").
        CharSequence titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
        String raw = Cards.sanitize(titleCs == null ? "" : titleCs.toString());
        // Split the shrinking distance off the front. The REST is the turn's
        // identity, so the countdown does not read as a new maneuver every
        // second; the distance itself is kept, and drawn.
        String dist = "";
        double metres = Double.NaN;
        String maneuver = raw;
        java.util.regex.Matcher dm = DIST.matcher(raw);
        if (dm.find()) {
            dist = dm.group(1) + " " + dm.group(2);
            metres = metersOf(dm.group(1), dm.group(2));
            maneuver = raw.substring(dm.end()).trim();
        }
        if (maneuver.isEmpty()) {
            // A nav-category notification with NO maneuver title is not a step -
            // it is a PROMPT, and the detour offer that could not be answered
            // on the drive home is the prime suspect: its content lives in the
            // action buttons and bigText, not the title, so keying off the
            // title alone drops it here silently. Log its SHAPE (button count,
            // each label classified as accept / dismiss / other, text lengths -
            // never the road names) so the next real one on a drive documents
            // itself and this can grow into relaying the prompt with its
            // choices. Nothing is posted yet; this only observes.
            Notification n = sbn.getNotification();
            int na = n.actions == null ? 0 : n.actions.length;
            if (na > 0) {
                StringBuilder shape = new StringBuilder();
                for (Notification.Action a : n.actions) {
                    String label = a.title == null ? "" : a.title.toString();
                    shape.append(promptChoice(label)).append('/').append(label.length()).append(' ');
                }
                Log.i(TAG, "nav-prompt: actions=" + na + " { " + shape + "} textLen="
                        + extra(ex, Notification.EXTRA_TEXT).length() + " bigLen="
                        + extra(ex, Notification.EXTRA_BIG_TEXT).length());
            }
            return;
        }
        // The turn's IDENTITY, for dedup: the instruction with EVERY distance
        // token removed, not only a leading one. If Maps phrases a step with
        // the distance elsewhere ("turn right in 0.5 mi"), a leading-only strip
        // leaves the number in, and each tick of it reads as a brand-new
        // maneuver - a card per second for one turn, and no throttle setting
        // can stop it because it never reaches the throttle.
        String identity = maneuver.replaceAll(
                "(?i)\\b(?:in\\s+)?[\\d.,]+\\s*(?:ft|feet|mi|miles?|m|meters?|km|yd|yards?)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        // A live maneuver means navigation is alive - cancel any pending
        // "Navigation ended" NOW, before the throttle below can return early. A
        // reroute removes and re-posts the notification with the SAME text, so
        // deferring this until after the dedup announced the trip had ended in
        // the middle of it.
        cancelEnd();

        // Secondary details on ONE line (no extra card): the ETA/arrival from
        // subText, PLUS any distinct transit info Maps puts in text/bigText - the
        // line and vehicle, the boarding/alighting stop ("Bus 51A", "BART to
        // Richmond"). Driving/walking leave text null, so those cards are
        // unchanged except for the glyph.
        String sub = extra(ex, Notification.EXTRA_SUB_TEXT);
        String text = extra(ex, Notification.EXTRA_TEXT);
        if (text.isEmpty()) {
            text = extra(ex, Notification.EXTRA_BIG_TEXT);
        }
        String info = sub;
        String ml = maneuver.toLowerCase(java.util.Locale.US);
        if (!text.isEmpty() && !text.equalsIgnoreCase(sub)
                && !ml.contains(text.toLowerCase(java.util.Locale.US))) {
            info = sub.isEmpty() ? text : sub + " · " + text;
        }
        // Header / details / instruction. A glyph chosen from the maneuver leads
        // the instruction: ← → ↑ for turns, ↖ ↗ for slight/keep, ↩ U-turn,
        // ↻ roundabout, ⌖ arrive. Verbatim Maps text otherwise - nothing
        // invented. Cards.post handles the 115-char clip/spill.
        String line2 = info.isEmpty() ? Cards.title(this) : info;
        String body = glyphFor(maneuver)
                + (dist.isEmpty() ? " " : " " + dist + " · ") + maneuver;

        // WHEN to draw. The distance and ETA tick about once a second, far too
        // fast to redraw a card someone is reading, but "once per maneuver" was
        // too slow - it drew the turn half a mile out then went silent through
        // the turn itself. So: always on a new maneuver, always the moment the
        // turn goes imminent, otherwise no faster than the throttle.
        //
        // Compared on the BODY - glyph, distance, maneuver - and never on the
        // whole card. Including the ETA line meant a drifting "Arrive 4:12 PM"
        // redrew a turn whose own text had not changed: measured on a real
        // drive as three consecutive cards showing the identical turn and
        // distance onto the same street.
        boolean newManeuver = !identity.equals(lastManeuver);
        boolean imminent = !Double.isNaN(metres) && metres <= CLOSE_M;
        int updateMs = Prefs.integer(this, Prefs.NAV_UPDATE_MS, DEFAULT_UPDATE_MS);
        long now = System.currentTimeMillis();
        String why;
        if (newManeuver) {
            // = imminent, not false. When Maps advances straight to a turn that
            // is ALREADY inside CLOSE_M - back-to-back city turns, ramps,
            // roundabout exits - this first card IS the imminent card.
            closeAlerted = imminent;
            why = "new";
        } else if (body.equals(lastBody)) {
            return;                                  // nothing new to say
        } else if (imminent && !closeAlerted) {
            closeAlerted = true;                     // the turn is on you: now
            why = "imminent";
        } else if (updateMs <= 0 || now - lastPostAt < updateMs) {
            return;                                  // only the counter moved
        } else {
            why = "refresh";
        }
        // Anything reaching here is a real change: a new maneuver, the turn
        // going imminent, or an explicitly enabled refresh interval.
        lastManeuver = identity;
        lastBody = body;
        lastPostAt = now;
        navActive = true;
        Cards.post(this, line2, body, "nav");
        Log.i(TAG, "relayed[" + why + "]: " + body);
    }

    /**
     * Classify a prompt button by its VERB, not its content: "accept" / "yes" /
     * "reroute" / "take" mean accept, "no" / "dismiss" / "ignore" mean dismiss,
     * anything else is "?". Only the class is logged, never the label, so a
     * detour offer's road names never reach the log - and this same match is
     * what a future accept-on-gesture would use to pick which action to fire.
     */
    private static String promptChoice(String label) {
        String s = label.toLowerCase(java.util.Locale.US);
        if (s.matches(".*\\b(accept|yes|ok|okay|reroute|re-route|take|faster|new route|go)\\b.*")) {
            return "ACCEPT";
        }
        if (s.matches(".*\\b(no|dismiss|ignore|cancel|keep|stay|decline)\\b.*")) {
            return "DISMISS";
        }
        return "?";
    }

    private static String extra(Bundle ex, String key) {
        CharSequence cs = ex.getCharSequence(key);
        return Cards.sanitize(cs == null ? "" : cs.toString());
    }

    /**
     * A direction glyph for a Maps maneuver. Read from the instruction HEAD
     * (before "onto/on/toward <street>"), so a street name containing
     * "left"/"right" (Wright St, Left Bank) can't flip the arrow. Plain BMP
     * arrows, which the lens font renders; anything unrecognised is "proceed".
     */
    static String glyphFor(String maneuver) {
        String s = maneuver.toLowerCase(java.util.Locale.US);
        if (s.contains("roundabout") || s.contains("traffic circle") || s.contains("rotary")) {
            return "↻";                         // ↻
        }
        String head = s.split("\\b(?:onto|toward|towards|on|at|via)\\b", 2)[0];
        if (head.matches(".*\\bu[-\\s]?turn\\b.*") || head.contains("make a u")) {
            return "↩";                         // ↩
        }
        if (head.matches(".*\\b(?:arrive|arrived|arriving|destination|reached)\\b.*")) {
            return "⌖";                         // ⌖ - you're there
        }
        if (head.matches(".*\\b(?:slight|keep|fork|bear)\\s+left\\b.*")) {
            return "↖";                         // ↖
        }
        if (head.matches(".*\\b(?:slight|keep|fork|bear)\\s+right\\b.*")) {
            return "↗";                         // ↗
        }
        if (head.matches(".*\\bleft\\b.*")) {
            return "←";                         // ←
        }
        if (head.matches(".*\\bright\\b.*")) {
            return "→";                         // →
        }
        if (head.matches(".*\\b(?:merge|exit|ramp)\\b.*")) {
            return "↗";                         // ↗
        }
        return "↑";                             // ↑ head/continue/depart/ride/straight
    }

    private void cancelEnd() {
        if (pendingEnd != null) {
            main.removeCallbacks(pendingEnd);
            pendingEnd = null;
        }
    }
}
