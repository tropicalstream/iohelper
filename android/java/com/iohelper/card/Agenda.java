package com.iohelper.card;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;

/**
 * Reminders that survive being missed.
 *
 * WHY THIS EXISTS. A reminder used to be one transient notification card. If it
 * arrived while the display was asleep, or while you were reading something
 * else, it was simply gone - which is exactly what happened during the first
 * outdoor test, where several reminders were set and none were ever seen.
 *
 * Writing the same reminder into a calendar buys two independent deliveries
 * instead of one:
 *
 *   1. The glasses' DASHBOARD renders it as a schedule card - title, a time
 *      range it draws itself, and location - and that card PERSISTS. It sits on
 *      the active list until the event passes, so a missed reminder can still be
 *      read by rotating the crown. Measured 2026-09-06: roughly 30 characters
 *      per line, and multiple events list together.
 *   2. The phone's calendar app fires its own notification at the due time,
 *      which the RayNeo relay forwards like any other - a second card, from a
 *      different sender, that does not depend on this app being awake.
 *
 * Both are additional to the existing in-app card, which still carries the full
 * text; the calendar copy is the durable one, not the detailed one.
 *
 * WHY A PRIVATE CALENDAR. Events go into a LOCAL calendar named "Jarvis" that
 * this app creates, never into the user's real calendars. It is a normal
 * calendar - it can be hidden or deleted in any calendar app, and deleting it
 * takes every reminder with it - and it keeps the dashboard's schedule widget
 * useful instead of flooding a shared calendar with assistant chatter.
 * LOCAL means it never syncs anywhere.
 */
public final class Agenda {

    private static final String TAG = "iohelperAgenda";
    /** Package-visible: Mirror builds the same sync-adapter URI. */
    static final String ACCOUNT = "iohelper";
    private static final String DISPLAY_NAME = "Jarvis";
    /** Reminders shorter than this stay in-app: a kitchen timer is not an
     *  appointment, and putting one on the dashboard is just noise. */
    private static final int MIN_SECONDS = 600;
    private static final int DURATION_MIN = 15;
    /** Past reminders are pruned after this, so the calendar cannot grow forever. */
    private static final long KEEP_DAYS = 7L;

    private Agenda() {
    }

    /** Whether a reminder this far out is worth persisting. */
    public static boolean worthKeeping(int seconds, boolean hasClockTime) {
        return hasClockTime || seconds >= MIN_SECONDS;
    }

    public static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Add the reminder. Returns the event id, or -1 if anything at all stopped
     * it - a missing permission, a provider that refused the insert, an OEM
     * calendar that will not take a local account. The caller must treat -1 as
     * "no second delivery", never as an error worth showing: the in-app card is
     * still going out either way, and a reminder that half-works is worse than
     * one that quietly works once.
     */
    public static long add(Context ctx, String title, long dueMs) {
        if (!allowed(ctx) || title == null || title.trim().isEmpty()) {
            return -1;
        }
        try {
            long cal = calendarId(ctx);
            if (cal < 0) {
                return -1;
            }
            prune(ctx, cal);
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, cal);
            v.put(CalendarContract.Events.TITLE, title.trim());
            v.put(CalendarContract.Events.DTSTART, dueMs);
            v.put(CalendarContract.Events.DTEND, dueMs + DURATION_MIN * 60_000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone
                    .getDefault().getID());
            // HAS_ALARM plus a Reminders row is what makes the phone's calendar
            // app raise its own notification - the second delivery. Without it
            // the event only ever appears on the dashboard.
            v.put(CalendarContract.Events.HAS_ALARM, 1);
            Uri u = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) {
                return -1;
            }
            long id = ContentUris.parseId(u);
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, id);
            r.put(CalendarContract.Reminders.MINUTES, 0);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            ctx.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
            android.util.Log.i(TAG, "reminder " + id + " at " + dueMs + ": " + title);
            return id;
        } catch (Throwable t) {
            android.util.Log.i(TAG, "calendar copy failed: " + t);
            return -1;
        }
    }

    /** Find our calendar, creating it the first time. -1 if it cannot be made. */
    static long calendarId(Context ctx) {
        Uri uri = sync(CalendarContract.Calendars.CONTENT_URI);
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                        + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                new String[]{ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL}, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Throwable ignored) {
        }
        try {
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT);
            v.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
            v.put(CalendarContract.Calendars.NAME, ACCOUNT);
            v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, DISPLAY_NAME);
            v.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF7C5CFF);
            v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER);
            v.put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT);
            v.put(CalendarContract.Calendars.VISIBLE, 1);
            v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
            v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE,
                    java.util.TimeZone.getDefault().getID());
            Uri u = ctx.getContentResolver().insert(sync(CalendarContract.Calendars.CONTENT_URI), v);
            return u == null ? -1 : ContentUris.parseId(u);
        } catch (Throwable t) {
            android.util.Log.i(TAG, "could not create the Jarvis calendar: " + t);
            return -1;
        }
    }

    /** Drop reminders that finished more than KEEP_DAYS ago. */
    private static void prune(Context ctx, long cal) {
        try {
            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000L;
            // The sync-adapter URI, because the plain one only marks
            // deleted=1 and leaves the row for a sync adapter to collect.
            // This calendar is LOCAL and has no sync adapter, so those rows
            // would never be reclaimed and the prune would do nothing.
            ctx.getContentResolver().delete(sync(CalendarContract.Events.CONTENT_URI),
                    CalendarContract.Events.CALENDAR_ID + "=? AND "
                            + CalendarContract.Events.DTEND + "<?",
                    new String[]{String.valueOf(cal), String.valueOf(cutoff)});
        } catch (Throwable ignored) {
        }
    }

    /**
     * Creating or deleting a CALENDAR (as opposed to an event) is only allowed
     * through the sync-adapter form of the URI; the plain one silently refuses.
     */
    private static Uri sync(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
    }
}
