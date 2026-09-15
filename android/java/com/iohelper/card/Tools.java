package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The assistant's hands: the functions a tool-calling model may invoke, and
 * the dispatcher that runs them.
 *
 * WHY THIS EXISTS. {@link Commands#parse} is a set of regexes, and a regex has
 * a ceiling: "the pasta needs twelve minutes, let me know when it's done" or
 * "I don't need the laundry timer anymore" fall through it and land at the
 * model, which until now could only answer questions - it was told in so many
 * words that it cannot act, so the request died with an apology. Here the model is given the same functions
 * the regexes reach, with STRUCTURED arguments, and it decides which to call.
 * The regexes still run first: a phrasing they recognise never costs a model
 * round-trip, so this only sees what they missed.
 *
 * ONE MANIFEST, TWO RENDERINGS. Each tool is declared once, as a name, a
 * description the model reads, a JSON-schema parameter block, and whether it
 * is an ACTION (changes something) or a QUERY (only reads). Groq's
 * chat/completions and OpenAI's /responses spell the same declaration
 * differently, so {@link #chatManifest} and {@link #responsesManifest} render
 * it into each.
 *
 * ACTIONS SPEAK FOR THEMSELVES. An action tool goes through
 * {@link Commands#run}, so the line it produces is the very line the regex
 * path would have put on the glasses - "⏰ Timer set: pasta in 3 min." - and
 * that line is what the wearer sees, not the model's paraphrase of it. A model
 * that has just been told a timer was set will happily say "Done, 5 minutes"
 * about a 3-minute timer; the tool's own words cannot drift. Query results go
 * back to the model, which answers from them.
 *
 * This is also the tool surface a full-duplex voice front end will delegate
 * to later: same functions, same dispatcher, a different mouth.
 */
final class Tools {

    private Tools() {
    }

    /** One tool as the model sees it, plus whether running it changes anything. */
    static final class Spec {
        final String name;
        final String desc;
        final JSONObject params;
        final boolean action;

        Spec(String name, String desc, boolean action, JSONObject params) {
            this.name = name;
            this.desc = desc;
            this.action = action;
            this.params = params;
        }
    }

    // ---- schema helpers -------------------------------------------------------
    private static JSONObject prop(String type, String desc) throws JSONException {
        return new JSONObject().put("type", type).put("description", desc);
    }

    private static JSONObject choice(String desc, String... values) throws JSONException {
        JSONArray a = new JSONArray();
        for (String v : values) {
            a.put(v);
        }
        return prop("string", desc).put("enum", a);
    }

    private static JSONObject params(JSONObject props, String... required) throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) {
            req.put(r);
        }
        return new JSONObject().put("type", "object").put("properties", props).put("required", req);
    }

    private static JSONObject none() throws JSONException {
        return params(new JSONObject());
    }

    static final List<Spec> SPECS = build();

    private static List<Spec> build() {
        try {
            List<Spec> t = new ArrayList<>();
            // ---- reads ----
            t.add(new Spec("get_calendar",
                    "Events on the user's own calendar. Use for anything about their "
                    + "schedule, appointments, meetings or plans.", false,
                    params(new JSONObject().put("when", prop("string",
                            "'today', 'tonight', 'tomorrow', a weekday name, 'this week' or "
                            + "'next week'")), "when")));
            t.add(new Spec("get_directions",
                    "Travel time, distance and route with current traffic. Use for 'how "
                    + "long to get to', 'traffic to', 'commute', 'how far'.", false,
                    params(new JSONObject()
                            .put("destination", prop("string", "Place or address"))
                            .put("origin", prop("string",
                                    "Starting point; omit to use the phone's current position"))
                            .put("mode", choice("Travel mode", "drive", "walk", "bike", "transit")),
                            "destination")));
            t.add(new Spec("search_web",
                    "Live facts from the web: weather, sports scores, prices, opening "
                    + "hours, news, anything current or specific. Not for the user's own "
                    + "calendar, list or notes.", false,
                    params(new JSONObject().put("query", prop("string", "What to look up")),
                            "query")));
            t.add(new Spec("list_timers", "Running timers and how long each has left.",
                    false, none()));
            t.add(new Spec("list_todos", "The user's open to-do items.", false, none()));
            t.add(new Spec("read_notes", "The user's saved notes, newest first.", false,
                    params(new JSONObject().put("query", prop("string",
                            "Words to filter by; omit for all notes")))));
            t.add(new Spec("now_playing", "What is playing right now.", false,
                    params(new JSONObject().put("room", prop("string",
                            "A Sonos room name to ask that speaker; omit for the phone")))));
            // ---- actions ----
            t.add(new Spec("set_timer", "Start a countdown from now.", true,
                    params(new JSONObject()
                            .put("seconds", prop("integer", "Total duration in seconds"))
                            .put("label", prop("string", "What the timer is for")),
                            "seconds")));
            t.add(new Spec("set_reminder_at",
                    "A reminder at a clock time (today, or tomorrow if that time has "
                    + "passed).", true,
                    params(new JSONObject()
                            .put("time", prop("string", "Clock time such as '5:00 PM' or '7:30 am'"))
                            .put("label", prop("string", "What to be reminded of")),
                            "time", "label")));
            t.add(new Spec("cancel_timer", "Cancel a running timer.", true,
                    params(new JSONObject().put("label", prop("string",
                            "A word from the timer's label; omit to cancel the most recent")))));
            t.add(new Spec("add_todo", "Add an item to the user's to-do list.", true,
                    params(new JSONObject().put("text", prop("string", "The item")), "text")));
            t.add(new Spec("complete_todo", "Tick an item off the to-do list.", true,
                    params(new JSONObject().put("match", prop("string",
                            "A word or two from the item")), "match")));
            t.add(new Spec("add_note", "Save a note for later.", true,
                    params(new JSONObject().put("text", prop("string", "The note")), "text")));
            t.add(new Spec("delete_note", "Delete saved notes matching some words.", true,
                    params(new JSONObject().put("query", prop("string",
                            "Words that appear in the note")), "query")));
            t.add(new Spec("play_music",
                    "Play a song, artist, album, playlist, genre or mood. Spotify unless "
                    + "the user asks for YouTube or a video. For YouTube, the user can "
                    + "steer the search: 'the latest video from <channel>' -> "
                    + "youtube_channel + youtube_sort=newest; 'the most popular video "
                    + "about X' -> youtube_sort=popular; 'something new about X this "
                    + "week' -> youtube_since=week. YouTube watch history and the "
                    + "subscriptions feed are NOT available - say so if asked. "
                    + "PODCASTS: service=pocketcasts. It searches the shows the user "
                    + "SUBSCRIBES to and matches the SHOW name, not an episode title, so "
                    + "pass the show ('all twit') rather than the episode ('tech news "
                    + "weekly 454'); it cannot browse the wider podcast directory, and a "
                    + "show that is not subscribed comes back as not found. NOT FOR "
                    + "RADIO: a station or call sign like KPFA, WNYC or KEXP belongs to "
                    + "play_radio - sent here it finds an unrelated song.", true,
                    params(new JSONObject()
                            .put("query", prop("string", "What to play, as the user said it"))
                            .put("service", choice("Where to find it",
                                    "spotify", "youtube", "pocketcasts"))
                            .put("kind", choice("What the query names", "track", "album", "playlist"))
                            .put("shuffle", prop("boolean", "Shuffle the album or playlist"))
                            .put("room", prop("string",
                                    "A Sonos room name to play there; omit for the phone"))
                            .put("youtube_sort", choice("YouTube only: how to rank results",
                                    "relevance", "newest", "popular"))
                            .put("youtube_channel", prop("string",
                                    "YouTube only: a channel name to search within"))
                            .put("youtube_since", choice("YouTube only: only videos this recent",
                                    "week", "month", "year")),
                            "query")));
            t.add(new Spec("play_radio",
                    "Play a live internet radio station, searched by name, call sign or "
                    + "genre across a worldwide directory. USE THIS FOR CALL SIGNS - "
                    + "KPFA, WNYC, KEXP, KQED and the like are radio stations, not songs "
                    + "or podcasts, and play_music would find an unrelated track. Also "
                    + "for 'the news', a genre ('jazz radio') or a named broadcaster "
                    + "('BBC World Service'). This is LIVE radio: there is nothing to "
                    + "search within it and no episodes to pick from.",
                    true, params(new JSONObject()
                            .put("station", prop("string",
                                    "Station name, call sign or genre"))
                            .put("room", prop("string",
                                    "A Sonos room name to play there; omit for the phone")),
                            "station")));
            t.add(new Spec("media_control",
                    "Control whatever is playing - music, a podcast or radio. On a "
                    + "podcast, next/previous are the app's forward/back jumps rather "
                    + "than a change of episode, because a podcast has no next track.",
                    true,
                    params(new JSONObject()
                            .put("action", choice("The control", "play", "pause", "stop",
                                    "next", "previous", "louder", "quieter"))
                            .put("room", prop("string",
                                    "A Sonos room name to control that speaker; omit for the phone")),
                            "action")));
            t.add(new Spec("navigate", "Start turn-by-turn navigation in Google Maps.", true,
                    params(new JSONObject()
                            .put("destination", prop("string", "Place or address"))
                            .put("mode", choice("Travel mode", "drive", "walk", "bike", "transit")),
                            "destination")));
            t.add(new Spec("end_navigation", "Stop the current navigation.", true, none()));
            t.add(new Spec("ask_phone_assistant",
                    "Hand a request to the phone's own assistant for phone functions not "
                    + "covered here: flashlight, texting, calling, alarms, settings, smart "
                    + "home.", true,
                    params(new JSONObject().put("request", prop("string",
                            "The request, in the user's words")), "request")));
            return t;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static Spec byName(String name) {
        for (Spec s : SPECS) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** OpenAI-compatible chat/completions shape (Groq). */
    static JSONArray chatManifest() throws JSONException {
        JSONArray out = new JSONArray();
        for (Spec s : SPECS) {
            out.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", s.name).put("description", s.desc).put("parameters", s.params)));
        }
        return out;
    }

    /** OpenAI /responses shape: the same fields, one level flatter. */
    static JSONArray responsesManifest() throws JSONException {
        JSONArray out = new JSONArray();
        for (Spec s : SPECS) {
            out.put(new JSONObject().put("type", "function").put("name", s.name)
                    .put("description", s.desc).put("parameters", s.params));
        }
        return out;
    }

    /**
     * Run one tool call and return what the MODEL should be told. Side effects
     * on the turn: an action's own line is queued for the glasses, and a
     * hand-off that draws its own cards marks the turn silent.
     */
    static String call(Context ctx, String name, String argsJson, Llm.Turn turn) {
        JSONObject a;
        try {
            a = argsJson == null || argsJson.trim().isEmpty() ? new JSONObject() : new JSONObject(argsJson);
        } catch (JSONException e) {
            a = new JSONObject();
        }
        Spec spec = byName(name);
        if (spec == null) {
            return "Unknown tool: " + name;
        }
        turn.calls++;
        String out;
        try {
            out = run(ctx, name, a);
        } catch (Throwable t) {
            out = "Tool failed: " + t;
        }
        if (out == null) {
            out = "";
        }
        if (!spec.action) {
            turn.queries++;
            return out.isEmpty() ? "(nothing)" : out;
        }
        if (out.isEmpty()) {
            turn.silent = true;                 // it is drawing its own cards
            return "Done - handed off. Nothing more to show.";
        }
        turn.actions.add(out);
        if ("set_timer".equals(name) || "set_reminder_at".equals(name)) {
            turn.kind = "timer";                // keeps the countdown bar alive
        }
        // Told explicitly, so the model neither repeats the line nor invents
        // its own account of what happened.
        return "Done. This exact line is now on the glasses: " + out;
    }

    private static String s(JSONObject a, String key, String def) {
        if (a.isNull(key)) {
            return def;
        }
        String v = a.optString(key, "").trim();
        return v.isEmpty() ? def : v;
    }

    private static int i(JSONObject a, String key, int def) {
        if (a.isNull(key)) {
            return def;
        }
        try {
            return (int) Math.round(a.getDouble(key));
        } catch (JSONException e) {
            try {
                return Integer.parseInt(a.optString(key, "").trim());
            } catch (NumberFormatException n) {
                return def;
            }
        }
    }

    private static String run(Context ctx, String name, JSONObject a) throws Exception {
        switch (name) {
            case "get_calendar": {
                String when = s(a, "when", "today");
                if (!Cal.allowed(ctx)) {
                    return "Calendar access is not granted on the phone.";
                }
                String snap = Cal.snapshotFor(ctx, when);
                return snap.isEmpty() ? "[Calendar: nothing found for " + when + "]" : snap;
            }
            case "get_directions": {
                String dest = s(a, "destination", null);
                if (dest == null) {
                    return "destination is required.";
                }
                if (!Search.available(ctx)) {
                    return "Live search is not configured (no SerpApi key).";
                }
                String origin = s(a, "origin", null);
                if (origin == null) {
                    origin = Loc.origin(ctx);
                }
                if (origin == null || origin.trim().isEmpty()) {
                    return "The phone's position is unknown; ask for the starting point.";
                }
                String mode = s(a, "mode", "drive");
                String r = Search.directions(ctx, dest, origin, mode);
                return r == null ? "No route found from " + origin + " to " + dest + "." : r;
            }
            case "search_web": {
                String q = s(a, "query", null);
                if (q == null) {
                    return "query is required.";
                }
                if (!Search.available(ctx)) {
                    return "Live search is not configured (no SerpApi key).";
                }
                String r = Search.webAnswer(ctx, q, Loc.searchLocation(ctx));
                return r == null ? "No results for: " + q : r;
            }
            case "list_timers":
                return timers(ctx);
            case "list_todos":
                return Commands.run(ctx, new Commands.Cmd("todo.list", 0, ""));
            case "read_notes":
                return Commands.run(ctx, new Commands.Cmd("note.read", 0, s(a, "query", "")));
            case "now_playing": {
                String room = s(a, "room", null);
                return Commands.run(ctx, room != null
                        ? new Commands.Cmd("sonos.now", 0, "", room)
                        : new Commands.Cmd("media.now", 0, ""));
            }
            case "set_timer": {
                int secs = i(a, "seconds", 0);
                if (secs <= 0) {
                    return "seconds must be a positive whole number.";
                }
                return Commands.run(ctx, new Commands.Cmd("timer", secs, s(a, "label", "Timer")));
            }
            case "set_reminder_at": {
                String time = s(a, "time", null);
                if (time == null) {
                    return "time is required, e.g. 5:00 PM.";
                }
                Commands.Clock c = Commands.parseClock("at " + time);
                if (c == null) {
                    return "Couldn't understand the time '" + time + "'.";
                }
                return Commands.run(ctx, new Commands.Cmd("timer", c.seconds,
                        s(a, "label", "Reminder"), c.label));
            }
            case "cancel_timer":
                return cancelTimer(ctx, s(a, "label", null));
            case "add_todo": {
                String text = s(a, "text", null);
                return text == null ? "text is required."
                        : Commands.run(ctx, new Commands.Cmd("todo", 0, text));
            }
            case "complete_todo": {
                String m = s(a, "match", null);
                return m == null ? "match is required."
                        : Commands.run(ctx, new Commands.Cmd("done", 0, m));
            }
            case "add_note": {
                String text = s(a, "text", null);
                return text == null ? "text is required."
                        : Commands.run(ctx, new Commands.Cmd("note.add", 0, text));
            }
            case "delete_note": {
                String q = s(a, "query", null);
                return q == null ? "query is required."
                        : Commands.run(ctx, new Commands.Cmd("note.delete", 0, q));
            }
            case "play_music": {
                String q = s(a, "query", null);
                if (q == null) {
                    return "query is required.";
                }
                String room = s(a, "room", null);
                String service = s(a, "service", "").toLowerCase(Locale.US);
                String kind = s(a, "kind", "").toLowerCase(Locale.US);
                // The speaker's room name travels in `due`, where Sonos.pick
                // matches it by substring - exactly what the regex path passes.
                Commands.Cmd c = room != null
                        ? new Commands.Cmd("sonos.play", 0, q, room)
                        : new Commands.Cmd("media.play", 0, q,
                                "youtube".equals(service) ? "youtube"
                                        : "pocketcasts".equals(service) ? "pocketcasts"
                                        : "spotify".equals(service) ? "spotify" : null);
                c.contentType = "album".equals(kind) ? "album"
                        : "playlist".equals(kind) ? "playlist" : null;
                c.shuffle = a.optBoolean("shuffle", false);
                c.descr = q;                                // lets the knowledge resolver see the wording
                c.ytSort = s(a, "youtube_sort", null);
                c.ytChannel = s(a, "youtube_channel", null);
                c.ytSince = s(a, "youtube_since", null);
                return Commands.run(ctx, c);
            }
            case "play_radio": {
                String st = s(a, "station", null);
                return st == null ? "station is required."
                        : Commands.run(ctx, new Commands.Cmd("radio.play", 0, st, s(a, "room", null)));
            }
            case "media_control": {
                String act = s(a, "action", "").toLowerCase(Locale.US);
                if (!act.matches("play|pause|stop|next|previous|louder|quieter")) {
                    return "action must be one of play, pause, stop, next, previous, louder, quieter.";
                }
                String room = s(a, "room", null);
                return Commands.run(ctx, room != null
                        ? new Commands.Cmd("sonos.control", 0, act, room)
                        : new Commands.Cmd("media.control", 0, act));
            }
            case "navigate": {
                String dest = s(a, "destination", null);
                if (dest == null) {
                    return "destination is required.";
                }
                String mode = s(a, "mode", "drive").toLowerCase(Locale.US);
                if (!mode.matches("drive|walk|bike|transit")) {
                    mode = "drive";
                }
                return Commands.run(ctx, new Commands.Cmd("nav.start", 0, dest, mode));
            }
            case "end_navigation":
                return Commands.run(ctx, new Commands.Cmd("nav.stop", 0, ""));
            case "ask_phone_assistant": {
                String req = s(a, "request", null);
                return req == null ? "request is required."
                        : Commands.run(ctx, new Commands.Cmd("assist.open", 0, req));
            }
            default:
                return "Unknown tool: " + name;
        }
    }

    /** Running timers with time left, oldest first. */
    private static String timers(Context ctx) {
        JSONArray ts = Store.load(ctx).optJSONArray("timers");
        if (ts == null || ts.length() == 0) {
            return "No timers running.";
        }
        long now = System.currentTimeMillis() / 1000;
        StringBuilder sb = new StringBuilder("⏰ ");
        for (int k = 0; k < ts.length(); k++) {
            JSONObject t = ts.optJSONObject(k);
            if (t == null) {
                continue;
            }
            int left = (int) Math.max(0, Math.round(t.optDouble("fire_at", now) - now));
            if (k > 0) {
                sb.append(" · ");
            }
            sb.append(t.optString("label", "Timer")).append(": ")
              .append(Commands.span(left)).append(" left");
        }
        return sb.toString();
    }

    /**
     * Remove a timer before it fires. Store has no cancel of its own - timers
     * only ever left by firing - so this edits the array in place. With no
     * label the MOST RECENTLY SET timer goes, which is what "cancel the timer"
     * means right after setting one.
     */
    private static String cancelTimer(Context ctx, String label) {
        JSONObject d = Store.load(ctx);
        JSONArray ts = d.optJSONArray("timers");
        if (ts == null || ts.length() == 0) {
            return "No timers running.";
        }
        int hit = -1;
        double newest = -1;
        for (int k = 0; k < ts.length(); k++) {
            JSONObject t = ts.optJSONObject(k);
            if (t == null) {
                continue;
            }
            if (label != null) {
                if (t.optString("label", "").toLowerCase(Locale.US)
                        .contains(label.toLowerCase(Locale.US))) {
                    hit = k;
                    break;
                }
            } else if (t.optDouble("set_at", 0) > newest) {
                newest = t.optDouble("set_at", 0);
                hit = k;
            }
        }
        if (hit < 0) {
            return "No timer matching '" + label + "'.";
        }
        JSONObject victim = ts.optJSONObject(hit);
        String gone = victim.optString("label", "Timer");
        long dueMs = Math.round(victim.optDouble("fire_at", 0) * 1000.0);
        ts.remove(hit);
        Store.save(ctx, d);
        // Commands.run("timer") mirrors anything appointment-shaped into the
        // Jarvis calendar with its own alarm; take that copy out too, or the
        // phone still rings at the time. A no-op for short kitchen timers.
        Agenda.remove(ctx, gone, dueMs);
        return "⏰ Cancelled: " + gone + ".";
    }
}
