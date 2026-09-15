package com.iohelper.card;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The phone's calendar, read natively.
 *
 * On the PC this was an adb `content query` against the calendar provider and
 * a regex over the shell output; on-device it is just CalendarContract, which
 * is both simpler and less fragile (no parsing of titles containing commas).
 * Needs READ_CALENDAR, a runtime permission - the app asks, or it can be
 * granted with `adb shell pm grant com.iohelper.card android.permission.READ_CALENDAR`.
 */
public final class Cal {

    private Cal() {
    }

    public static final class Event {
        public final long begin;
        public final long end;
        public final boolean allDay;
        public final String title;

        Event(long begin, long end, boolean allDay, String title) {
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.title = title;
        }
    }

    public static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Events starting within the next `hours`, soonest first, de-duplicated. */
    public static List<Event> events(Context ctx, int hours) {
        List<Event> out = new ArrayList<>();
        if (!allowed(ctx)) {
            return out;
        }
        long now = System.currentTimeMillis();
        long end = now + hours * 3600_000L;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now);
        ContentUris.appendId(b, end);
        String[] cols = {CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION};
        Cursor c = null;
        try {
            // VISIBLE=1 only. Every calendar the user has switched OFF was
            // still being read - holidays in Australia, two Classroom feeds, a
            // dead college account - and all of it went into the prompt, where
            // it crowded out the events that matter.
            c = ctx.getContentResolver().query(b.build(), cols,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext()) {
                String title = c.getString(3);
                if (title == null) {
                    title = "(untitled)";
                }
                long bg = c.getLong(0);
                // subscribed calendars duplicate the same event
                String key = bg + "|" + title.toLowerCase();
                if (!seen.add(key)) {
                    continue;
                }
                String where = c.getString(4);
                if (where != null && !where.trim().isEmpty()) {
                    title = title + " @ " + where.trim();
                }
                out.add(new Event(bg, c.getLong(1), c.getInt(2) == 1, title));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /** Compact agenda text for LLM prompt context. */
    private static final String[] DAY_NAMES = {"sunday", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday"};

    /**
     * The stretch of time the question is actually about, as {start, end}.
     *
     * A horizon alone is not enough, and getting this wrong is worse than
     * having no calendar at all. "Am I free Thursday" against a rolling
     * eight-day window put four days of this-week events in front of Thursday's
     * and the list hit its cap long before Thursday appeared - so the assistant
     * reported a day with eight events on it as free. A wrong "yes you're free"
     * is the one answer here that actively costs you something, so a question
     * that names a day now gets THAT DAY's window, not a window starting now.
     */
    public static long[] windowFor(String query) {
        String q = query == null ? "" : query.toLowerCase();
        java.util.Calendar c = java.util.Calendar.getInstance();
        long now = c.getTimeInMillis();

        if (q.matches("(?s).*\\b(?:this|next) week\\b.*") || q.contains("weekend")) {
            return new long[]{now, now + 8L * 24 * 3600_000L};
        }
        for (int i = 0; i < DAY_NAMES.length; i++) {
            if (q.matches("(?s).*\\b" + DAY_NAMES[i] + "\\b.*")) {
                java.util.Calendar d = midnight(java.util.Calendar.getInstance());
                // The NEXT such day; naming today's weekday means today.
                int delta = (i + 1 - d.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7;
                d.add(java.util.Calendar.DAY_OF_YEAR, delta);
                long start = d.getTimeInMillis();
                return new long[]{start, start + 24 * 3600_000L};
            }
        }
        if (q.contains("tomorrow")) {
            java.util.Calendar d = midnight(java.util.Calendar.getInstance());
            d.add(java.util.Calendar.DAY_OF_YEAR, 1);
            long start = d.getTimeInMillis();
            return new long[]{start, start + 24 * 3600_000L};
        }
        // "today"/"tonight" from MIDNIGHT, not now - otherwise an event already
        // under way this morning (asked about at midday while it runs 9:30-4) is
        // filtered out for beginning before now, and the day's listing starts
        // mid-afternoon with the morning gone. Naming the weekday already worked
        // this way; "today" now matches it.
        if (q.contains("today") || q.contains("tonight")) {
            long start = midnight(java.util.Calendar.getInstance()).getTimeInMillis();
            return new long[]{start, start + 24 * 3600_000L};
        }
        return new long[]{now, now + 24 * 3600_000L};
    }

    private static java.util.Calendar midnight(java.util.Calendar c) {
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c;
    }

    /**
     * An all-day event's begin re-anchored from UTC midnight to LOCAL midnight
     * of the same calendar date.
     *
     * All-day events are stored at 00:00 UTC of their date, so in any timezone
     * behind UTC a Friday all-day event's raw timestamp is Thursday evening
     * locally. Labeling or day-windowing it in local time then puts it a day
     * early - which is exactly why "is there a workshop today" answered "no"
     * about a workshop happening today. Re-anchoring to local midnight of the
     * UTC date makes both the label and the day-window place it on the right
     * day, in the same local frame everything else uses.
     */
    private static long allDayLocal(long utcMidnight) {
        java.util.Calendar u =
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utcMidnight);
        java.util.Calendar l = java.util.Calendar.getInstance();
        l.set(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH),
                u.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0);
        l.set(java.util.Calendar.MILLISECOND, 0);
        return l.getTimeInMillis();
    }

    /** Events inside an explicit window. */
    public static List<Event> between(Context ctx, long from, long to) {
        List<Event> out = new ArrayList<>();
        if (!allowed(ctx)) {
            return out;
        }
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] cols = {CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION};
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(b.build(), cols,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext()) {
                String title = c.getString(3);
                if (title == null) {
                    title = "(untitled)";
                }
                long bg = c.getLong(0);
                if (!seen.add(bg + "|" + title.toLowerCase())) {
                    continue;
                }
                String where = c.getString(4);
                if (where != null && !where.trim().isEmpty()) {
                    title = title + " @ " + where.trim();
                }
                out.add(new Event(bg, c.getLong(1), c.getInt(2) == 1, title));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /**
     * The calendar block for a specific question.
     *
     * When the list is cut short it says so IN THE TEXT and names the day it
     * covers. The LLM cannot tell a short list from an empty stretch of
     * calendar otherwise, and it answers "you're free" for both.
     */
    public static String snapshotFor(Context ctx, String query) {
        if (!allowed(ctx)) {
            return "";
        }
        long[] w = windowFor(query);
        // between() asks the provider for everything OVERLAPPING the window, so
        // a long event from today spills into a question about tomorrow. Keep
        // only what actually STARTS inside the day asked about - unless the
        // window is the open-ended "now onwards" one, where an event already
        // under way is still the answer to "what's on".
        boolean aDay = w[1] - w[0] <= 25 * 3600_000L
                && w[0] > System.currentTimeMillis() - 3600_000L;
        List<Event> evs = new ArrayList<>();
        for (Event e : between(ctx, w[0], w[1])) {
            // Compare an all-day event by its LOCAL date, not its UTC-midnight
            // raw begin, or it filters out one day early.
            long begin = e.allDay ? allDayLocal(e.begin) : e.begin;
            if (!aDay || (begin >= w[0] && begin < w[1])) {
                evs.add(e);
            }
        }
        SimpleDateFormat label = new SimpleDateFormat("EEE d MMM", Locale.US);
        String span = label.format(new Date(w[0]));
        if (w[1] - w[0] > 36 * 3600_000L) {
            span = span + " to " + label.format(new Date(w[1]));
        }
        if (evs.isEmpty()) {
            return "[Calendar " + span + ": no events - genuinely nothing scheduled]";
        }
        // Said explicitly, because a terse answer otherwise drops exactly the
        // part that makes it useful: "you have the panel and two protests" is
        // no help when you are deciding what to leave for.
        //
        // And said for ONE event as much as for many. The older wording only
        // covered "listing these", so a question with a single answer - "what
        // is my next event" - fell outside it, and BOTH backends replied with
        // the bare title: no time, no place, which is most of what was asked
        // for. The room is there; a card carries a title line and a body, and
        // a longer answer pages across several.
        String rule = "\nWhen answering from these, ALWAYS give the event's START TIME"
                + " together with its TITLE - for a single event just as much as for a"
                + " list - and include the place when one is shown after \"@\". Never a"
                + " bare time without the title, and never a title without its time.";
        SimpleDateFormat day = new SimpleDateFormat("EEE HH:mm", Locale.US);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder("[Calendar " + span + ", "
                + evs.size() + " event(s)]\n");
        int cap = 20;
        for (int i = 0; i < evs.size() && i < cap; i++) {
            Event e = evs.get(i);
            sb.append("- ");
            if (e.allDay) {
                sb.append(new SimpleDateFormat("EEE", Locale.US)
                        .format(new Date(allDayLocal(e.begin))))
                  .append(" all day");
            } else {
                sb.append(day.format(new Date(e.begin))).append("-")
                  .append(hm.format(new Date(e.end)));
            }
            sb.append("  ").append(e.title).append('\n');
        }
        if (evs.size() > cap) {
            sb.append("- (list truncated: ").append(evs.size() - cap)
              .append(" further events not shown - do NOT say the rest is free)\n");
        }
        return sb.toString().trim() + rule;
    }

    public static String snapshot(Context ctx, int hours) {
        if (!allowed(ctx)) {
            return "";
        }
        List<Event> evs = events(ctx, hours);
        if (evs.isEmpty()) {
            return "[Calendar: no events in the next " + hours + "h]";
        }
        SimpleDateFormat day = new SimpleDateFormat("EEE HH:mm", Locale.US);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder("[Calendar for the next " + hours + "h]\n");
        int n = 0;
        for (Event e : evs) {
            if (n++ >= 12) {
                sb.append("- (+").append(evs.size() - 12).append(" more)\n");
                break;
            }
            sb.append("- ");
            if (e.allDay) {
                sb.append(new SimpleDateFormat("EEE", Locale.US)
                        .format(new Date(allDayLocal(e.begin))))
                  .append(" all day");
            } else {
                sb.append(day.format(new Date(e.begin))).append("-").append(hm.format(new Date(e.end)));
            }
            sb.append("  ").append(e.title).append("\n");
        }
        return sb.toString().trim();
    }

    public static String time(long epochMs) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(epochMs));
    }
}
