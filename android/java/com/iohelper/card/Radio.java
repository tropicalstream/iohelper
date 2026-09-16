package com.iohelper.card;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Internet radio: finding a station by name or genre, and playing it.
 *
 * Stations come from Radio Browser (radio-browser.info), an open community
 * directory with no API key and no account - which is the whole reason it is
 * usable here, since every commercial radio directory wants a contract before
 * it will tell you a stream URL.
 *
 * A station is not a track. There is no album, no duration, and nothing to
 * queue: it is one endless URI. On a Sonos that means SetAVTransportURI with a
 * broadcast DIDL class rather than the queue dance a Spotify album needs (see
 * {@link Sonos#playRadio}); on the phone it means an actual media player, since
 * unlike Spotify there is no other app to hand the job to.
 */
public final class Radio {

    private static final String TAG = "iohelperRadio";
    /**
     * The load-balanced entry point. Radio Browser asks clients to resolve this
     * name rather than pin one of the numbered mirrors, so a server going down
     * is invisible here.
     */
    private static final String API = "https://all.api.radio-browser.info/json/stations";
    /**
     * Radio Browser asks every client to identify itself, and answers thinly or
     * not at all to callers that do not. It is a volunteer-run directory; this
     * is the price of admission and it costs nothing to pay.
     */
    private static final String UA = "iohelper/1.0 (+RayNeo iO glasses assistant)";
    private static final int TIMEOUT_MS = 12000;

    /**
     * The mark on a radio line, chosen by putting candidates on the lens and
     * looking - the only test that means anything, since the display draws a
     * character its font lacks as an empty box.
     *
     * A CONSTANT rather than a literal because the glyph is load-bearing:
     * Commands and TalkService both decide "radio has started, so the live
     * voice session can hang up" by testing what the answer line STARTS with.
     * When this was a "◉" typed out at six separate sites, changing it would
     * have left those two testing for a character nothing produced any more -
     * the session would have stayed open, microphone live and metered, with
     * nothing to show it. One name, one place to change it.
     */
    public static final String GLYPH = "≈";

    private Radio() {
    }

    /**
     * A station for a spoken request, or null when nothing matches.
     *
     * Returns {name, streamUrl, codec, bitrate, country}. Searched by NAME
     * first, because "play KQED" means that station and nothing else; a genre
     * word like "jazz" or "ambient" usually misses on name and is then tried as
     * a TAG, which is how the directory models genres.
     */
    public static String[] find(Context ctx, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || vague(q)) {
            return null;
        }
        // Spoken requests carry the word "radio" far more often than station
        // names do ("play jazz radio"), and leaving it in pushes real matches
        // down the list. But some stations ARE named for it - Radio Paradise,
        // Radio 4 - so the ORIGINAL wording is tried first and only then the
        // trimmed one. Stripping first would have searched for "paradise".
        String cleaned = q.replaceAll("(?i)\\b(radio station|radio|station|stream|live)\\b", " ")
                .replaceAll("(?i)^\\s*(the|a|an|some)\\b", " ")
                .replaceAll("\\s+", " ").trim();

        String[] hit = search(ctx, "byname", q);
        if (hit == null && !cleaned.isEmpty() && !cleaned.equalsIgnoreCase(q)) {
            hit = search(ctx, "byname", cleaned);
        }
        if (hit == null && !cleaned.isEmpty()) {
            hit = search(ctx, "bytag", cleaned);     // genres live as tags
        }
        return hit;
    }

    /**
     * A BROADCAST CALL SIGN, or null if this word is not confidently one.
     *
     * "play kpfa" names a radio station, but it carries none of the words the
     * RADIO pattern needs, so it fell through to the music path and Spotify
     * fuzzy-matched it to "Kodak Black". Call signs are how people actually ask
     * for the stations they listen to, so the shape has to be recognised
     * without the word "radio" - and recognised CONSERVATIVELY, because a
     * four-letter word is also a band.
     *
     * The rule is deliberately strict, and every part of it was measured
     * against the directory:
     *
     *   kpfa -> "KPFA"                      US   accepted
     *   kexp -> "KEXP 90.3 Seattle, WA"     US   accepted
     *   wnyc -> "WNYC 93.9 FM"              US   accepted
     *   kiss -> "Kiss FM 106.5"             UA   rejected, not US/CA
     *   work -> "Radio 105 Network"         IT   rejected, no prefix match
     *   wolf -> "The WOLF - New Country"    DE   rejected, no prefix match
     *
     * A real call sign leads its station's name; an ordinary word that happens
     * to appear in one turns up in the middle. The country check is what keeps
     * "kiss" - which DOES lead a station name - out, since North American call
     * signs are the only ones shaped like this. Both have to hold, and the
     * caller still falls back to music when they do not, so the cost of a miss
     * is the behaviour that already existed.
     */
    public static String[] callSign(Context ctx, String word) {
        String w = word == null ? "" : word.trim();
        // NAMED STATIONS ARE DECIDED, NOT INFERRED. A word the wearer has
        // listed is a station full stop, so it skips every test below - that
        // is the whole point of the list: it is where a conflict the inference
        // refuses to arbitrate ("kiss" is a station AND a band) gets settled by
        // someone who knows which they meant. The ordinary directory lookup
        // resolves it, the same one an explicit "play X radio" uses.
        if (listed(ctx, w)) {
            String[] hit = find(ctx, w);
            if (hit != null) {
                Log.i(TAG, "listed station " + w + " -> " + hit[0]);
                return hit;
            }
            // Listed but not found: still not a music request. Saying nothing
            // here would hand "kpfa" to Spotify, which is the bug this fixes.
            Log.w(TAG, "listed station " + w + " not in the directory");
            return null;
        }
        if (!w.matches("(?i)[kw][a-z]{3}")) {
            return null;
        }
        String up = w.toUpperCase(Locale.US);
        try {
            String body = get(API + "/byname/" + enc(w)
                    + "?limit=20&hidebroken=true&order=votes&reverse=true");
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "").trim();
                String cc = o.optString("countrycode", "").trim().toUpperCase(Locale.US);
                if (!"US".equals(cc) && !"CA".equals(cc)) {
                    continue;
                }
                String nameUp = name.toUpperCase(Locale.US);
                // Leads the name, and is not merely the start of a longer word:
                // "KISSING" must not pass for "KISS".
                if (!nameUp.startsWith(up)
                        || (nameUp.length() > up.length()
                            && Character.isLetter(nameUp.charAt(up.length())))) {
                    continue;
                }
                String stream = pickUrl(o);
                if (stream == null) {
                    continue;
                }
                Log.i(TAG, "call sign " + up + " -> " + name);
                return new String[]{
                    name, stream, o.optString("codec", "").trim(),
                    String.valueOf(o.optInt("bitrate", 0)), cc,
                };
            }
        } catch (Exception e) {
            Log.w(TAG, "callSign failed: " + e);
        }
        return null;
    }

    /** Whether the wearer has declared this word to be a station. */
    private static boolean listed(Context ctx, String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (String s : Prefs.list(ctx, Prefs.RADIO_CALLSIGNS, "")) {
            if (s.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a request names no station at all - "play the radio", "put the
     * radio on". Worth catching, because the directory will cheerfully answer a
     * search for "the" with a real station, and playing it would be presenting
     * a coin flip as the thing that was asked for.
     */
    public static boolean vague(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.US);
        return q.replaceAll("(?i)\\b(radio station|radio|station|stream|live"
                + "|the|a|an|some|please|on|off|it)\\b", " ")
                .replaceAll("[^a-z0-9]+", " ").trim().isEmpty();
    }

    /**
     * One directory query, already ranked. Ordered by votes because this is a
     * community directory: the popular entry for a name is overwhelmingly the
     * real station, while the tail is full of duplicates and dead mirrors.
     */
    private static String[] search(Context ctx, String mode, String term) {
        try {
            String url = API + "/" + mode + "/" + enc(term)
                    + "?limit=20&hidebroken=true&order=votes&reverse=true";
            String body = get(url);
            JSONArray arr = new JSONArray(body);
            String[] best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String stream = pickUrl(o);
                if (stream == null) {
                    continue;
                }
                int score = score(o, stream, i);
                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{
                        o.optString("name", "Radio").trim(),
                        stream,
                        o.optString("codec", "").trim(),
                        String.valueOf(o.optInt("bitrate", 0)),
                        o.optString("countrycode", "").trim(),
                    };
                }
            }
            if (best != null) {
                Log.i(TAG, "station: " + best[0] + " " + best[2] + " " + best[3] + "kbps");
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, mode + " failed: " + e);
            return null;
        }
    }

    /**
     * The URL to actually play.
     *
     * url_resolved is the directory's own follow-through of playlist files and
     * redirects down to the real stream, so it is preferred: handing a player a
     * .pls and hoping it parses it is the single most common way an otherwise
     * good station fails to start.
     */
    private static String pickUrl(JSONObject o) {
        String resolved = o.optString("url_resolved", "").trim();
        String raw = o.optString("url", "").trim();
        String u = !resolved.isEmpty() ? resolved : raw;
        if (u.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
            return null;
        }
        return u;
    }

    /** Prefer a live, well-supported, reasonably encoded stream. */
    private static int score(JSONObject o, String stream, int rank) {
        int s = -rank;                               // keep the directory's order as the base
        String codec = o.optString("codec", "").toUpperCase(Locale.US);
        if (codec.contains("MP3")) {
            s += 30;                                 // the format everything plays
        } else if (codec.contains("AAC")) {
            s += 20;
        }
        int bitrate = o.optInt("bitrate", 0);
        if (bitrate >= 64 && bitrate <= 320) {
            s += 10;                                 // sane; 0 usually means unknown
        }
        String low = stream.toLowerCase(Locale.US);
        if (low.endsWith(".pls") || low.endsWith(".m3u") || low.endsWith(".m3u8")) {
            s -= 25;                                 // a playlist, not a stream
        }
        if (o.optInt("lastcheckok", 1) == 0) {
            s -= 100;                                // the directory says it is down
        }
        return s;
    }

    // ---- phone playback ----------------------------------------------------
    // Unlike Spotify there is no other app to hand a stream to, so the app owns
    // an actual player here. One at a time, deliberately: a second station
    // replaces the first rather than playing over it.

    private static MediaPlayer player;
    private static volatile String nowPlaying;
    /** Reconnects spent on the current outage; reset once the stream holds. */
    private static volatile int attempts;
    /** When the current stream started playing, or 0 while it is opening. */
    private static volatile long startedAt;
    /**
     * Bumped on every open and stop. A reconnect scheduled for an outage that
     * the wearer has since ended - by stopping, or by starting something else
     * - must not come back and restart a station nobody wants any more.
     */
    private static volatile int generation;
    /** Reopen this many times before giving up on a stream. */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_MS = 2000;
    /** A stream that ran this long before failing gets a fresh retry budget. */
    private static final long HELD_MS = 30_000;
    /** Kept so stopPhone() can hand audio focus back; it takes no Context. */
    private static volatile Context appCtx;

    /**
     * Yield when another app takes the audio.
     *
     * The focus request used to pass a NULL listener, which asks the system
     * for focus while declining to be told when it is lost. Nothing ever
     * stopped this player, so starting a podcast - or Spotify, or a video -
     * left the station streaming UNDERNEATH it, both audible at once. Asking
     * for focus and then ignoring the answer is worse than never asking.
     *
     * A permanent loss stops the stream outright rather than pausing it: this
     * is LIVE radio, so there is no position to come back to, and a paused
     * stream resumes into a stale buffer. A transient one (a navigation prompt,
     * a call) pauses and resumes, and a duckable one just drops the volume.
     */
    private static final AudioManager.OnAudioFocusChangeListener FOCUS =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    MediaPlayer p = player;
                    if (p == null) {
                        return;
                    }
                    try {
                        switch (change) {
                            case AudioManager.AUDIOFOCUS_LOSS:
                                Log.i(TAG, "audio focus lost - stopping " + nowPlaying);
                                // OFF THE MAIN THREAD. A focus callback arrives
                                // there, and reset()/release() on a live network
                                // stream can block long enough to be an ANR -
                                // the stop is not urgent to the millisecond.
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        stopPhone();
                                    }
                                }, "radio-stop").start();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                p.pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                p.setVolume(0.2f, 0.2f);
                                break;
                            case AudioManager.AUDIOFOCUS_GAIN:
                                p.setVolume(1f, 1f);
                                if (!p.isPlaying()) {
                                    p.start();
                                }
                                break;
                            default:
                                break;
                        }
                    } catch (Exception e) {
                        // A player torn down between the check and the call.
                        Log.w(TAG, "focus change " + change + ": " + e);
                    }
                }
            };

    /** What is streaming on the PHONE right now, or null. */
    public static String current() {
        return nowPlaying;
    }

    /**
     * Start a stream on the phone. Returns the line for the glasses.
     *
     * Prepared ASYNCHRONOUSLY: a radio URL is a network connection that can take
     * seconds to open, and preparing it inline would block whichever thread the
     * assistant answered on. The card therefore promises only that it is
     * starting, and an error later corrects it rather than a lie standing.
     */
    public static synchronized String playOnPhone(Context ctx, String url, String name) {
        attempts = 0;
        return open(ctx.getApplicationContext(), url, name);
    }

    /**
     * Open a stream, fresh or as a reconnect.
     *
     * A LIVE STREAM DROPPING ONCE IS ORDINARY - mobile networks hiccup, a
     * station restarts its encoder - and a single MediaPlayer error used to end
     * playback for good, with "would not play" on the lens for a station that
     * had been playing fine for twenty minutes. Measured: "stream error
     * 1/-2147483648", MEDIA_ERROR_UNKNOWN on a KPFA stream that had been up
     * since the request. So an error reopens the same URL, up to MAX_RETRIES
     * times a couple of seconds apart, and only then admits defeat. A stream
     * that held for HELD_MS before failing starts a fresh budget, so an
     * intermittent link keeps recovering all afternoon instead of spending its
     * three chances on the first three blips.
     *
     * Silent on success on purpose: a reconnect the wearer hears as a two
     * second gap needs no card, and one saying "reconnecting" would arrive
     * after the music was already back.
     */
    private static synchronized String open(final Context app, final String url,
                                            final String name) {
        release();
        final int mine = ++generation;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    AudioManager am = app.getSystemService(AudioManager.class);
                    if (am != null) {
                        am.requestAudioFocus(FOCUS, AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN);
                    }
                    m.start();
                    startedAt = System.currentTimeMillis();
                    if (attempts > 0) {
                        Log.i(TAG, "reconnected " + name + " (attempt " + attempts + ")");
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "stream error " + what + "/" + extra);
                    if (mine != generation) {
                        return true;                 // already replaced or stopped
                    }
                    // A stream that held for a while earns a fresh budget: this
                    // is a new outage, not the same one still failing.
                    long ran = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
                    if (ran > HELD_MS) {
                        attempts = 0;
                    }
                    if (attempts < MAX_RETRIES) {
                        final int next = attempts + 1;
                        Log.i(TAG, "reopening " + name + " in " + RETRY_MS + " ms (attempt "
                                + next + "/" + MAX_RETRIES + ", ran " + ran / 1000 + "s)");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(RETRY_MS);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                synchronized (Radio.class) {
                                    if (mine != generation) {
                                        return;      // the wearer moved on meanwhile
                                    }
                                    attempts = next;
                                    open(app, url, name);
                                }
                            }
                        }, "radio-reconnect").start();
                        return true;
                    }
                    // Out of retries: correct the card rather than leave
                    // "playing" standing on the glasses for a dead stream.
                    Cards.post(app, Cards.title(app),
                            GLYPH + " " + Cards.sanitize(name) + " would not play.", "answer");
                    stopPhone();
                    return true;
                }
            });
            player = mp;
            nowPlaying = name;
            startedAt = 0;
            appCtx = app;
            mp.prepareAsync();
            return GLYPH + " " + name;
        } catch (Exception e) {
            stopPhone();
            return "Could not open that stream (" + e + ")";
        }
    }

    /** Stop phone playback. Safe to call when nothing is playing. */
    public static synchronized boolean stopPhone() {
        cancelScan();                              // a stop is a stop, scan included
        return release();
    }

    /** Tear the player down. The scan, if any, is left running - it is what
     *  calls this between stations. */
    private static synchronized boolean release() {
        generation++;                              // any pending reconnect stands down
        boolean was = player != null;
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
                // A player torn down mid-prepare throws; nothing to salvage.
            }
            player = null;
        }
        // Hand focus BACK. Holding it after the stream is gone leaves the
        // system believing this app is still playing, which is how a paused
        // app fails to resume when the thing that interrupted it has finished.
        try {
            Context c = appCtx;
            AudioManager am = c == null ? null : c.getSystemService(AudioManager.class);
            if (am != null) {
                am.abandonAudioFocus(FOCUS);
            }
        } catch (Exception ignored) {
            // Nothing to salvage; the stream is already down.
        }
        nowPlaying = null;
        return was;
    }

    // ---- presets & scan ----------------------------------------------------
    // A few stations the wearer keeps, and a car-radio scan through them:
    // "scan stations" starts at the next one and moves on every dozen seconds
    // until "keep this" or "stop"; "next station" and "previous station" step
    // by hand. Kept as {name, url} resolved at the time they were added, so a
    // scan does not spend a directory lookup per station - and falls back to a
    // fresh lookup if a stored stream has gone stale.

    private static final long SCAN_DWELL_MS = 12_000;
    /** Stations visited on this scan; the first one is held for longer. */
    private static volatile int hops;
    private static volatile int scanIndex = -1;
    private static volatile boolean scanning;
    private static final android.os.Handler SCAN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable scanTick;

    /** The saved stations, oldest first: each is {name, url}. */
    public static java.util.List<String[]> presets(Context ctx) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.str(ctx, Prefs.RADIO_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("name", "").isEmpty()) {
                    out.add(new String[]{o.optString("name"), o.optString("url", "")});
                }
            }
        } catch (Exception ignored) {
            // A hand-edited pref that is not JSON: treat as empty, not fatal.
        }
        return out;
    }

    private static void savePresets(Context ctx, java.util.List<String[]> list) {
        JSONArray a = new JSONArray();
        try {
            for (String[] p : list) {
                a.put(new JSONObject().put("name", p[0]).put("url", p[1]));
            }
        } catch (Exception ignored) {
        }
        Prefs.put(ctx, Prefs.RADIO_PRESETS, a.toString());
    }

    /**
     * Save a station by the name the wearer typed, resolved through the
     * directory now so the scan never has to. Returns what to tell them.
     */
    public static String addPreset(Context ctx, String query) {
        String[] hit = find(ctx, query);
        if (hit == null) {
            return "No station found for \"" + query + "\".";
        }
        java.util.List<String[]> list = presets(ctx);
        for (String[] p : list) {
            if (p[0].equalsIgnoreCase(hit[0])) {
                return hit[0] + " is already saved.";
            }
        }
        list.add(new String[]{hit[0], hit[1]});
        savePresets(ctx, list);
        return "Saved " + hit[0] + ".";
    }

    public static void removePreset(Context ctx, int index) {
        java.util.List<String[]> list = presets(ctx);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            savePresets(ctx, list);
            if (scanIndex >= list.size()) {
                scanIndex = -1;
            }
        }
    }

    /** Start (or continue) scanning: play the next saved station, keep moving. */
    public static String scan(Context ctx) {
        java.util.List<String[]> list = presets(ctx);
        if (list.isEmpty()) {
            return GLYPH + " No stations saved - add a few in the app first.";
        }
        scanning = true;
        hops = 0;
        return tune(ctx, list, +1, true);
    }

    /** One station forward or back. Keeps scanning if a scan was running. */
    public static String step(Context ctx, int dir) {
        java.util.List<String[]> list = presets(ctx);
        if (list.isEmpty()) {
            return GLYPH + " No stations saved - add a few in the app first.";
        }
        return tune(ctx, list, dir, scanning);
    }

    /** Stay on the current station; the scan stops moving. */
    public static String keep(Context ctx) {
        boolean was = scanning;
        cancelScan();
        String on = nowPlaying;
        return on == null ? GLYPH + " Nothing is playing."
                : GLYPH + (was ? " Staying on " : " ") + on;
    }

    public static boolean scanning() {
        return scanning;
    }

    private static synchronized String tune(final Context ctx, java.util.List<String[]> list,
                                            int dir, boolean keepMoving) {
        int n = list.size();
        String[] p = null;
        String url = null;
        // A station that will not resolve is SKIPPED, not the end of the scan:
        // step past it, up to once round the list, before giving up.
        for (int tries = 0; tries < n; tries++) {
            scanIndex = ((scanIndex + dir) % n + n) % n;
            String[] cand = list.get(scanIndex);
            String u = cand[1];
            if (u.isEmpty()) {
                String[] hit = find(ctx, cand[0]);   // saved by name only
                u = hit == null ? "" : hit[1];
            }
            if (!u.isEmpty()) {
                p = cand;
                url = u;
                break;
            }
            Log.w(TAG, "skipping " + cand[0] + ": no stream");
        }
        if (p == null) {
            cancelScan();
            return GLYPH + " None of the saved stations would resolve.";
        }
        String line = open(ctx.getApplicationContext(), url, p[0]);
        if (scanTick != null) {
            SCAN.removeCallbacks(scanTick);
        }
        if (keepMoving) {
            scanning = true;
            scanTick = new Runnable() {
                @Override
                public void run() {
                    if (!scanning) {
                        return;
                    }
                    // OFF THE MAIN THREAD. This Handler fires on it, and the
                    // next station may need a directory lookup - which threw
                    // NetworkOnMainThreadException, was swallowed by search(),
                    // and came back as "KQED is not in the directory any
                    // more" for a station with five entries in it.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            java.util.List<String[]> now = presets(ctx);
                            if (now.isEmpty() || !scanning) {
                                scanning = false;
                                return;
                            }
                            // Say where the scan has got to, so the lens tracks it.
                            Cards.post(ctx.getApplicationContext(), Cards.title(ctx),
                                    tune(ctx, now, +1, true), "media");
                        }
                    }, "radio-scan").start();
                }
            };
            // The FIRST station is held twice as long. Partly because the
            // wearer just asked and deserves a moment to settle on it, and
            // partly because on the crown channel the answer card is deferred
            // behind RayNeo's own reply (see Cards.waitNativeIdle) - so a hop
            // at the normal interval put "2/2: KQED" on the lens BEFORE
            // "1/2: KPFA" arrived, with KQED already playing. Measured.
            SCAN.postDelayed(scanTick, hops++ == 0 ? SCAN_DWELL_MS * 2 : SCAN_DWELL_MS);
        }
        return line.startsWith(GLYPH)
                ? GLYPH + " " + (keepMoving ? "Scanning " : "")
                  + (scanIndex + 1) + "/" + n + ": " + p[0]
                : line;
    }

    private static void cancelScan() {
        scanning = false;
        if (scanTick != null) {
            SCAN.removeCallbacks(scanTick);
            scanTick = null;
        }
    }

    // ---- http --------------------------------------------------------------

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                throw new IllegalStateException("no body (HTTP " + code + ")");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String body = out.toString("UTF-8");
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Path-segment encoding. The station name goes in the PATH here, not a
     * query string, so a space has to be %20 - a "+" would be searched for
     * literally and match nothing.
     */
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
}
