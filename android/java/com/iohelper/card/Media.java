package com.iohelper.card;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Playing things: transport control, Spotify, and YouTube.
 *
 * THE IMPORTANT PART IS THAT MOST OF THIS NEEDS NO CREDENTIALS. Play, pause,
 * skip and volume are media key events, so they drive whatever currently holds
 * the media session - Spotify, YouTube, a podcast - with nothing configured and
 * nothing to expire. That is the piece that works on day one and keeps working.
 *
 * Credentials only buy "play THIS thing by name":
 *
 *  - Spotify: a client id + secret is enough to SEARCH (Client Credentials).
 *    Playback prefers the Web API (PUT /me/player/play) when a user OAuth token
 *    is configured: it targets the Spotify device directly, so it starts the
 *    right thing without a global media key that could resume some other app.
 *    Opening a spotify: URI registers and cues the app when no device is active
 *    yet; the search-only path (no user token) just opens the URI.
 *  - YouTube: a Data API key resolves a name to a video id, which autoplays.
 *    Without one this falls back to opening a search, which still gets you
 *    there in one tap but will not start on its own.
 *
 * Everything is launched with `am start` over the existing adb link rather than
 * startActivity(): the assistant runs in the background, where Android blocks
 * activity starts outright, and shell UID is not subject to that.
 */
public final class Media {

    private static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final String YT_SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private static final String YT_PKG = "com.google.android.youtube";
    /** Pocket Casts. Cold, it holds no session for a search to land in. */
    private static final String PC_ACTIVITY =
            "au.com.shiftyjelly.pocketcasts/.ui.MainActivity";
    /**
     * The mark on a YouTube line. Filled "▶" means "playing" for everything -
     * Spotify, Sonos, a phone track - so a video was indistinguishable from a
     * song; the hollow triangle still reads as play while saying at a glance
     * that this one is a video. Confirmed rendering on the lens, which is the
     * whole test: a character its font lacks is drawn as an empty box, so a
     * rectangular glyph could never be told apart from a missing one. BMP
     * only, like everything that reaches the glasses (Cards.sanitize drops
     * the rest).
     */
    static final String YT_GLYPH = "▷";

    /** Whether a line reports something actually playing, whatever the source. */
    static boolean playing(String line) {
        return line != null && (line.startsWith("▶") || line.startsWith(YT_GLYPH));
    }

    private static volatile String spotifyToken;
    private static volatile long spotifyTokenExpiry;

    private Media() {
    }

    // ---- transport: no credentials, works with any player -------------------

    /** Media key codes, by the word a person would actually say. */
    private static int keyFor(String action) {
        switch (action) {
            case "play": return 126;
            case "pause": return 127;
            case "toggle": return 85;
            case "next": return 87;
            case "previous": return 88;
            case "stop": return 86;
            case "louder": return 24;
            case "quieter": return 25;
            default: return -1;
        }
    }

    public static String control(Context ctx, String action) {
        int code = keyFor(action);
        if (code < 0) {
            return null;
        }
        // A podcast has no "next track", and Pocket Casts says so: its session
        // advertises neither SKIP_TO_NEXT nor SKIP_TO_PREVIOUS (measured,
        // actions=122703). The media keys for those therefore reached it and
        // did nothing at all, while the card still claimed "⏭ Skipped." What it
        // does offer is FAST_FORWARD and REWIND - the jumps, which is what
        // "skip" means to somebody listening to a podcast anyway. Gated on
        // Pocket Casts OWNING the media keys rather than on it playing: a
        // paused podcast still owns them, and "is it playing" sent skip down
        // the keyevent path to an app that ignores it. When music is on top,
        // "next" is still a real track skip. The jump length is the wearer's
        // own setting in the app, so the card names no number it cannot know.
        if ("next".equals(action) || "previous".equals(action)) {
            MediaController pc = Sessions.ownsMediaKeys(ctx, Sessions.POCKETCASTS)
                    ? Sessions.find(ctx, Sessions.POCKETCASTS) : null;
            if (pc != null
                    && !Sessions.supports(pc, PlaybackState.ACTION_SKIP_TO_NEXT)
                    && Sessions.supports(pc, PlaybackState.ACTION_FAST_FORWARD)) {
                if ("next".equals(action)) {
                    pc.getTransportControls().fastForward();
                    return "⏭ Skipped forward.";
                }
                pc.getTransportControls().rewind();
                return "⏮ Skipped back.";
            }
        }
        try {
            // Volume steps of one are barely audible; a spoken "louder" means more.
            int repeats = (code == 24 || code == 25) ? 3 : 1;
            for (int i = 0; i < repeats; i++) {
                LocalAdb.shell(ctx, "input keyevent " + code, 6000);
            }
        } catch (Exception e) {
            return "Could not reach the player (" + e + ")";
        }
        switch (action) {
            case "next": return "⏭ Skipped.";
            case "previous": return "⏮ Back.";
            case "pause": return "⏸ Paused.";
            case "stop": return "⏹ Stopped.";
            case "louder": return "🔊 Louder.";
            case "quieter": return "🔉 Quieter.";
            default: return "▶ Playing.";
        }
    }

    /** One media session's worth of state. */
    public static final class Track {
        public final String pkg;
        public final String title;
        public final String artist;
        public final boolean playing;

        Track(String pkg, String title, String artist, boolean playing) {
            this.pkg = pkg;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
        }

        /**
         * Identity for "has the track changed", ignoring play/pause flips and
         * the incidental churn session metadata shows while a video plays.
         *
         * The separator used to be a literal NUL byte embedded in the source,
         * which made the whole file read as binary to grep and every other
         * text tool. A unit separator does the same job in a printable file.
         */
        public String key() {
            String t = (title == null ? "" : title) + " \u001f "
                    + (artist == null ? "" : artist);
            return t.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.US);
        }

