package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import java.util.regex.Pattern;

/**
 * Putting text on the glasses, plus the measured rules about how they render.
 *
 *  - body <=115 chars renders fully; 116-130 is visually cut; >=131 NEVER
 *    renders while everything upstream still reports success;
 *  - the limit is CHARACTERS, not bytes - a 107-char/152-byte glyph frame
 *    renders in full, so glyphs cost nothing extra;
 *  - non-BMP emoji render inconsistently and are stripped; BMP symbols are fine;
 *  - one stable notification id, so reposting updates the card in place instead
 *    of piling up (a pile triggers Android's LOCAL_ONLY grouping, which
 *    silently stops forwarding to the glasses);
 *  - cards render even to a dark screen, so the last one is re-posted once when
 *    the display wakes.
 */
public final class Cards {

    public static final String CHANNEL = "assistant";
    /**
     * The quiet channel the cards actually go out on.
     *
     * The notification is only the TRANSPORT to the lens, so on the PHONE it
     * should be silent and invisible: no heads-up banner sliding over whatever
     * is on screen, no sound, no vibration, for something the wearer has
     * already read on the glasses. A channel's importance is fixed when it is
     * created - createNotificationChannel on an existing id updates the name
     * and nothing else - so going quiet needs a NEW id, not an edit of
     * {@link #CHANNEL}. The phone's own business (Recovery's "needs
     * reconnecting", Pairing) stays on its own high-importance channels, which
     * is the one kind of popup worth interrupting for.
     */
    public static final String QUIET_CHANNEL = "assistant_quiet";
    public static final int CARD_ID = 1;
    public static final int DEFAULT_LIMIT = 115;
    private static final String EXTRA_SUBSTITUTE_APP_NAME = "android.substName";

    private static String lastBody;
    private static String lastTitle;
    private static long lastAt;
    private static boolean reposted = true;
    /** What is currently on the glasses: "answer" | "timer" | "progress". A
     *  countdown may replace a timer or an earlier countdown, but must never
     *  stomp an answer the user just asked for. */
    static volatile String lastKind = "answer";

    /** How long the card on the glasses has been up. Used to avoid wiping an
     *  answer the wearer is still reading with an unprompted push. */
    static long msSinceLastCard() {
        return lastAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastAt;
    }

    private Cards() {
    }

    /** Measured on the lens 2026-09-06: the title line holds ~59 chars. */
    private static final int DEFAULT_TITLE_LIMIT = 58;

