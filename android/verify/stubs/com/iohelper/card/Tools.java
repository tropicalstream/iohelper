package com.iohelper.card;

import android.content.Context;

/**
 * Desktop stub: the probe tests CLASSIFICATION only, so nothing here runs.
 *
 * Commands reaches into Tools for exactly one thing - cancelling a timer,
 * which lives with the other timer bookkeeping rather than in the parser - and
 * the suite only needs the call to compile.
 */
public final class Tools {
    static String cancelTimer(Context c, String label) { return "stub"; }
}
