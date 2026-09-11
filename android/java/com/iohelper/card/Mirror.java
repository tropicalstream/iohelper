package com.iohelper.card;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A curated copy of what is coming up, so the glasses' dashboard is worth a
 * glance again.
 *
 * WHY. Pointing RayNeo's dashboard at this app's own calendar made the schedule
 * card queryable-by-proxy but empty: the only thing in that calendar is what
 * you explicitly asked the assistant to remember. The real calendar was still
 * readable by the assistant - it answers questions from it - but invisible on
 * the lens. Mirroring a handful of upcoming events across gives both: the
 * dashboard shows the next few things, and the assistant keeps answering from
 * the real thing rather than from this copy.
 *
 * WHAT IS COPIED, and what deliberately is not:
 *
 *  - UPCOMING only. An event that has already started is not useful on a
 *    heads-up display - you are at it. Timed events are taken only while
 *    begin is still in the future.
 *  - ALL-DAY events are kept for today, because "today is a holiday" stays
 *    useful all day and has no start time to be past.
 *  - VISIBLE calendars only, and never this app's own: mirroring our own
 *    mirror would compound every pass.
 *
 * Copies carry {@link #MARK} in their description, which is how a pass tells
 * its own copies from the reminders the user asked for - those are never
 * touched. Description does not cross the link to the glasses (measured), so
 * the marker is invisible on the lens.
 */
public final class Mirror {

    private static final String TAG = "iohelperMirror";
    /**
     * Never more than this on the dashboard at once.
     *
     * NINE, and this is a measured hardware ceiling, not a taste. The glasses
     * paginate the schedule three entries to a crown-rotatable page and stop at
     * THREE PAGES - confirmed on the lens: fourteen events in the calendar, ten
     * forwarded by RayNeo, nine drawn across three pages, the rest never
     * appearing. Sending more than nine buys nothing but wasted rows.
     *
     * The whole nine are filled by the curation order - reminders first, then
     * to-dos, then upcoming events soonest-first - so the most useful nine are
     * what reach the pages.
     *
     * Two things this ceiling does NOT change. Tomorrow's events render as a
     * fixed three-entry preview and do not paginate at all, so on an evening
     * when nothing is left today only three show however many are sent - a
     * device limit with no workaround. And it is nine of what QUALIFIES: an
     * event already under way is dropped before it is counted.
     */
    private static final int MAX = 9;
    /** How far ahead to look for the next MAX events. */
    private static final long HORIZON_MS = 14L * 24 * 3600_000L;
    private static final String MARK = "iohelper-mirror";
    /** Marks a to-do's presence on the card, so it is not mistaken for a reminder. */
    private static final String TODO_MARK = "iohelper-todo";
    /**
     * Cities recognised as a title prefix even when the venue text does not
     * repeat them - the "San Francisco: ..." case, whose address ("Phillip
     * Burton Federal Building ...") never says the city, so the in-location
     * check alone left it inconsistent with Richmond and San Jose. A bounded,
     * region-biased list on top of that check, never instead of it: a prefix
     * that is neither in the location nor a known city (an org, a series name)
     * is still left in the title.
     */
    private static final java.util.Set<String> CITIES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "sanfrancisco", "sanjose", "oakland", "berkeley", "richmond",
                    "losaltos", "paloalto", "eastpaloalto", "fremont", "hayward",
                    "sunnyvale", "santaclara", "mountainview", "redwoodcity",
                    "sanmateo", "dalycity", "alameda", "emeryville", "walnutcreek",
                    "concord", "vallejo", "sacramento", "santacruz", "monterey",
                    "sanleandro", "milpitas", "cupertino", "campbell", "losgatos",
                    "pleasanton", "livermore", "sanramon", "danville",
                    "martinez", "antioch", "southsanfrancisco", "pacifica",
                    "burlingame", "menlopark", "sausalito", "novato", "petaluma",
                    "napa", "sanrafael", "stanford", "losangeles", "sandiego",
                    "sacramento", "newyork", "chicago", "seattle", "portland",
                    "boston", "washington", "denver", "austin"));
    /**
     * How many open to-dos may sit on the card.
     *
     * Three, not all of them. The card holds ten; a to-do list of any size
     * would bury the day it is supposed to sit beside, and a heads-up display
     * showing fifteen errands thirty characters at a time is not a to-do list,
     * it is a wall.
     *
     * They do NOT age out - a to-do stays on the glasses until it is done, by
     * choice. The cost is that three items you have read fifty times become
     * wallpaper and stop registering; the benefit is that nothing you asked to
     * be reminded of ever quietly disappears. Oldest first, so a slot is freed
     * by completing something rather than by time passing.
     */
    private static final int TODO_MAX = 3;
    /**
     * How many entries from a LATER day may take up room on the card.
     *
     * The glasses do not treat all of them alike. Every event crosses the link
     * tagged isTomorrowSchedule, and a later day is rendered as a short preview
     * - about three - while today's can be paged through with the crown. So
     * filling all ten slots with tomorrow's events buys nothing: seven of them
     * are never drawn, and they were displacing today's events and the to-dos,
     * which ARE drawn.
     */
    private static final int FUTURE_MAX = 3;
    /** A pass costs a provider query; the dashboard does not need it faster. */
    private static final long EVERY_MS = 5 * 60_000L;

    private static long lastRun;
    /**
     * When the next thing on the card stops qualifying - a timed event
     * starting, an all-day one ending.
     *
     * Without this the card was only re-checked every five minutes, so an event
     * that had just begun sat on the glasses for up to five minutes after it
     * should have gone. Waking exactly when the soonest one expires costs
     * nothing and makes the removal look immediate.
     */
    private static volatile long nextExpiry;
    /** Set by the content observer when the calendar changes under us. */
    private static volatile boolean dirty;
    /** Coalesce the burst of notifications a single edit produces. */
    private static final long DEBOUNCE_MS = 3000;
    private static volatile long dirtySince;

    private Mirror() {
    }

    /**
     * The calendar changed. Called from a ContentObserver.
     *
     * Without this the mirror only woke every five minutes, so an event you
     * had just created was missing from the glasses for up to five minutes -
     * exactly when you look, and exactly the moment the mirror is supposed to
     * earn its keep. A change now jumps the rate limit, after a short debounce
     * because one edit produces a burst of notifications.
     */
    public static void onCalendarChanged() {
        onDataChanged();
    }

    /**
     * Anything that could alter the card - a calendar edit, or a to-do added or
     * ticked off. Without this, completing something by voice left it sitting
     * on the glasses until the next five-minute pass.
     */
    public static void onDataChanged() {
        dirty = true;
        dirtySince = System.currentTimeMillis();
    }

    /** Cheap to call often; does real work every EVERY_MS, or sooner if dirty. */
    public static void tick(Context ctx) {
        long now = System.currentTimeMillis();
        boolean urgent = dirty && now - dirtySince >= DEBOUNCE_MS;
        boolean expired = nextExpiry > 0 && now >= nextExpiry;
        if (!urgent && !expired && now - lastRun < EVERY_MS) {
            return;
        }
        if (urgent) {
            dirty = false;
        }
        lastRun = now;
        try {
            sync(ctx);
        } catch (Throwable t) {
            android.util.Log.i(TAG, "mirror failed: " + t);
        }
    }

    /** One upcoming event, as it should appear in the mirror. */
    private static final class Item {
        final long sourceId;
        final long begin;
        final long end;
        final boolean allDay;
        final String title;
        final String where;

        Item(long sourceId, long begin, long end, boolean allDay, String title, String where) {
            this.sourceId = sourceId;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.title = title;
            this.where = where;
        }

        /** Identity for "has this changed": the event and when it starts. */
        String key() {
            return sourceId + "@" + begin;
        }
    }

    private static void sync(Context ctx) throws Exception {
        if (!Cal.allowed(ctx) || !Agenda.allowed(ctx)) {
            return;
        }
        long mine = Agenda.calendarId(ctx);
        if (mine < 0) {
            return;
        }
        // MAX is the whole dashboard's budget, not the mirror's. Reminders are
        // things the user explicitly asked to be told, so they keep their place
        // and the mirror takes what is left - otherwise a busy week of copied
        // events would push a reminder off the end of the card.
        List<Item> real = upcoming(ctx, mine);
        List<Item> reminders = upcomingReminders(ctx, mine);
        // A reminder the calendar has since covered is retired, and the alarm
        // moves to the copy so nothing stops notifying.
        java.util.Set<String> alarmed = new java.util.HashSet<>();
        List<Item> kept = new ArrayList<>();
        for (Item r : reminders) {
            Item by = supersededBy(r, real);
            if (by == null) {
                kept.add(r);
                continue;
            }
            ctx.getContentResolver().delete(
                    ContentUris.withAppendedId(syncUri(), r.sourceId), null, null);
            alarmed.add(by.key());
            android.util.Log.i(TAG, "reminder \"" + r.title
                    + "\" superseded by the calendar event at the same time");
        }
        // TO-DOS FIRST, then events. RayNeo renders the card in insertion
        // (_id) order, so the only way to keep to-dos ahead of events is to
        // give them the lower ids - which means, on any change, deleting the
        // whole set and reinserting it in the order it should read. That churns
        // only when something actually changes (the match check below returns
        // early otherwise), and a change is a to-do added/completed or the
        // calendar edited - never the idle five-minute pass.
        java.util.LinkedHashMap<String, Shown> todos = desiredTodos(ctx);
        int budget = MAX - kept.size() - todos.size();

        java.util.Set<String> gone = Store.dismissed(ctx);
        long soonest = Long.MAX_VALUE;
        java.util.LinkedHashMap<String, Shown> events = new java.util.LinkedHashMap<>();
        for (Item it : real) {
            if (events.size() >= budget) {
                break;
            }
            if (gone.contains(it.key())) {
                continue;              // finished with, by voice
            }
            events.put(it.key(), shownFor(it, alarmed.contains(it.key())));
            soonest = Math.min(soonest, it.allDay ? it.end : it.begin);
        }
        nextExpiry = soonest == Long.MAX_VALUE ? 0 : soonest;

        java.util.List<Entry> target = new ArrayList<>();
        for (Map.Entry<String, Shown> e : todos.entrySet()) {
            target.add(new Entry(TODO_MARK, e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, Shown> e : events.entrySet()) {
            target.add(new Entry(MARK, e.getKey(), e.getValue()));
        }

        java.util.List<Copy> current = existingOrdered(ctx, mine);
        boolean same = current.size() == target.size();
        for (int i = 0; same && i < target.size(); i++) {
            Copy h = current.get(i);
            Entry d = target.get(i);
            if (!h.key.equals(d.key) || h.dtstart != d.shown.dtstart
                    || !h.title.equals(d.shown.title)) {
                same = false;
            }
        }
        if (same) {
            return;                    // card already correct; leave the link alone
        }

        for (Copy h : current) {
            ctx.getContentResolver().delete(
                    ContentUris.withAppendedId(syncUri(), h.id), null, null);
        }
        for (Entry d : target) {
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, mine);
            v.put(CalendarContract.Events.TITLE, d.shown.title);
            v.put(CalendarContract.Events.DTSTART, d.shown.dtstart);
            v.put(CalendarContract.Events.DTEND, d.shown.dtend);
            if (!d.shown.where.isEmpty()) {
                v.put(CalendarContract.Events.EVENT_LOCATION, d.shown.where);
            }
            v.put(CalendarContract.Events.ALL_DAY, d.shown.allDay ? 1 : 0);
            v.put(CalendarContract.Events.EVENT_TIMEZONE,
                    d.shown.allDay ? "UTC" : java.util.TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.DESCRIPTION, d.mark + ":" + d.key);
            v.put(CalendarContract.Events.HAS_ALARM, d.shown.carry ? 1 : 0);
            Uri ins = ctx.getContentResolver().insert(
                    CalendarContract.Events.CONTENT_URI, v);
            if (d.shown.carry && ins != null) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(ins));
                r.put(CalendarContract.Reminders.MINUTES, 0);
                r.put(CalendarContract.Reminders.METHOD,
                        CalendarContract.Reminders.METHOD_ALERT);
                ctx.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
        }
        android.util.Log.i(TAG, "mirror rebuilt: " + todos.size() + " to-dos + "
                + events.size() + " events");
    }

    /** One entry in the ordered target: its mark, key, and shown form. */
    private static final class Entry {
        final String mark;
        final String key;
        final Shown shown;

        Entry(String mark, String key, Shown shown) {
            this.mark = mark;
            this.key = key;
            this.shown = shown;
        }
    }

    /**
     * The open to-dos to show, as all-day-today Shown entries, oldest first and
     * de-duplicated by text (the store briefly held "buy milk" twice).
     */
    private static java.util.LinkedHashMap<String, Shown> desiredTodos(Context ctx) {
        java.util.LinkedHashMap<String, Shown> out = new java.util.LinkedHashMap<>();
        java.util.Set<String> seenText = new java.util.HashSet<>();
        long day = todayUtcMidnight();
        for (org.json.JSONObject t : Store.openTodoItems(ctx)) {
            if (out.size() >= TODO_MAX) {
                break;
            }
            String id = t.optString("id");
            String text = flatten(t.optString("text"));
            if (id.isEmpty() || text.isEmpty() || !seenText.add(norm(text))) {
                continue;
            }
            out.put(id, new Shown(day, day + 24 * 3600_000L, true,
                    "\u2713 " + text, "", false));
        }
        return out;
    }

    /** Every card copy (to-dos and events), in _id order = display order. */
    private static java.util.List<Copy> existingOrdered(Context ctx, long mine) {
        java.util.List<Copy> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events._ID,
                            CalendarContract.Events.DESCRIPTION,
                            CalendarContract.Events.DTSTART,
                            CalendarContract.Events.TITLE},
                    CalendarContract.Events.CALENDAR_ID + "=? AND ("
                            + CalendarContract.Events.DESCRIPTION + " LIKE ? OR "
                            + CalendarContract.Events.DESCRIPTION + " LIKE ?)",
                    new String[]{String.valueOf(mine), MARK + ":%", TODO_MARK + ":%"},
                    CalendarContract.Events._ID + " ASC");
            while (c != null && c.moveToNext()) {
                String d = c.getString(1);
                String key = d == null ? "" : d.substring(d.indexOf(':') + 1);
                out.add(new Copy(c.getLong(0), c.getLong(2), c.getString(3), key));
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
     * The next events worth showing, soonest first.
     *
     * Instances is queried rather than Events so that repeating events resolve
     * to their actual next occurrence.
     */
    private static List<Item> upcoming(Context ctx, long excludeCalendar) {
        List<Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now - 24 * 3600_000L);   // reach back for today's all-day rows
        ContentUris.appendId(b, now + HORIZON_MS);
        String[] cols = {CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID};
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(b.build(), cols,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext()) {
                if (c.getLong(6) == excludeCalendar) {
                    continue;                       // never mirror the mirror
                }
                boolean allDay = c.getInt(3) == 1;
                long begin = c.getLong(1);
                long end = c.getLong(2);
                // Upcoming only. An event already under way is not a heads-up -
                // you are at it. All-day rows have no meaningful start, so they
                // stand until the day is over.
                if (allDay ? end <= now : begin <= now) {
                    continue;
                }
                String title = flatten(c.getString(4));
                if (title.isEmpty()) {
                    title = "(untitled)";
                }
                // Location goes in its own column, NOT appended to the title.
                // The dashboard draws it as its own line; folding it into the
                // title only pushed the title off the ~30 characters the lens
                // shows - and these events carry multi-line street addresses,
                // Zoom links and passcodes, so the title vanished entirely.
                String where = flatten(c.getString(5));
                if (where.length() > 60) {
                    where = where.substring(0, 59).trim() + "…";
                }
                // Subscribed calendars carry the same event more than once.
                if (!seen.add(begin + "|" + title.toLowerCase())) {
                    continue;
                }
                out.add(new Item(c.getLong(0), begin, end, allDay, title, where));
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
     * Keep the open to-dos on the card, and return how many are there.
     *
     * ALL-DAY, not timed, and the surface forces this rather than it being a
     * preference: a fully past event is never forwarded to the glasses at all
     * (measured - an ongoing one and an all-day one both arrive, a finished one
     * does not). A to-do given a due time would vanish the moment it went
     * overdue, which is the exact disappearance this exists to prevent. An
     * all-day entry is carried, and is re-dated each day it stays open.
     *
     * No alarm. A standing presence is not a nag; timed reminders in Agenda are
     * what nags.
     */


    /**
     * Take something off the card by name, and return what went.
     *
     * "Mark off the picket" should work on whatever is showing, not only on
     * to-dos. What removal MEANS differs by what it is, and the difference
     * matters: a reminder is ours and is deleted outright, while a mirrored
     * event is a copy of something in a real calendar, so only the copy goes
     * and the source is remembered as dismissed - otherwise the next pass would
     * put it straight back, and deleting the original would be destroying the
     * user's data to tidy a display.
     */
    public static String dismiss(Context ctx, String phrase) {
        if (phrase == null || phrase.trim().isEmpty() || !Agenda.allowed(ctx)) {
            return null;
        }
        long mine = Agenda.calendarId(ctx);
        if (mine < 0) {
            return null;
        }
        if (norm(phrase).isEmpty()) {
            return null;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                            CalendarContract.Events.DESCRIPTION,
                            CalendarContract.Events.DTEND},
                    // cal 59 holds only the current card copies, so match by
                    // name across all of them. No DTEND filter: a seeded all-day
                    // copy ends at midnight UTC, which reads as past in the
                    // evening and used to hide the very row being dismissed.
                    CalendarContract.Events.CALENDAR_ID + "=?",
                    new String[]{String.valueOf(mine)}, null);
            while (c != null && c.moveToNext()) {
                String title = c.getString(1);
                if (!names(phrase, title)) {
                    continue;
                }
                String desc = c.getString(2);
                if (desc != null && desc.startsWith(MARK + ":")) {
                    // Remember it, or the mirror restores it within seconds.
                    Store.dismiss(ctx, desc.substring(MARK.length() + 1),
                            Math.max(c.getLong(3), System.currentTimeMillis() + 3600_000L));
                }
                ctx.getContentResolver().delete(
                        ContentUris.withAppendedId(syncUri(), c.getLong(0)), null, null);
                onDataChanged();
                return title;
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "dismiss failed: " + e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    /**
     * Does what the user said name this entry?
     *
     * Comparing whole normalised strings was useless in practice: "mark off the
     * Union Democracy panel" has to match "Union Democracy & Fighting Business
     * Unionism & Concession Contracts: A WorkWeek Panel", and neither string
     * contains the other. Every content word spoken must appear in the title,
     * which is loose enough for how people refer to a long calendar entry and
     * strict enough that one stray word does not clear the wrong thing.
     */
    private static final java.util.Set<String> STOP = new java.util.HashSet<>(
            java.util.Arrays.asList("the", "a", "an", "my", "that", "this", "for",
                    "and", "off", "out", "event", "appointment", "meeting", "thing",
                    "one", "it", "to", "of", "on", "at", "with"));

    private static boolean names(String phrase, String title) {
        if (title == null) {
            return false;
        }
        String t = title.toLowerCase(java.util.Locale.US);
        int need = 0;
        int hit = 0;
        for (String w : phrase.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (w.length() < 3 || STOP.contains(w)) {
                continue;
            }
            need++;
            if (t.contains(w)) {
                hit++;
            }
        }
        return need > 0 && hit == need;
    }

    /** The last instant of today, locally. */
    private static long endOfLocalDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 23);
        c.set(java.util.Calendar.MINUTE, 59);
        c.set(java.util.Calendar.SECOND, 59);
        c.set(java.util.Calendar.MILLISECOND, 999);
        return c.getTimeInMillis();
    }

    /** Midnight today, expressed the way an all-day row wants it. */
    private static long todayUtcMidnight() {
        java.util.Calendar local = java.util.Calendar.getInstance();
        java.util.Calendar utc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH),
                local.get(java.util.Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    /**
     * One line, no runs of whitespace. Calendar text from subscribed feeds is
     * full of newlines, and a newline inside a title breaks the dashboard's
     * line-per-field layout.
     */
    private static String flatten(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Reminders of the user's own still ahead of us - anything in our calendar
     * that is NOT one of our copies. These have first claim on the card.
     */
    private static List<Item> upcomingReminders(Context ctx, long mine) {
        List<Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND},
                    CalendarContract.Events.CALENDAR_ID + "=? AND "
                            + CalendarContract.Events.DTEND + ">? AND ("
                            + CalendarContract.Events.DESCRIPTION + " IS NULL OR ("
                            + CalendarContract.Events.DESCRIPTION + " NOT LIKE ? AND "
                            + CalendarContract.Events.DESCRIPTION + " NOT LIKE ?))",
                    new String[]{String.valueOf(mine),
                            String.valueOf(System.currentTimeMillis()),
                            MARK + ":%", TODO_MARK + ":%"}, null);
            while (c != null && c.moveToNext()) {
                out.add(new Item(c.getLong(0), c.getLong(2), c.getLong(3), false,
                        flatten(c.getString(1)), ""));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /** Letters and digits only, so case and punctuation stop mattering. */
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * A real calendar event that is the same thing as a reminder, or null.
     *
     * THE REAL EVENT WINS. Putting something in your calendar and also asking
     * Jarvis to remind you about it is normal, and the card was showing both -
     * the same errand at 09:59 from the reminder and 10:00 from the event. The
     * calendar entry is the one the user actually keeps, so it is the one that
     * shows, at its own time, and the reminder it supersedes is removed with
     * its alarm carried over to the copy.
     *
     * FIVE minutes, not thirty. Within five it is the same moment and the two
     * are plainly one thing. A reminder twenty minutes before an event is a
     * deliberate pre-alert, not a duplicate, and both belong on the card.
     */
    private static final long SAME_THING_MS = 5 * 60_000L;

    private static Item supersededBy(Item reminder, List<Item> real) {
        String a = norm(reminder.title);
        if (a.isEmpty()) {
            return null;
        }
        for (Item e : real) {
            String b = norm(e.title);
            if (b.isEmpty()) {
                continue;
            }
            boolean sameish = a.equals(b) || (a.length() > 6 && b.contains(a))
                    || (b.length() > 6 && a.contains(b));
            if (sameish && Math.abs(e.begin - reminder.begin) <= SAME_THING_MS) {
                return e;
            }
        }
        return null;
    }

    /** A mirror copy already on the card: its row id, shown date and shown title. */
    private static final class Copy {
        final long id;
        final long dtstart;
        final String title;
        final String key;

        Copy(long id, long dtstart, String title, String key) {
            this.id = id;
            this.dtstart = dtstart;
            this.title = title;
            this.key = key;
        }
    }

    /** The desired shown form of a source event - what should be on the card. */
    private static final class Shown {
        final long dtstart;
        final long dtend;
        final boolean allDay;
        final String title;
        final String where;
        final boolean carry;

        Shown(long dtstart, long dtend, boolean allDay, String title,
              String where, boolean carry) {
            this.dtstart = dtstart;
            this.dtend = dtend;
            this.allDay = allDay;
            this.title = title;
            this.where = where;
            this.carry = carry;
        }
    }



    /** Local midnight today, as an ordinary epoch. */
    private static long localMidnightToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Where a seeded copy is placed: late TODAY, so it is "today" to RayNeo
     *  (and therefore paginates) and still ahead of the clock all evening. */
    private static long seedInstant() {
        return localMidnightToday() + 23 * 3600_000L;
    }

    /** Is this source event actually happening today, in local terms? */
    private static boolean isTodayLocal(Item it) {
        if (it.allDay) {
            return it.begin == todayUtcMidnight();
        }
        long mid = localMidnightToday();
        return it.begin >= mid && it.begin < mid + 24 * 3600_000L;
    }

    /**
     * The real day (and time) as a title prefix, so an event moved onto today
     * for the sake of pagination still reads as when it truly is.
     *
     * All-day sources are formatted in UTC - their start is a UTC midnight that
     * in a western zone lands the evening before, and a local format would name
     * the wrong day.
     */
    private static String whenLabel(Item it) {
        java.text.SimpleDateFormat f;
        if (it.allDay) {
            f = new java.text.SimpleDateFormat("EEE", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.format(new java.util.Date(it.begin));
        }
        f = new java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(it.begin));
    }

    /**
     * How a source event should appear on the card.
     *
     * Today's timed events are shown as themselves - real time, real title, a
     * correct time line. Everything else is a future day, which RayNeo renders
     * as a fixed three-item preview that will not paginate; to get it onto a
     * crown-rotatable page it has to look like TODAY, so it is copied to a late
     * time today with the real day and time carried in the title. An all-day
     * copy would be simpler but RayNeo drops an all-day "today" event once UTC
     * has rolled past midnight, so the copy is a timed one.
     */
    private static Shown shownFor(Item it, boolean carry) {
        // Lift a redundant "City: ..." prefix in BOTH cases - a today event and
        // a seeded future one. Today's events used to skip this and show the raw
        // "Richmond: <event>" title with the full venue, so the city reverted to
        // the venue the moment an event rolled into today.
        String[] lift = liftCity(it);
        String title = lift[0];
        String place = lift[1];       // the city when lifted, else the full venue

        if (isTodayLocal(it) && !it.allDay) {
            // RayNeo shows the real time for a today event, so the location line
            // is just the place - the city (or the venue), no "EEE HH:mm".
            return new Shown(it.begin, it.end, false, title, place, carry);
        }
        // ALL-DAY today, not a timed slot. Proven on the lens: an all-day
        // event dated to LOCAL today (in cal 59, with tz UTC) is tagged today
        // by RayNeo and paginates, shows "All day" instead of a fake clock
        // time, and gives the TITLE two lines. The real day and time go on the
        // LOCATION line (prefixing them onto the title ate its front), with the
        // place trailing.
        long day = todayUtcMidnight();
        String loc = whenLabel(it);
        if (!place.isEmpty()) {
            loc = loc + "  " + place;
        }
        return new Shown(day, day + 24 * 3600_000L, true, title, loc, carry);
    }

    /**
     * Lift a redundant leading "City: ..." out of the title. Returns {title,
     * place}: when the prefix genuinely names the location - it appears in the
     * venue text, or is a known city - the title keeps only the event and the
     * place is that city, in place of a long venue; otherwise the original title
     * and full venue. "Richmond: Workers Over Billionaires! ... Rally" @
     * "...Richmond City Hall" -> {"Workers Over Billionaires! ... Rally",
     * "Richmond"}. "SF Mime Troupe: WRECKAGE" is left alone - the prefix is not
     * in the venue and not a known city, so it is not a place.
     */
    private static String[] liftCity(Item it) {
        int colon = it.title.indexOf(':');
        if (colon >= 2 && colon <= 24 && !it.where.isEmpty()) {
            String prefix = it.title.substring(0, colon).trim();
            String rest = it.title.substring(colon + 1).trim();
            String np = norm(prefix);
            boolean isPlace = !np.isEmpty()
                    && (norm(it.where).contains(np) || CITIES.contains(np));
            if (!rest.isEmpty() && isPlace) {
                return new String[]{rest, prefix};
            }
        }
        return new String[]{it.title, it.where};
    }

    /**
     * Deleting through the sync-adapter URI, because the plain one only marks
     * deleted=1 and leaves the row behind for a sync adapter that does not
     * exist here - the mirror would then grow without bound.
     */
    private static Uri syncUri() {
        return CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, Agenda.ACCOUNT)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
    }
}