    public static String title(Context c) {
        String t = Prefs.trigger(c);
        return t.isEmpty() ? "iO" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Collapse whitespace and drop non-BMP characters. */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isHighSurrogate(ch) && !Character.isLowSurrogate(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * The opening of a long answer, to be put on the TITLE line, cut at a word
     * boundary. Empty if the whole thing already fits in the body.
     */
    static String head(String text, int limit) {
        String s = sanitize(text);
        if (s.length() <= limit) {
            return "";
        }
        int cut = s.lastIndexOf(' ', limit);
        // A single very long word (a URL, a long place name) has no space to
        // break at; splitting it mid-word reads worse than leaving the line
        // alone, so give up rather than mangle it.
        if (cut < limit / 2) {
            return "";
        }
        return s.substring(0, cut).trim();
    }

    public static String clip(Context c, String text) {
        int limit = Prefs.integer(c, Prefs.BODY_LIMIT, DEFAULT_LIMIT);
        String s = sanitize(text);
        return s.length() <= limit ? s : s.substring(0, limit - 1).trim() + "…";
    }

    /**
     * Glyphs for the other answers people ask for most: markets and commodities,
     * news, routes, addresses, scores.
     *
     * All Basic-Multilingual-Plane symbols, because anything outside the BMP
     * renders inconsistently on this display and gets stripped before sending.
     * The glyph is a category marker, not decoration: on one green line it is
     * what tells you at a glance whether you are looking at a price or a route.
     */
    private static String nonWeatherGlyph(String answer, String low) {
        String g = "";
        // "price" and "cost" on their own are NOT markets words - an answer
        // about an opera mentioning a ticket price was getting a "$" glyph, as
        // though the West End had moved. They only count alongside an actual
        // amount; everything else here names an instrument or a currency and
        // stands by itself.
        boolean instrument = low.matches(".*(\\$|\\busd\\b|\\beuros?\\b"
                + "|\\bbarrel\\b|\\bcrude\\b|\\boil\\b|\\bstocks?\\b|\\bshares?\\b|\\bnasdaq\\b"
                + "|\\bdow\\b|\\bs&p\\b|\\bindex\\b|\\bbitcoin\\b|\\bbtc\\b|\\bethereum\\b"
                + "|\\bgold\\b|\\bexchange rate\\b|\\btrading\\b).*");
        boolean pricedAmount = low.matches(".*\\b(price[sd]?|costs?)\\b.*")
                && low.matches(".*(\\$\\s*\\d|\\d+\\s*(dollars?|cents?|usd|euros?|pounds?)).*");
        boolean money = instrument || pricedAmount;
        if (money) {
            // Direction first: on a price, up-or-down IS the news.
            if (low.matches(".*\\b(up|rose|rose to|gained|climbed|higher|rally|rallied|surged)\\b.*")) {
                g = "▲";
            } else if (low.matches(".*\\b(down|fell|dropped|lower|declined|slipped|sank|slid)\\b.*")) {
                g = "▼";
            } else {
                g = "$";
            }
        } else if (low.matches(".*\\b(headlines?|breaking|reported|reports|according to|announced)\\b.*")) {
            g = "▤";
        } else if (low.matches(".*\\b(\\d+\\s*min(ute)?s?\\b.*\\b(by car|drive|driving|walk|bike|transit|bart|bus)"
                + "|via\\b|\\broute\\b|\\bhighway\\b|\\bi-\\d+\\b).*")) {
            g = "→";
        } else if (low.matches(".*\\b(street|st\\.|avenue|ave\\.?|boulevard|blvd|road|rd\\.?"
                + "|drive|dr\\.|lane|ln\\.|way|plaza|you are at|located at)\\b.*")) {
            g = "⌖";
        } else if (low.matches(".*\\b(won|beat|defeated|final score|leads?|trailing|standings)\\b.*")) {
            g = "⚑";
        }
        return g.isEmpty() || answer.startsWith(g) ? answer : g + " " + answer;
    }

    /** A weather glyph on weather answers; never changes the words. */
    public static String decorate(String answer) {
        if (!answer.isEmpty()) {
            // Already carries a glyph - a tool's own "⏰ Cancelled: ..." line
            // followed by a weather answer was getting "☂ ⏰ ..." stacked on
            // it. One glyph per card; the first one wins.
            int t = Character.getType(answer.charAt(0));
            if (t == Character.OTHER_SYMBOL || t == Character.MATH_SYMBOL
                    || t == Character.CURRENCY_SYMBOL) {
                return answer;
            }
        }
        String low = answer.toLowerCase();
        if (!low.matches(".*(°|\\bdegrees\\b|\\bweather\\b|\\bforecast\\b|\\brain\\b|\\bsunny\\b|\\bcloudy\\b|\\bsnow\\b).*")) {
            return nonWeatherGlyph(answer, low);
        }
        // Pick the condition mentioned FIRST, not the one highest in a fixed
        // priority list. "Sunny now at 67 °F with 0% chance of rain" contains
        // both, and a fixed order drew an umbrella on a cloudless day.
        String[][] conditions = {
            {"thunder|storm|lightning", "⚡"},
            {"snow|flurr|sleet", "☃"},
            {"rain|shower|drizzle", "☂"},
            {"fog|mist|haze", "≈"},
            {"cloud|overcast", "☁"},
            {"sun|clear|fair|bright", "☀"},
        };
        String g = "";
        int best = Integer.MAX_VALUE;
        for (String[] c : conditions) {
            java.util.regex.Matcher m = Pattern.compile(c[0]).matcher(low);
            if (m.find() && m.start() < best) {
                best = m.start();
                g = c[1];
            }
        }
        return g.isEmpty() || answer.startsWith(g) ? answer : g + " " + answer;
    }

    public static void post(Context ctx, String title, String body) {
        post(ctx, title, body, "answer");
    }

    /**
     * Card kinds that arrive UNINVITED, and so are the ones worth silencing
     * while driving. "answer" is deliberately absent: it exists only because
     * someone asked a question, and swallowing a reply would be a worse bug
     * than the interruption this avoids.
     */
    private static final java.util.Set<String> UNPROMPTED =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "push", "media", "caption", "progress"));

