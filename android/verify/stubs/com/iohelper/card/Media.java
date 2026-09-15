package com.iohelper.card;
import android.content.Context;
/** Desktop stub: the probe tests CLASSIFICATION only, never playback. */
public final class Media {
    public static String nowPlaying(Context c) { return "stub"; }
    public static String control(Context c, String action) { return "stub"; }
    public static String spotify(Context c, String q) { return "stub"; }
    public static String[] searchTrack(Context c, String q) { return null; }
    public static String[] searchContainer(Context c, String q, String k) { return null; }
    public static String spotify(Context c, String q, String k, boolean s) { return "stub"; }
    public static String[] artistMatch(Context c, String q) { return null; }
    public static String[] artistTopTracks(Context c, String id, String name, int m) { return null; }
    public static String[] artistPlaylist(Context c, String name) { return null; }
    public static boolean spotifyConfigured(Context c) { return false; }
    public static String[] findAlbum(Context c, String a, String t) { return null; }
    public static String[] findTrack(Context c, String a, String t) { return null; }
    public static String playUri(Context c, String u, boolean k, String l, boolean s) { return "stub"; }
    public static String[][] verifyQueue(Context c, org.json.JSONObject p, int m) { return null; }
    public static String playTracks(Context c, String[] u, String l, boolean s) { return "stub"; }
    public static String youtube(Context c, String q) { return "stub"; }
    public static String youtube(Context c, String q, String sort, String channel, String since) { return "stub"; }
    public static String pocketcasts(Context c, String q) { return "stub"; }
    public static String podcast(Context c, String q) { return "stub"; }
    public static String assistant(Context c, String q) { return "stub"; }
    public static String navigate(Context c, String d, String m) { return "stub"; }
    /** Kept in step with the real Media: YouTube lines carry their own glyph. */
    static final String YT_GLYPH = "▷";
    static boolean playing(String line) {
        return line != null && (line.startsWith("▶") || line.startsWith(YT_GLYPH));
    }
}
