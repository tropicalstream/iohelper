package com.iohelper.card;
import android.content.Context;
import org.json.JSONObject;
/** Desktop stub: the parser test classifies utterances, it never stores a note. */
public final class Notes {
    public static JSONObject add(Context c, String text) {
        return new JSONObject().put("text", text);
    }
    public static String line(Context c, String query) {
        return "\u25a4 (stub)";
    }
    public static int remove(Context c, String query) {
        return 1;
    }
    public static String snapshot(Context c) {
        return "";
    }
}
