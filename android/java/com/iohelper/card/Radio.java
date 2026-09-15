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
        stopPhone();
        try {
            final Context app = ctx.getApplicationContext();
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
                        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN);
                    }
                    m.start();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "stream error " + what + "/" + extra);
                    // Correct the card rather than leave "playing" standing on
                    // the glasses for a stream that never opened.
                    Cards.post(app, Cards.title(app),
                            "◉ " + Cards.sanitize(nowPlaying == null ? "Station" : nowPlaying)
                            + " would not play.", "answer");
                    stopPhone();
                    return true;
                }
            });
            player = mp;
            nowPlaying = name;
            mp.prepareAsync();
            return "◉ " + name;
        } catch (Exception e) {
            stopPhone();
            return "Could not open that stream (" + e + ")";
        }
    }

    /** Stop phone playback. Safe to call when nothing is playing. */
    public static synchronized boolean stopPhone() {
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
        nowPlaying = null;
        return was;
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
