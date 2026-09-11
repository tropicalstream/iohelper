package com.iohelper.card;
import android.content.Context;
/** Desktop stub: the probe tests CLASSIFICATION only, never speaker control. */
public final class Sonos {
    public static final class Zone { public String room = "stub"; public String ip = "0.0.0.0"; }
    public static Zone pick(Context c, String spoken) { return null; }
    public static String control(Context c, Zone z, String action) { return "stub"; }
    public static String nowPlaying(Context c, Zone z) { return "stub"; }
    public static String playSpotify(Context c, Zone z, String id, String label) { return "stub"; }
    public static String playRadio(Context c, Zone z, String url, String name) { return "stub"; }
    public static String playContainer(Context c, Zone z, String k, String id, String l, boolean s) { return "stub"; }
    public static String playTracks(Context c, Zone z, String[] ids, String l, boolean sh) { return "stub"; }
    public static String shuffle(Context c, Zone z, boolean on) { return "stub"; }
}