    /**
     * Create the card channel and return the id to post on. THE single place
     * that decides quiet-versus-alerting, so every path that puts a card on the
     * glasses agrees. {@link ShowReceiver} used to own a second, hard-coded
     * IMPORTANCE_HIGH definition of "assistant", so cards driven from the PC
     * still popped a heads-up banner on the phone while the app's own switch
     * said they would not.
     */
    static String ensureChannel(Context ctx, NotificationManager nm) {
        boolean quiet = Prefs.bool(ctx, Prefs.QUIET_CARDS, true);
        String channel = quiet ? QUIET_CHANNEL : CHANNEL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(channel, "Assistant cards",
                    quiet ? NotificationManager.IMPORTANCE_LOW
                          : NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            if (quiet) {
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.enableLights(false);
            }
            nm.createNotificationChannel(ch);
        }
        return channel;
    }

    /** Bumped by every new answer, so a running sequence knows to give up. */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Long enough to read a full card before the next replaces it. */
    private static final int PAGE_GAP_MS = 9000;
    private static final int MAX_PAGES = 3;

    /**
     * An answer too long for one card, dealt out over several.
     *
     * The lens shows one card at a time and a new one replaces the last, so the
     * pages are PACED rather than posted together - fired in a burst they would
     * flash past and only the final one would ever be read. A page number is
     * appended when there is more than one, because otherwise a continuation
     * reads as a fresh, oddly abrupt answer.
     *
     * A new question abandons a sequence in flight: SEQ moves and the thread
     * notices. Answering the previous question over the top of the current one
     * is worse than dropping the tail.
     */
    public static void postSequence(Context ctx, String title, String body, String kind) {
        final java.util.List<String> pages = paginate(ctx, body);
        final int mine = SEQ.incrementAndGet();
        if (pages.size() <= 1) {
            post(ctx, title, body, kind);
            return;
        }
        post(ctx, title, pages.get(0) + "  (1/" + pages.size() + ")", kind, true);
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i < pages.size(); i++) {
                    try {
                        Thread.sleep(PAGE_GAP_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (SEQ.get() != mine) {
                        return;               // a newer answer owns the display
                    }
                    post(app, Cards.title(app),
                            pages.get(i) + "  (" + (i + 1) + "/" + pages.size() + ")",
                            kind, true);
                }
            }
        }, "card-pages").start();
    }

    /**
     * Split into card-sized pages, preferring sentence ends and never breaking
     * a word. Each page may use both the title and body lines, so the budget is
     * the sum of the two less the room the "(2/3)" marker takes.
     */
    static java.util.List<String> paginate(Context ctx, String text) {
        java.util.List<String> pages = new java.util.ArrayList<>();
        String s = sanitize(text);
        int titleLimit = Prefs.integer(ctx, Prefs.TITLE_LIMIT, DEFAULT_TITLE_LIMIT);
        int bodyLimit = Prefs.integer(ctx, Prefs.BODY_LIMIT, DEFAULT_LIMIT);
        // Does the WHOLE thing fit one card? A single card spills across the
        // title and body lines and needs no "(1/2)" marker, so its capacity is
        // the full title+body sum - not the sum-minus-marker used for real
        // multi-page splits. A 172-char answer is 7 over the marked budget but
        // fits one spilled card at 173; paging it split it into two cards that
        // flash 9s apart and read as truncated. Keep it whole here.
        if (s.length() <= titleLimit + bodyLimit) {
            pages.add(s);
            return pages;
        }
        int budget = titleLimit + bodyLimit - 8;
        // How many pages this needs, so they can be filled EVENLY. Greedily
        // filling the first page left "…tickets are" / "required and it's a
        // full-length performance" - a split mid-phrase, with a nearly empty
        // second card.
        int need = Math.min(MAX_PAGES, (s.length() + budget - 1) / budget);
        while (!s.isEmpty() && pages.size() < MAX_PAGES) {
            int left = need - pages.size();
            if (left <= 1 || s.length() <= budget) {
                pages.add(s.length() <= budget ? s : s.substring(0, budget).trim());
                break;
            }
            int target = Math.min(budget, (s.length() + left - 1) / left);
            int cut = boundary(s, target, budget);
            pages.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        return pages;
    }

    /**
     * Where a reader would pause. Sentence ends first, then a semicolon or
     * comma, then any space - searching outward from the balanced target so a
     * page breaks at a phrase rather than in the middle of one.
     */
    private static int boundary(String s, int target, int max) {
        int lo = Math.max(1, target / 2);
        int hi = Math.min(max, s.length() - 1);
        for (String set : new String[]{".!?", ";:", ","}) {
            int best = -1;
            for (int i = lo; i <= hi; i++) {
                if (set.indexOf(s.charAt(i)) >= 0
                        && (best < 0 || Math.abs(i - target) < Math.abs(best - target))) {
                    best = i;
                }
            }
            if (best > 0) {
                return best + 1;
            }
        }
        int best = -1;
        for (int i = lo; i <= hi; i++) {
            if (s.charAt(i) == ' '
                    && (best < 0 || Math.abs(i - target) < Math.abs(best - target))) {
                best = i;
            }
        }
        return best > 0 ? best : Math.min(max, s.length());
    }

    /** A level bar built from BMP block glyphs: bar(0.7) -> "▓▓▓▓▓▓▓░░░". */
    public static String bar(double fraction, int width) {
        int n = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(i < n ? '▓' : '░');
        }
        return sb.toString();
    }

    public static void post(Context ctx, String title, String body, String kind) {
        post(ctx, title, body, kind, false);
    }

    /**
     * @param forceSpill split across the title and body lines even when the
     *                   text would fit in the body alone. A continuation page
     *                   is short by nature, and without this it reverted to
     *                   "Jarvis" on the title line - throwing away the line the
     *                   spill exists to reclaim and making page 2 of an answer
     *                   look emptier than page 1.
     */
    public static void post(Context ctx, String title, String body, String kind,
                            boolean forceSpill) {
        // Driving: hold back anything nobody asked for. A calendar heads-up or
        // a "now playing" landing on top of the turn you are about to take is
        // the one moment the glasses must not be chatty - and unlike an answer,
        // it was not invited. Answers, the "heard" echo and the turns
        // themselves still come through, so this never costs you a reply.
        if (UNPROMPTED.contains(kind) && Prefs.bool(ctx, Prefs.NAV_FOCUS, false)
                && NavListener.navigating()) {
            return;
        }
        lastKind = kind;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        String channel = ensureChannel(ctx, nm);
        // THE TITLE LINE IS FREE SPACE. The glasses draw three lines - app
        // label, title, body - and this app used to put its own name on the
        // first two, so every card repeated "Jarvis" and threw the second line
        // away. Measured on the lens: the title carries ~59 characters, on top
        // of the body's 115. So when an answer would be clipped, spill its
        // opening into that line instead and roughly half again as much text
        // arrives. Short answers are left exactly as they were - a one-line
        // reply reads better under a plain header than split across two.
        String full = sanitize(body);
        String line2 = title;
        String rest = full;
        if (forceSpill || full.length() > Prefs.integer(ctx, Prefs.BODY_LIMIT, DEFAULT_LIMIT)) {
            int tl = Prefs.integer(ctx, Prefs.TITLE_LIMIT, DEFAULT_TITLE_LIMIT);
            String head = head(full, tl);
            if (head.isEmpty() && forceSpill && full.length() > tl / 2) {
                // head() declines when the whole thing fits; for a forced split
                // take a sensible first line anyway.
                int cut = full.lastIndexOf(' ', Math.min(tl, full.length() - 1));
                if (cut > tl / 3) {
                    head = full.substring(0, cut).trim();
                }
            }
            if (!head.isEmpty()) {
                line2 = head;
                rest = full.substring(head.length()).trim();
            }
        }
        String clipped = clip(ctx, rest);
        // Store what was actually drawn, not the original: repostOnWake feeds
        // these back through here, and a body already under the limit will not
        // spill a second time - so the wake re-post reproduces the same card.
        lastBody = clipped;
        lastTitle = line2;
        lastAt = System.currentTimeMillis();
        reposted = false;
        Notification.Builder b = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(line2)
                .setContentText(clipped)
                .setStyle(new Notification.BigTextStyle().bigText(clipped))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);   // reposts must re-alert or nothing re-renders
        Bundle extras = new Bundle();
        extras.putString(EXTRA_SUBSTITUTE_APP_NAME, title);   // ignored by the
        // current relay - the header is always the package label - but harmless
        // and still correct if a firmware ever honours it.
        b.addExtras(extras);
        nm.notify(CARD_ID, b.build());
        dismissLater(ctx, SHADE_SEQ.incrementAndGet());
    }

    /** Bumped by every card, so a pending dismissal knows whether it still owns
     *  the notification id. */
    private static final java.util.concurrent.atomic.AtomicInteger SHADE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final android.os.Handler SHADE =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** Comfortably longer than the relay takes to mirror a card to the lens. */
    private static final int DEFAULT_SHADE_MS = 8000;

    /**
     * Take the card back out of the PHONE's notification shade once the relay
     * has had it.
     *
     * The notification is only the TRANSPORT: RayNeo's companion app is a
     * notification listener, so a card has to be posted on the phone to reach
     * the lens at all. What it should not do is LIVE there - every answer left a
     * "Jarvis" entry sitting in the shade, and popped a heads-up over whatever
     * was on the phone's screen, for a card the wearer had already read on the
     * glasses. The lens keeps what it was sent, so the phone copy is disposable.
     *
     * Only the card that is still current dismisses itself: a newer post bumps
     * SHADE_SEQ, so the older dismissal skips instead of clearing the card that
     * replaced it. {@link Prefs#SHADE_MS} of 0 leaves them in the shade.
     */
    private static void dismissLater(Context ctx, final int mine) {
        int delay = Prefs.integer(ctx, Prefs.SHADE_MS, DEFAULT_SHADE_MS);
        if (delay <= 0) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        SHADE.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (SHADE_SEQ.get() != mine) {
                    return;                       // a newer card owns the id now
                }
                NotificationManager m = app.getSystemService(NotificationManager.class);
                if (m != null) {
                    m.cancel(CARD_ID);
                }
            }
        }, delay);
    }

    /** One-shot re-post when the glasses' display wakes shortly after a card. */
    public static void repostOnWake(Context ctx) {
        if (reposted || lastBody == null
                || System.currentTimeMillis() - lastAt > 120_000) {
            return;
        }
        // Re-post under the SAME kind: going through the 3-arg post() would
        // relabel it "answer" and silently cancel a running countdown.
        post(ctx, lastTitle, lastBody, lastKind);
        // AFTER the post, not before: post() clears this flag, so setting it
        // first meant the guard was wiped by the very call it was guarding, and
        // every later screen wake re-drew the same card again.
        reposted = true;
    }

    /**
     * Crown channel only. RayNeo answers the same utterance and its streamed
     * reply owns the display, so a card sent meanwhile is never seen. Our LLM
     * usually answers first, so wait for theirs to START, then to go quiet, then
     * dwell so it can be read. Bounded.
     */
    public static void waitNativeIdle(Context ctx) {
        final long start = System.currentTimeMillis();
        final long maxWait = 30_000, minHold = 10_000, quietMs = 3_000, dwell = 8_000;
        int lastCount = nativeCount(ctx);
        long lastChange = System.currentTimeMillis();
        boolean seen = false;
        while (System.currentTimeMillis() - start < maxWait) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            int count = nativeCount(ctx);
            if (count != lastCount) {          // they are still streaming
                seen = true;
                lastCount = count;
                lastChange = System.currentTimeMillis();
            } else if (seen && System.currentTimeMillis() - lastChange >= quietMs) {
                break;                         // started, then went quiet
            } else if (!seen && System.currentTimeMillis() - start >= minHold) {
                break;                         // they never answered at all
            }
        }
        if (seen) {                            // let their answer be read
            try {
                Thread.sleep(Math.min(dwell,
                        Math.max(0, maxWait - (System.currentTimeMillis() - start))));
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * How many VOICE_ASSISTANT frames are in the recent log. Activity is
     * detected by this COUNT CHANGING between polls rather than by parsing
     * timestamps - simpler, and it is the change we actually care about.
     */
    /**
     * What "RayNeo is still talking" actually looks like in the log.
     *
     * VOICE_ASSISTANT alone is far too sparse: during one real answer it
     * appeared exactly twice, ten seconds apart, so this poll saw three quiet
     * seconds, declared the display free, and drew our card while their
     * assistant was still streaming. Theirs overwrote ours about a second
     * later, which from the wearer's side reads as "it never answered".
     *
     * SherpaASREngine is their recogniser; it ticks every ~1.6s for as long as
     * it is actually running, which is the signal wanted here.
     */
    private static final String[] NATIVE_MARKERS = {
        "VOICE_ASSISTANT", "SherpaASREngine", "RayNeoAi_CPP",
    };

    private static int nativeCount(Context ctx) {
        try {
            String out = LocalAdb.shell(ctx, "logcat -d -t 400", 8000);
            int n = 0;
            for (String line : out.split("\n")) {
                for (String marker : NATIVE_MARKERS) {
                    if (line.contains(marker)) {
                        n++;
                        break;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }
}
