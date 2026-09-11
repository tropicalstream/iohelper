package com.iohelper.card;
import android.content.Context;
/** Desktop stub: the parser test classifies utterances, it never writes a calendar. */
public final class Agenda {
    public static boolean worthKeeping(int seconds, boolean hasClockTime) {
        return hasClockTime || seconds >= 600;
    }
    public static long add(Context c, String title, long dueMs) {
        return -1;
    }
}