        public String label() {
            return title + (artist == null || artist.isEmpty() ? "" : " — " + artist);
        }
    }

    /** Every media session the phone is holding, in the order dumpsys lists them. */
    private static java.util.List<Track> sessions(Context ctx) {
        try {
            String out = LocalAdb.shell(ctx, "dumpsys media_session", 10000);
            java.util.List<Track> found = new java.util.ArrayList<>();
            String pkg = null;
            String title = null;
            String artist = null;
            Boolean playing = null;
            for (String raw : out.split("\n")) {
                String s = raw.trim();
                int p = s.indexOf("package=");
                if (p >= 0) {
                    if (title != null) {
                        found.add(new Track(pkg, title, artist, Boolean.TRUE.equals(playing)));
                    }
                    pkg = s.substring(p + 8).split("\\s")[0];
                    title = null;
                    artist = null;
                    playing = null;
                    continue;
                }
                if (s.contains("state=PlaybackState {state=")) {
                    playing = s.contains("state=PLAYING(") || s.contains("state=BUFFERING(");
                }
                if (s.startsWith("metadata:") && s.contains("description=")) {
                    String d = s.substring(s.indexOf("description=") + 12).trim();
                    String[] parts = d.split(",");
                    String t = parts.length > 0 ? parts[0].trim() : "";
                    if (!t.isEmpty() && !"null".equals(t)) {
                        title = t;
                        if (parts.length >= 2 && !"null".equals(parts[1].trim())) {
                            artist = parts[1].trim();
                        }
                    }
                }
            }
            if (title != null) {
                found.add(new Track(pkg, title, artist, Boolean.TRUE.equals(playing)));
            }
            return found;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * What is playing right now, across every media session.
     *
     * Deliberately prefers the session that is actually PLAYING rather than the
     * first one listed: several apps hold sessions at once here (a music app, a
     * podcast, a browser), and taking the first meant answering with a paused
     * podcast while music played.
     */
    public static Track current(Context ctx) {
        java.util.List<Track> found = sessions(ctx);
        for (Track t : found) {
            if (t.playing) {
                return t;
            }
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The one-line answer to "what song is this". A title and artist is what the
     * display can actually hold, and it is the useful part.
     */
    public static String nowPlaying(Context ctx) {
        Track t = current(ctx);
        if (t == null) {
            return "♪ Nothing is playing.";
        }
        return (t.playing ? "♪ " : "⏸ ") + t.label();
    }

    // ---- play a named thing -------------------------------------------------

    public static boolean spotifyConfigured(Context ctx) {
        return !Prefs.str(ctx, Prefs.SPOTIFY_ID, "").isEmpty()
                && !Prefs.str(ctx, Prefs.SPOTIFY_SECRET, "").isEmpty();
    }

    private static volatile String userToken;
    private static volatile long userTokenExpiry;

    /**
     * A token for THE USER, not just the app - the only kind that can command a
     * player. Minted from the refresh token saved by spotify-auth.py.
     */
    private static String userToken(Context ctx) throws Exception {
        if (userToken != null && System.currentTimeMillis() < userTokenExpiry) {
            return userToken;
        }
        String refresh = Prefs.str(ctx, Prefs.SPOTIFY_REFRESH, "");
        if (refresh.isEmpty()) {
            return null;
        }
        String basic = Base64.encodeToString(
                (Prefs.str(ctx, Prefs.SPOTIFY_ID, "") + ":"
                        + Prefs.str(ctx, Prefs.SPOTIFY_SECRET, "")).getBytes("UTF-8"),
                Base64.NO_WRAP);
        String resp = http(SPOTIFY_TOKEN_URL, "POST",
                "grant_type=refresh_token&refresh_token=" + enc(refresh),
                "application/x-www-form-urlencoded", "Basic " + basic, 15000);
        JSONObject o = new JSONObject(resp);
        userToken = o.getString("access_token");
        userTokenExpiry = System.currentTimeMillis()
                + Math.max(60, o.optInt("expires_in", 3600) - 60) * 1000L;
        return userToken;
    }

    /**
     * Start something on the phone through the Web API, precisely.
     *
     * This exists to replace "open the URI, wait, then press the PLAY media key".
     * That sequence had a race with no winner: press too early and the key
     * resumes WHATEVER WAS PAUSED BEFORE, while the requested track loads behind
     * it - so the wrong song plays and the right one is queued but silent. There
     * is no delay that fixes it, because the load time is not knowable. Naming
     * the URI to the player removes the guess entirely.
     *
     * @param contextUri true for an album or playlist, false for a single track.
     * @return null when there is no user token or no device, so the caller can
     *         fall back rather than fail.
     */
    /** {userToken, deviceId} for a commandable phone device, or null. */
    private static String[] activeDeviceId(Context ctx) {
        try {
            String token = userToken(ctx);
            if (token == null) {
                return null;
            }
            String devices = http("https://api.spotify.com/v1/me/player/devices",
                    "GET", null, null, "Bearer " + token, 15000);
            JSONArray list = new JSONObject(devices).optJSONArray("devices");
            String deviceId = null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                if (d.optBoolean("is_restricted", false) || d.isNull("id")) {
                    continue;                       // a Sonos cannot be commanded
                }
                boolean phone = "Smartphone".equalsIgnoreCase(d.optString("type"));
                if (d.optBoolean("is_active", false) || (deviceId == null && phone)) {
                    deviceId = d.getString("id");
                    if (d.optBoolean("is_active", false)) {
                        break;                      // whatever is already playing
                    }
                }
            }
            return deviceId == null ? null : new String[]{token, deviceId};
        } catch (Exception e) {
            android.util.Log.i("iohelperMedia", "activeDeviceId failed: " + e);
            return null;
        }
    }

    private static String playViaApi(Context ctx, String uri, boolean contextUri) {
        String[] dev = activeDeviceId(ctx);
        if (dev == null) {
            return null;
        }
        try {
            String body = contextUri
                    ? "{\"context_uri\":\"" + uri + "\"}"
                    : "{\"uris\":[\"" + uri + "\"]}";
            http("https://api.spotify.com/v1/me/player/play?device_id=" + enc(dev[1]),
                    "PUT", body, "application/json", "Bearer " + dev[0], 15000);
            return "ok";
        } catch (Exception e) {
            android.util.Log.i("iohelperMedia", "playViaApi failed: " + e);
            return null;
        }
    }

    /** Client Credentials: enough to search, which is all that is needed here. */
    private static String spotifyToken(Context ctx) throws Exception {
        if (spotifyToken != null && System.currentTimeMillis() < spotifyTokenExpiry) {
            return spotifyToken;
        }
        String id = Prefs.str(ctx, Prefs.SPOTIFY_ID, "");
        String secret = Prefs.str(ctx, Prefs.SPOTIFY_SECRET, "");
        String basic = Base64.encodeToString((id + ":" + secret).getBytes("UTF-8"),
                Base64.NO_WRAP);
        String resp = http(SPOTIFY_TOKEN_URL, "POST", "grant_type=client_credentials",
                "application/x-www-form-urlencoded", "Basic " + basic, 15000);
        JSONObject o = new JSONObject(resp);
        spotifyToken = o.getString("access_token");
        spotifyTokenExpiry = System.currentTimeMillis()
                + Math.max(60, o.optInt("expires_in", 3600) - 60) * 1000L;
        return spotifyToken;
    }

    /**
     * Resolve a spoken name to one Spotify track: {id, "Title — Artist"}.
     *
     * Split out from spotify() because a Sonos needs the bare track id to build
     * its own URI, while the phone path only needs something to open.
     */
    /** Filler people say around the thing they actually want. */
    private static String cleanQuery(String q) {
        return q.replaceAll("(?i)^\\s*(?:the\\s+)?(?:artist|band|group|singer|song|track|album)\\b[,:]?\\s*", "")
                .replaceAll("(?i)^\\s*(?:some|a little|a bit of|anything by|music by|songs? by|stuff by)\\b\\s*", "")
                .replaceAll("(?i)^\\s*(?:i want to hear|i wanna hear|let'?s hear)\\b\\s*", "")
                .replaceAll("[\\s,.!?]+$", "")
                .trim();
    }

    /** Karaoke, tribute and "in the style of" records that impersonate the real thing. */
    private static final String IMPOSTOR =
            "(?i).*\\b(karaoke|tribute|originally performed|made famous by|in the style of"
            + "|cover version|instrumental version|backing track|playback)\\b.*";

    /**
     * A song's identity, ignoring which RELEASE it came from.
     *
     * norm() alone was not enough: it keeps every word, so "Safety Dance" and
     * "The Safety Dance - Remastered 2011" are different strings and both
     * survived the de-duplication - the queue then played the same song twice
     * in a row off a single and an album. Spotify's catalogue carries a
     * remaster, a live cut, a radio edit and a deluxe reissue of anything
     * popular, and a field-scoped artist search returns all of them.
     *
     * Only variant markers are stripped. A parenthetical that is part of the
     * actual name - "(Don't Fear) The Reaper", "Sunday Bloody Sunday" - has no
     * marker word in it and is left alone.
     */
    private static final String VARIANT =
            "remaster(?:ed)?|re-?master|live|version|edit|mix|mono|stereo|deluxe"
            + "|anniversary|bonus|demo|re-?recorded|radio|single|album|acoustic"
            + "|instrumental|reissue|remix|take \\d+|\\d{4} remaster";

    static String songKey(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.US);
        t = t.replaceAll("\\([^)]*\\b(?:" + VARIANT + ")\\b[^)]*\\)", " ");
        t = t.replaceAll("\\[[^\\]]*\\b(?:" + VARIANT + ")\\b[^\\]]*\\]", " ");
        // the " - Remastered 2011" form, which is how Spotify usually spells it
        t = t.replaceAll("\\s[-\u2013\u2014]\\s[^-\u2013\u2014]*\\b(?:" + VARIANT + ")\\b.*$", " ");
        t = t.replaceAll("\\b(?:feat\\.?|featuring|with)\\b.*$", " ");
        return norm(t);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US)
                .replaceAll("^the\\s+", "").replaceAll("[^a-z0-9]", "");
    }

    /**
     * Resolve a spoken request to one track: {id, uri, "Title — Artist"}.
     *
     * Naming an ARTIST and taking the top track search result is how "play the
     * artist, men without hats" became a karaoke record: Spotify ranks tracks by
     * popularity across everyone who recorded them, and impersonation acts rank
     * well. So an artist is resolved as an artist first, and their own top track
     * is used. Only a request that is not a known artist falls back to a track
     * search, and even then obvious impostor records are skipped.
     */
    /**
     * Resolve an album or a playlist: {id, uri, "Name — Owner", kind}.
     *
     * Kept separate from track resolution because the Sonos needs to know WHICH
     * kind it is - a container and a single track use different URI prefixes and
     * different DIDL classes, and getting that wrong is silently rejected.
     */
    /** An artist whose name IS the query, or null. {id, name} */
    public static String[] artistMatch(Context ctx, String query) {
        if (!spotifyConfigured(ctx)) {
            return null;
        }
        String q = cleanQuery(query);
        if (q.isEmpty()) {
            return null;
        }
        try {
            String resp = http(SPOTIFY_SEARCH_URL + "?q=" + enc(q) + "&type=artist&limit=5",
                    "GET", null, null, "Bearer " + spotifyToken(ctx), 15000);
            JSONArray items = new JSONObject(resp).getJSONObject("artists").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject a = items.optJSONObject(i);
                if (a != null && norm(a.optString("name")).equals(norm(q))) {
                    return new String[]{a.getString("id"), a.optString("name")};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * An artist's own recordings, as track ids - enough of them to be a set,
     * not a single song.
     *
     * A field-scoped track search, de-duplicated by song. It is the LAST resort
     * for "play <artist>": both id-based catalogue endpoints - top-tracks AND
     * /artists/{id}/albums - answer 403 to this app's tokens (user and
     * client-credential alike, measured), so search is all there is, and for a
     * hit-heavy artist it collapses to almost nothing once variants are removed
     * ("Men Without Hats" -> two). The caller tries the artist's "This Is"
     * playlist, then an album, before falling back here.
     *
     * @param artistId unused now (the id-based endpoints 403); kept so the
     *                 caller need not change if a catalogue-capable token is
     *                 ever configured.
     */
    public static String[] artistTopTracks(Context ctx, String artistId,
                                           String artistName, int max) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        if (ids.size() < max) {
            try {
                // %20, not "+". A quoted field filter takes a literal plus sign,
                // so artist:"Men+Without+Hats" matches nothing.
                String q = enc("artist:\"" + artistName + "\"").replace("+", "%20");
                // limit=10 and no higher: these credentials 400 on 20 or 50.
                String resp = http(SPOTIFY_SEARCH_URL + "?q=" + q + "&type=track&limit=10",
                        "GET", null, null, "Bearer " + spotifyToken(ctx), 15000);
                JSONArray items = new JSONObject(resp).getJSONObject("tracks")
                        .getJSONArray("items");
                for (int i = 0; i < items.length() && ids.size() < max; i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    JSONArray as = t.optJSONArray("artists");
                    String by = as != null && as.length() > 0
                            ? as.getJSONObject(0).optString("name", "") : "";
                    if (!norm(by).equals(norm(artistName))) {
                        continue;               // a featured credit is not theirs
                    }
                    if (!seen.add(songKey(t.optString("name")))) {
                        continue;               // variant of one already taken
                    }
                    String id = t.getString("id");
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            } catch (Exception e) {
                android.util.Log.i("iohelperMedia", "field search failed: " + e);
            }
        }
        return ids.isEmpty() ? null : ids.toArray(new String[0]);
    }

    /**
     * Turn a curator plan {tracks:[{artist,title}, ...]} into VERIFIED Spotify
     * track ids + uris, or null. Each named track must exist on /search
     * (findTrack, which also checks the credited artist), so the model can name
     * anything but only real, correctly-credited songs are queued. An empty plan
     * - the model's signal that the request actually named a specific
     * artist/song, not a category - returns null so the caller falls through to
     * the literal path.
     *
     * @return String[][]{ids, uris}, or null.
     */
    /**
     * Turn a curator plan {tracks:[{artist,title}, ...]} into VERIFIED Spotify
     * track ids + uris, or null. Each named track must exist on /search
     * (findTrack, which also checks the credited artist), so the model can name
     * anything but only real, correctly-credited songs are queued. An empty plan
     * - the model's signal that the request named a specific artist/song, not a
     * category - returns null so the caller falls back to a genre playlist.
     *
     * @return String[][]{ids, uris}, or null.
     */
    public static String[][] verifyQueue(Context ctx, org.json.JSONObject plan, int max) {
        if (plan == null) {
            return null;
        }
        JSONArray tr = plan.optJSONArray("tracks");
        if (tr == null || tr.length() == 0) {
            return null;
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> uris = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < tr.length() && ids.size() < max; i++) {
            JSONObject t = tr.optJSONObject(i);
            if (t == null) {
                continue;
            }
            String[] hit = findTrack(ctx, t.optString("artist", "").trim(),
                    t.optString("title", "").trim());
            if (hit == null || !seen.add(hit[0])) {
                continue;                           // unverifiable, or a duplicate
            }
            ids.add(hit[0]);
            uris.add(hit[1]);
        }
        if (ids.isEmpty()) {
            return null;
        }
        return new String[][]{ids.toArray(new String[0]), uris.toArray(new String[0])};
    }

    /**
     * Play a LIST of already-resolved track uris on the phone (the curated-set
     * counterpart to Sonos.playTracks). Web API multi-uri when a phone device is
     * reachable, else opens the first track so something plays.
     */
    public static String playTracks(Context ctx, String[] uris, String label, boolean shuffle) {
        if (uris == null || uris.length == 0) {
            return "\u266a Nothing to play for " + label + ".";
        }
        String line = "\u25b6 " + label + " \u2014 " + uris.length + " tracks"
                + (shuffle ? ", shuffled" : "");
        if (playUrisApi(ctx, uris) != null) {
            return line;
        }
        // No Spotify device yet: open the app so it registers, then drive it via
        // the Web API - never a global media key (it resumes whatever app last
        // held the media session, e.g. a paused YouTube, playing the wrong thing
        // and still looking like success).
        open(ctx, uris[0], line);
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (playUrisApi(ctx, uris) != null) {
                return line;
            }
        }
        return "\u266a Opened Spotify \u2014 press play if it didn't start.";
    }

    /** PUT a set of track uris to the active phone device, or null on failure. */
    private static String playUrisApi(Context ctx, String[] uris) {
        String[] dev = activeDeviceId(ctx);
        if (dev == null) {
            return null;
        }
        try {
            StringBuilder arr = new StringBuilder();
            for (String u : uris) {
                if (arr.length() > 0) {
                    arr.append(",");
                }
                arr.append("\"").append(u).append("\"");
            }
            http("https://api.spotify.com/v1/me/player/play?device_id=" + enc(dev[1]),
                    "PUT", "{\"uris\":[" + arr + "]}", "application/json",
                    "Bearer " + dev[0], 15000);
            return "ok";
        } catch (Exception e) {
            android.util.Log.i("iohelperMedia", "playUrisApi failed: " + e);
            return null;
        }
    }

    /**
     * Find the artist's named ALBUM on Spotify, VERIFIED, or null.
     *
     * This closes the hallucination seam for descriptive requests: the LLM only
     * NAMES an album (see Llm.resolveMusic); nothing plays until /search returns
     * a real album whose title AND credited artist both match. Uses only
     * /search (the id-based catalogue endpoints 403 for this token) with the
     * proven field-filter form, and songKey() so a "(Deluxe Edition)" or
     * "- Remaster" suffix still matches the plain title.
     */
    public static String[] findAlbum(Context ctx, String artist, String title) {
        return findRelease(ctx, artist, title, "album");
    }

    /** Find the artist's named TRACK on Spotify, VERIFIED, or null. */
    public static String[] findTrack(Context ctx, String artist, String title) {
        return findRelease(ctx, artist, title, "track");
    }

    private static String[] findRelease(Context ctx, String artist, String title, String kind) {
        if (!spotifyConfigured(ctx) || title == null || title.trim().isEmpty()) {
            return null;
        }
        String field = kind + ":\"" + title + "\""
                + (artist == null || artist.isEmpty() ? "" : " artist:\"" + artist + "\"");
        // %20 not "+": a quoted field filter takes a literal plus otherwise.
        String[] hit = searchRelease(ctx, enc(field).replace("+", "%20"), artist, title, kind);
        if (hit != null) {
            return hit;
        }
        // The quoted filter is exact to a fault: punctuation inside it can
        // return NOTHING at all. "Who's Afraid of the Art of Noise?" found
        // nothing because the catalogue spells it with an exclamation mark, so
        // a correctly named album was reported as missing. A plain-text search
        // tolerates that, and every candidate still goes through the same
        // title-and-artist check below - nothing unverified can play.
        String plain = (title + (artist == null || artist.isEmpty() ? "" : " " + artist)).trim();
        return searchRelease(ctx, enc(plain).replace("+", "%20"), artist, title, kind);
    }

    /** One search, with every candidate verified against the wanted title and artist. */
    private static String[] searchRelease(Context ctx, String q, String artist, String title,
                                          String kind) {
        try {
            String resp = http(SPOTIFY_SEARCH_URL + "?q=" + q + "&type=" + kind + "&limit=10",
                    "GET", null, null, "Bearer " + spotifyToken(ctx), 15000);
            JSONArray items = new JSONObject(resp).getJSONObject(kind + "s").getJSONArray("items");
            String wantTitle = songKey(title);
            String wantArtist = norm(artist);
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;                       // Spotify returns nulls here
                }
                String name = o.optString("name", "");
                JSONArray as = o.optJSONArray("artists");
                String by = as != null && as.length() > 0
                        ? as.getJSONObject(0).optString("name", "") : "";
                if ((name + " " + by).matches(IMPOSTOR)) {
                    continue;                       // karaoke/tribute is not the real thing
                }
                boolean titleOk = songKey(name).equals(wantTitle)
                        || norm(name).equals(norm(title));
                boolean artistOk = wantArtist.isEmpty()
                        || norm(by).equals(wantArtist)
                        || norm(by).contains(wantArtist)
                        || wantArtist.contains(norm(by));
                if (titleOk && artistOk) {
                    return new String[]{o.getString("id"), o.getString("uri"),
                            name + (by.isEmpty() ? "" : " \u2014 " + by), kind};
                }
            }
        } catch (Exception e) {
            android.util.Log.i("iohelperMedia", "findRelease(" + kind + ") failed: " + e);
        }
        return null;                                // named but not on this catalogue: honest miss
    }

    /**
     * Play an ALREADY-RESOLVED Spotify uri on the phone (never re-searching -
     * re-searching would reopen the hole the verification just closed). The Web
     * API targets the Spotify device directly; if none is registered yet, open
     * the app so it becomes one, then drive it through the Web API - never a
     * global media key (that resumes whatever app last held the media session).
     */
    public static String playUri(Context ctx, String uri, boolean container,
                                 String label, boolean shuffle) {
        String line = "\u25b6 " + label + (shuffle ? " (shuffle)" : "");
        if (playViaApi(ctx, uri, container) != null) {
            return line;
        }
        open(ctx, uri, line);
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (playViaApi(ctx, uri, container) != null) {
                return line;
            }
        }
        return "\u266a Opened Spotify to " + label + " \u2014 press play if it didn't start.";
    }

    /**
     * A verified full-catalogue playlist for an artist, or null.
     *
     * The ideal is Spotify's own "This Is <artist>", but Spotify NULLS its
     * editorial playlists out of search results for an app in development mode
     * (measured on this token: 10 hits, 6 null, none Spotify-owned), so it is
     * rarely reachable. The next-best VERIFIED source is the artist's OWN
     * playlist - e.g. "The Complete Men Without Hats", owned by the account
     * "Men Without Hats" itself - a far fuller set than the two songs a track
     * search can surface. A stranger's same-named playlist is still refused: it
     * is a guess at the catalogue, not the artist's music. The contents are
     * never fetched (that 403s for this app); Sonos plays the playlist by URI on
     * its OWN account, so only the id/uri are needed.
     */
    public static String[] artistPlaylist(Context ctx, String artistName) {
        if (!spotifyConfigured(ctx) || artistName == null || artistName.trim().isEmpty()) {
            return null;
        }
        String want = norm("this is " + artistName);
        try {
            String resp = http(SPOTIFY_SEARCH_URL + "?q="
                            + enc("This Is " + artistName) + "&type=playlist&limit=10",
                    "GET", null, null, "Bearer " + spotifyToken(ctx), 15000);
            JSONArray items = new JSONObject(resp).getJSONObject("playlists")
                    .getJSONArray("items");
            String artist = norm(artistName);
            String[] editorial = null, ownNamed = null, own = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;                      // Spotify nulls editorial playlists here
                }
                String name = o.optString("name", "");
                JSONObject owner = o.optJSONObject("owner");
                String by = owner == null ? "" : owner.optString("display_name", "");
                String oid = owner == null ? "" : owner.optString("id", "");
                String[] hit = new String[]{o.getString("id"), o.getString("uri"),
                        name + (by.isEmpty() ? "" : " \u2014 " + by), "playlist"};
                boolean spotifyOwned = "spotify".equalsIgnoreCase(by)
                        || "spotify".equalsIgnoreCase(oid);
                boolean artistOwned = !artist.isEmpty() && norm(by).equals(artist);
                if (spotifyOwned && norm(name).equals(want)) {
                    return hit;                    // the canonical "This Is <artist>"
                }
                if (editorial == null && spotifyOwned && norm(name).startsWith("thisis")
                        && norm(name).contains(artist)) {
                    editorial = hit;               // a Spotify-owned "This Is <artist> ..." variant
                }
                if (artistOwned) {
                    if (ownNamed == null && norm(name).contains(artist)) {
                        ownNamed = hit;            // the artist's own, and it names them
                    }
                    if (own == null) {
                        own = hit;                 // the artist's own, first seen
                    }
                }
            }
            String[] chosen = editorial != null ? editorial
                    : ownNamed != null ? ownNamed : own;
            if (chosen != null) {
                android.util.Log.i("iohelperMedia", "artist playlist for " + artistName
                        + ": " + chosen[2]);
                return chosen;
            }
            android.util.Log.i("iohelperMedia", "no editorial/own playlist for "
                    + artistName + " (" + items.length() + " hits)");
        } catch (Exception e) {
            android.util.Log.i("iohelperMedia", "This Is lookup failed: " + e);
        }
        return null;
    }

    /** Do the query's words actually appear in the name being offered? */
    private static boolean relevant(String query, String name) {
        String[] words = norm(query).isEmpty() ? new String[0]
                : query.toLowerCase(Locale.US).split("[^a-z0-9]+");
        String hay = name.toLowerCase(Locale.US);
        int hits = 0;
        int meaningful = 0;
        for (String w : words) {
            if (w.length() < 3) {
                continue;
            }
            meaningful++;
            if (hay.contains(w)) {
                hits++;
            }
        }
        // A SHORT query has no room for a miss. "Wolfgang Amadeus Mozart"
        // shares two of its three words with the album "Wolfgang Amadeus
        // Phoenix", and no artist is claimed for a bare name, so the majority
        // rule below cheerfully played Phoenix on the speaker. The one word
        // that was missing was the only one that identified anybody. With three
        // words or fewer every one of them has to be present; the tolerance
        // exists for longer titles, where a dropped article or a missing
        // subtitle word is normal.
        if (meaningful <= 3) {
            return hits == meaningful;
        }
        return hits * 2 >= meaningful;
    }

    public static String[] searchContainer(Context ctx, String query, String kind) {
        if (!spotifyConfigured(ctx)) {
            return null;
        }
        String q = cleanQuery(query);
        if (q.isEmpty()) {
            return null;
        }
        // A PLAYLIST named after a band is not that band's music. Spotify does
        // not verify that a user playlist's contents match its title, so asking
        // for "the playlist Men Without Hats" found a stranger's playlist with
        // that name and played whatever was inside it. If the words name an
        // artist, the artist wins and this refuses.
        if ("playlist".equals(kind) && artistMatch(ctx, q) != null) {
            return null;
        }
        // Whose record the words asked for, when they said. The relevance test
        // below only compares the words against the TITLE, which is how
        // "acdc's 2nd most popular album" came back with an album called "Most
        // Popular Nursery Rhymes" - two of the words were in that title and
        // nothing ever asked who made it. Null for a plain title, so a literal
        // search is unaffected (see Commands.artistHint).
        // Only an ALBUM carries an artist credit: for a playlist the `by`
        // below is the OWNER's display name ("Spotify", a stranger's handle),
        // which no artist name will ever match, so claiming one here would
        // reject every playlist there is.
        String want = "album".equals(kind) ? Commands.artistHint(query) : null;
        try {
            String token = spotifyToken(ctx);
            String resp = http(SPOTIFY_SEARCH_URL + "?q=" + enc(q)
                            + "&type=" + kind + "&limit=10", "GET", null, null,
                    "Bearer " + token, 15000);
            JSONArray items = new JSONObject(resp).getJSONObject(kind + "s")
                    .getJSONArray("items");
            String[] fallback = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;                      // Spotify returns nulls here
                }
                String name = o.optString("name", q);
                String by;
                boolean curated = false;
                if ("album".equals(kind)) {
                    JSONArray as = o.optJSONArray("artists");
                    by = as != null && as.length() > 0
                            ? as.getJSONObject(0).optString("name", "") : "";
                } else {
                    JSONObject owner = o.optJSONObject("owner");
                    by = owner == null ? "" : owner.optString("display_name", "");
                    curated = "spotify".equalsIgnoreCase(by);
                }
                if ((name + " " + by).matches(IMPOSTOR) || !relevant(q, name)
                        || !Commands.artistIs(want, by)) {
                    continue;
                }
                String[] hit = new String[]{o.getString("id"), o.getString("uri"),
                        name + (by.isEmpty() ? "" : " — " + by), kind};
                if (curated) {
                    return hit;                    // Spotify's own beats a stranger's
                }
                if (fallback == null) {
                    fallback = hit;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    public static String[] searchTrack(Context ctx, String query) {
        if (!spotifyConfigured(ctx)) {
            return null;
        }
        String q = cleanQuery(query);
        if (q.isEmpty()) {
            return null;
        }
        try {
            String token = spotifyToken(ctx);

            // 1. Is this the name of an artist? If so, play THEIR music.
            String ar = http(SPOTIFY_SEARCH_URL + "?q=" + enc(q) + "&type=artist&limit=3",
                    "GET", null, null, "Bearer " + token, 15000);
            JSONArray artists = new JSONObject(ar).getJSONObject("artists").getJSONArray("items");
            for (int i = 0; i < artists.length(); i++) {
                JSONObject a = artists.getJSONObject(i);
                if (!norm(a.optString("name")).equals(norm(q))) {
                    continue;                       // a loose match is not a name
                }
                try {
                    String top = http("https://api.spotify.com/v1/artists/"
                                    + a.getString("id") + "/top-tracks?market=US",
                            "GET", null, null, "Bearer " + token, 15000);
                    JSONArray tracks = new JSONObject(top).optJSONArray("tracks");
                    if (tracks != null && tracks.length() > 0) {
                        JSONObject t = tracks.getJSONObject(0);
                        return new String[]{t.getString("id"), t.getString("uri"),
                                t.optString("name", q) + " — " + a.optString("name")};
                    }
                } catch (Exception topFail) {
                    // /top-tracks 403s for this app's token. Don't let that abort
                    // the whole search (it turned "play Chuck Mangione" into
                    // "Nothing on Spotify"); fall through to the track search,
                    // which ranks the artist's own songs first for a name query.
                    android.util.Log.i("iohelperMedia",
                            "top-tracks unavailable, using track search: " + topFail);
                }
                break;                              // artist matched; track search handles it
            }

            // 2. Otherwise a song or album title: take the best NON-impostor track.
            String resp = http(SPOTIFY_SEARCH_URL + "?q=" + enc(q) + "&type=track&limit=10",
                    "GET", null, null, "Bearer " + token, 15000);
            JSONArray items = new JSONObject(resp).getJSONObject("tracks").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject t = items.getJSONObject(i);
                JSONArray as = t.optJSONArray("artists");
                String artist = as != null && as.length() > 0
                        ? as.getJSONObject(0).optString("name", "") : "";
                String name = t.optString("name", q);
                if ((name + " " + artist).matches(IMPOSTOR)) {
                    continue;
                }
                return new String[]{t.getString("id"), t.getString("uri"),
                        name + (artist.isEmpty() ? "" : " — " + artist)};
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String spotify(Context ctx, String query) {
        if (!spotifyConfigured(ctx)) {
            // Still useful without keys: hand the query to the app's own search.
            return open(ctx, "spotify:search:" + enc(query), "♪ Searching Spotify: " + query);
        }
        return spotify(ctx, query, null, false);
    }

    /**
     * @param kind "album", "playlist", or null to auto-resolve as artist/track.
     * @param shuffle only meaningful on a container; the phone player keeps
     *                whatever shuffle state it already had, which is why a
     *                shuffle request says so rather than silently ignoring it.
     */
    public static String spotify(Context ctx, String query, String kind, boolean shuffle) {
        if (!spotifyConfigured(ctx)) {
            return open(ctx, "spotify:search:" + enc(query), "♪ Searching Spotify: " + query);
        }
        // One resolver for both destinations: the phone and the Sonos must not
        // disagree about what "play X" means.
        String[] hit = kind == null ? searchTrack(ctx, query)
                                    : searchContainer(ctx, query, kind);
        if (hit == null) {
            return "♪ Nothing on Spotify for " + query + ".";
        }
        String label = "▶ " + hit[2] + (shuffle ? " (shuffle)" : "");
        // The Web API targets the Spotify device DIRECTLY - it starts this exact
        // track/album and cannot wake another app.
        if (playViaApi(ctx, hit[1], kind != null) != null) {
            android.util.Log.i("iohelperMedia", "played via web api: " + hit[1]);
            return label;
        }
        // No Spotify device is registered yet. Open the app so it becomes one,
        // then drive it through the Web API. The old path pressed a GLOBAL media
        // key (KEYCODE_MEDIA_PLAY) here, which Android routes to whatever app
        // last held the media session - so "play X" resumed a paused YouTube and
        // still claimed success. Never do that: confirm only a real Spotify play,
        // and say so honestly when it can't be confirmed.
        open(ctx, hit[1], label);                  // cue Spotify (Web API drives playback)
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1500);                 // let the app register as a device
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (playViaApi(ctx, hit[1], kind != null) != null) {
                android.util.Log.i("iohelperMedia", "played via web api after open: " + hit[1]);
                return label;
            }
        }
        android.util.Log.i("iohelperMedia", "opened Spotify but could not confirm playback");
        return "♪ Opened Spotify to " + hit[2] + " — press play if it didn't start.";
    }

    /**
     * Start Google Maps turn-by-turn navigation to dest, in the given mode
     * ("walk"/"drive"/"bike"/"transit"). Launches Maps over the adb link (the
     * same am-start path as open()); NavListener then relays the live steps to
     * the glasses. Maps uses the phone's current location as the origin.
     *
     * google.navigation: takes single-letter modes d/w/b; transit is not one of
     * them, so a transit request opens the Maps directions URL in navigate mode.
     */
    public static String navigate(Context ctx, String dest, String mode) {
        if (dest == null || dest.trim().isEmpty()) {
            return "⌖ Where to? Say \"navigate to <place>\".";
        }
        String uri;
        if ("transit".equals(mode)) {
            uri = "https://www.google.com/maps/dir/?api=1&destination=" + enc(dest)
                    + "&travelmode=transit&dir_action=navigate";
        } else {
            String m = "walk".equals(mode) ? "w" : "bike".equals(mode) ? "b" : "d";
            uri = "google.navigation:q=" + enc(dest) + "&mode=" + m;
        }
        String label = "⌖ Navigating to " + dest
                + ("drive".equals(mode) ? "" : " (" + mode + ")");
        // MAPS WILL NOT START GUIDANCE BEHIND THE LOCK SCREEN. Measured on
        // device, screen off and screen on alike: the intent is delivered, Maps
        // opens and becomes the resumed activity, and the route never starts -
        // no maneuver is ever posted, so nothing reaches the lens either. This
        // method reported success anyway, because it returned as soon as the
        // shell call had not thrown and never looked at what happened next.
        // That is the one thing this assistant may not do: claim it acted when
        // it did not.
        //
        // The keyguard is the whole barrier - measured, with it out of the way
        // the identical intent starts guidance at once and the turns reach the
        // lens. This does NOT take it out of the way: it lights the screen so
        // the loaded route is one unlock from starting, instead of a dark phone
        // and silence, and says plainly that the unlock is what is missing.
        //
        // Why the side button looks exempt: that path starts the voice SERVICE,
        // audio only, which needs no window and so meets no keyguard at all.
        // Navigation needs a running activity, which is exactly what is blocked.
        boolean locked = false;
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            locked = km != null && km.isKeyguardLocked();
        } catch (Exception ignored) {
            // Cannot tell - say the optimistic thing rather than a wrong warning.
        }
        if (locked) {
            if (Prefs.bool(ctx, Prefs.NAV_WAKE, true)) {
                try {
                    LocalAdb.shell(ctx, "input keyevent 224", 6000);   // KEYCODE_WAKEUP
                } catch (Exception e) {
                    android.util.Log.i("iohelperMedia", "wake: " + e);
                }
            }
            label = "⌖ Route to " + dest + " is ready - unlock to start it";
        }
        // Forget the previous trip BEFORE Maps starts posting this one. Changing
        // travel mode updates the same notification in place, and two trips from
        // the same spot open with identical first maneuvers, which the relay's
        // dedup swallowed - leaving the last trip's card on the lens.
        NavListener.newTrip();
        try {
            // PIN Google Maps. google.navigation: (and the maps directions URL)
            // also resolve to Waze/Uber/a browser, so a bare VIEW would pop an
            // app-chooser on the lens instead of navigating. MapsActivity is the
            // long-stable Maps entry that handles both forms (verified as a
            // resolver for each). NavListener then relays the live steps.
            LocalAdb.shell(ctx, "am start"
                    + " -n com.google.android.apps.maps/com.google.android.maps.MapsActivity"
                    + " -a android.intent.action.VIEW -d " + shellQuote(uri), 12000);
            return label;
        } catch (Exception e) {
            return "Could not open Maps (" + e + ")";
        }
    }

    /**
     * Hand over to the PHONE's own assistant - Gemini, or whatever is set as
     * the assist app.
     *
     * Sent as KEYCODE_ASSIST rather than an intent, and that is the whole
     * trick: measured on this device, both android.intent.action.ASSIST and
     * android.intent.action.VOICE_COMMAND resolve to an app CHOOSER
     * (ResolverActivity), which is useless on a phone in your pocket and
     * actively confusing relayed to the lens. The key event goes straight to
     * whatever the system has registered as the assistant, exactly as a long
     * press on the power button does - no chooser, no pinned package that a
     * Google app update can invalidate.
     *
     * This ACTIVATES it; it cannot pass a question through. The assistant
     * answers out loud on the phone, not on the glasses, so the card says only
     * what is true: it is listening now.
     */
    public static String assistant(Context ctx, String question) {
        try {
            LocalAdb.shell(ctx, "input keyevent 219", 8000);
        } catch (Exception e) {
            return "Could not reach the phone assistant (" + e + ")";
        }
        // Never imply the question was forwarded when it was not.
        return question == null || question.trim().isEmpty()
                ? "◇ Phone assistant listening."
                : "◇ Assistant listening - ask it out loud.";
    }

    public static String youtube(Context ctx, String query) {
        return youtube(ctx, query, null, null, null);
    }

    /**
     * Steerable YouTube: rank by relevance / newest / popular, search within a
     * channel, and limit how recent. Only the Data API key can do any of this;
     * the SerpApi fallback has no ordering and plays the plain top hit.
     *
     * What is deliberately NOT here, because the API cannot do it: the watch
     * history (removed from the Data API in 2016 - the history playlist is
     * empty for every app), and the subscriptions feed, which exists but needs
     * a Google OAuth grant this app does not hold.
     */
    public static String youtube(Context ctx, String query, String sort, String channel,
                                 String since) {
        String key = Prefs.str(ctx, Prefs.YOUTUBE_KEY, "");
        if (key.isEmpty()) {
            // The caller may have emptied `query` on purpose and put the name in
            // `channel` instead (see Commands.YT_LATEST) - that split only means
            // something to the Data API path below, which is not the one about
            // to run. Fall back to searching the channel name as plain text:
            // no date ordering without the Data API key, but a relevant result
            // instead of a query left blank.
            String q = (query == null || query.trim().isEmpty())
                    && channel != null && !channel.trim().isEmpty() ? channel : query;
            // No Google key needed: SerpApi has a youtube engine, and the key for
            // it is already configured for search. Resolving to a watch URL is
            // what makes the video START - opening a results page just shows a
            // list and waits for a tap, which is what "it searched but did not
            // play" was.
            String serp = Prefs.str(ctx, Prefs.SERPAPI_KEY, "");
            if (!serp.isEmpty()) {
                try {
                    String resp = http("https://serpapi.com/search.json?engine=youtube"
                            + "&search_query=" + enc(q) + "&api_key=" + enc(serp),
                            "GET", null, null, null, 20000);
                    JSONArray vids = new JSONObject(resp).optJSONArray("video_results");
                    if (vids != null && vids.length() > 0) {
                        JSONObject v = vids.getJSONObject(0);
                        String link = v.optString("link", "");
                        if (link.contains("watch?v=")) {
                            return openVideo(ctx, link, YT_GLYPH + " " + v.optString("title", q));
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to the search page
                }
            }
            return open(ctx, "https://www.youtube.com/results?search_query=" + enc(q),
                    YT_GLYPH + " YouTube search: " + q);
        }
        try {
            StringBuilder url = new StringBuilder(YT_SEARCH_URL)
                    .append("?part=snippet&type=video&maxResults=1&key=").append(enc(key));
            // Within a channel: resolve the spoken name to an id first. A plain
            // "q=<channel> <query>" would just find videos ABOUT the channel.
            if (channel != null && !channel.trim().isEmpty()) {
                String cResp = http(YT_SEARCH_URL + "?part=snippet&type=channel&maxResults=1&q="
                        + enc(channel) + "&key=" + enc(key), "GET", null, null, null, 15000);
                JSONArray ch = new JSONObject(cResp).getJSONArray("items");
                if (ch.length() == 0) {
                    return YT_GLYPH + " No YouTube channel called " + channel + ".";
                }
                url.append("&channelId=").append(enc(ch.getJSONObject(0)
                        .getJSONObject("id").getString("channelId")));
            }
            // "The latest from" a channel with no topic means the channel's
            // newest upload, which needs an empty query and a date order.
            String q = query == null ? "" : query.trim();
            if (!q.isEmpty()) {
                url.append("&q=").append(enc(q));
            } else if (channel == null) {
                return YT_GLYPH + " What should I look for on YouTube?";
            } else if (sort == null) {
                sort = "newest";
            }
            if ("newest".equals(sort)) {
                url.append("&order=date");
            } else if ("popular".equals(sort)) {
                url.append("&order=viewCount");
            }
            long days = "week".equals(since) ? 7 : "month".equals(since) ? 31
                    : "year".equals(since) ? 366 : 0;
            if (days > 0) {
                url.append("&publishedAfter=").append(enc(java.time.Instant.now()
                        .minus(days, java.time.temporal.ChronoUnit.DAYS).toString()));
            }
            String resp = http(url.toString(), "GET", null, null, null, 15000);
            JSONArray items = new JSONObject(resp).getJSONArray("items");
            if (items.length() == 0) {
                return YT_GLYPH + " Nothing on YouTube for " + (q.isEmpty() ? channel : q) + ".";
            }
            JSONObject first = items.getJSONObject(0);
            String id = first.getJSONObject("id").getString("videoId");
            String title = first.getJSONObject("snippet").optString("title", query);
            return openVideo(ctx, "https://www.youtube.com/watch?v=" + id, YT_GLYPH + " " + title);
        } catch (Exception e) {
            return "YouTube search failed (" + e + ")";
        }
    }

    /**
     * Launch a URI as shell, which the background-activity-start rules allow.
     *
     * This only OPENS (cues) the target - it never presses a media key to force
     * play. It used to send KEYCODE_MEDIA_PLAY as a "nudge" after a
     * spotify:track: deep link, but a GLOBAL media key routes to whatever app
     * last held the media session: a paused YouTube would resume, "play X"
     * played the wrong thing, and it still claimed success. Spotify playback is
     * driven through the Web API instead (playViaApi / playUrisApi, which target
     * the Spotify device directly); a YouTube watch URL autoplays on its own.
     */
    /**
     * Start a podcast by name on Pocket Casts.
     *
     * Its session advertises ACTION_PLAY_FROM_SEARCH, which is the mechanism
     * Android Auto and Assistant use and the only one that actually plays:
     * measured, the activity intent that looks equivalent
     * (ACTION_MEDIA_PLAY_FROM_SEARCH) only opened the app and left the session
     * in state=ERROR(7) with nothing playing. Pocket Casts searches what the
     * wearer is SUBSCRIBED to, so this finds their own shows rather than the
     * whole directory.
     *
     * A cold app holds no session at all, so it is started first and waited
     * for - firing a control at a package that is not there would otherwise
     * report success and play nothing.
     */
    static String pocketcasts(Context ctx, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            // Bare "play on Pocket Casts" means resume what is already loaded.
            MediaController c = Sessions.find(ctx, Sessions.POCKETCASTS);
            if (c == null) {
                return "♪ Pocket Casts isn't running - say what to play.";
            }
            c.getTransportControls().play();
            String what = waitForPodcast(ctx, 6000);
            return what == null ? "♪ Asked Pocket Casts to resume."
                    : "▶ " + what;
        }
        if (Sessions.find(ctx, Sessions.POCKETCASTS) == null) {
            try {
                LocalAdb.shell(ctx, "am start -n " + PC_ACTIVITY, 12000);
            } catch (Exception e) {
                return "Could not open Pocket Casts (" + e + ")";
            }
            for (int i = 0; i < 12; i++) {
                if (Sessions.find(ctx, Sessions.POCKETCASTS) != null) {
                    break;
                }
                pause(700);
            }
        }
        if (!Sessions.playFromSearch(ctx, Sessions.POCKETCASTS, q)) {
            return "♪ Pocket Casts wouldn't take a search for " + q + ".";
        }
        String what = waitForPodcast(ctx, 12000);
        if (what == null) {
            // Its search is over SUBSCRIBED shows; an unsubscribed one is a
            // miss, not a fault, and saying which is more use than "failed".
            return "♪ Nothing in your Pocket Casts subscriptions for " + q + ".";
        }
        return "▶ " + what;
    }

    /** Wait for Pocket Casts to actually start, and say what it started. */
    private static String waitForPodcast(Context ctx, long budgetMs) {
        long end = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < end) {
            if (Sessions.isPlaying(ctx, Sessions.POCKETCASTS)) {
                String what = Sessions.nowPlaying(ctx, Sessions.POCKETCASTS);
                return what == null ? "Playing on Pocket Casts" : what;
            }
            pause(600);
        }
        return null;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * open(), plus the guarantee that what was opened is actually playing.
     *
     * Only for links that ARE a video. A results page has nothing to play, and
     * pressing play at one would start whatever the last session was.
     */
    private static String openVideo(Context ctx, String uri, String say) {
        String out = open(ctx, uri, say);
        if (out.equals(say)) {                       // the launch itself worked
            ensurePlaying(ctx);
        }
        return out;
    }

    /**
     * Make sure a launched video really started.
     *
     * `am start` on a watch URL autoplays a video YouTube does not already have
     * open. If it DOES have that one open and paused - the same thing asked for
     * twice, or paused earlier and come back to - the deep link only brings the
     * existing, paused player forward. The card then says "playing" over a still
     * frame, and the live session hangs up on the strength of it, because
     * playing() reads the card's own text rather than the phone. Measured: a
     * fresh video reached PLAYING within a second; a re-opened one sat at
     * PAUSED, at the position it was left.
     *
     * KEYCODE_MEDIA_PLAY (126), never PLAY_PAUSE (85): a toggle sent to a video
     * that did start on its own would pause the very thing this is here to
     * guarantee. Pressed at most once, and only when YOUTUBE's own session says
     * it is not playing - an unreadable dump is not evidence of a pause, and
     * some other app's paused session is none of this method's business.
     */
    private static void ensurePlaying(Context ctx) {
        new Thread(() -> {
            try {
                // Let the deep link swap the player over first. Checking sooner
                // reads the OUTGOING video's state and would press play on it.
                Thread.sleep(2500);
                for (int i = 0; i < 4; i++) {
                    Track yt = null;
                    for (Track t : sessions(ctx)) {
                        if (YT_PKG.equals(t.pkg)) {
                            yt = t;
                            break;
                        }
                    }
                    if (yt == null) {
                        Thread.sleep(1500);          // player not up yet
                        continue;
                    }
                    if (yt.playing) {
                        return;                      // started on its own
                    }
                    android.util.Log.i("iohelperMedia", "opened video was paused - pressing play");
                    LocalAdb.shell(ctx, "input keyevent 126", 6000);
                    return;
                }
            } catch (Exception e) {
                android.util.Log.i("iohelperMedia", "ensurePlaying failed: " + e);
            }
        }, "yt-ensure-play").start();
    }

    private static String open(Context ctx, String uri, String say) {
        try {
            LocalAdb.shell(ctx, "am start -a android.intent.action.VIEW -d "
                    + shellQuote(uri), 12000);
            return say;
        } catch (Exception e) {
            return "Could not open the player (" + e + ")";
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String http(String url, String method, String body, String contentType,
                               String auth, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        if (auth != null) {
            c.setRequestProperty("Authorization", auth);
        }
        if (contentType != null) {
            c.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        String text = bos.toString("UTF-8");
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": "
                    + text.substring(0, Math.min(160, text.length())));
        }
        return text;
    }
}
