package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SerpApi live data. Port of search.py, keeping the routing rules that were
 * tuned against real usage:
 *  - personal-calendar questions never spend a search;
 *  - a general google search only fires on strong live cues, not bare time words;
 *  - the destination is the text after the LAST "to" ("how long to get to X");
 *  - directions retry with the origin's region and then fall back to the plain
 *    google engine, because the maps engine returns "Fully empty" for some
 *    ordinary destinations (measured: "San Francisco" failed while "San Jose" worked).
 */
public final class Search {

    private static final String ENDPOINT = "https://serpapi.com/search.json";
    public static String lastError;

    private Search() {
    }

    public static boolean available(Context c) {
        return !Prefs.str(c, Prefs.SERPAPI_KEY, "").isEmpty();
    }

    private static JSONObject call(Context ctx, String params, int timeoutMs) {
        String key = Prefs.str(ctx, Prefs.SERPAPI_KEY, "");
        if (key.isEmpty()) {
            lastError = "no SerpApi key";
            return null;
        }
        try {
            URL url = new URL(ENDPOINT + "?" + params + "&api_key=" + key + "&output=json");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "iohelper");
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; is != null && (n = is.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
            }
            if (is != null) {
                is.close();
            }
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            // HTTP 200 can still carry an error in the body (the maps engine's
            // "Google hasn't returned any results for this query.")
            lastError = o.has("error") ? o.optString("error") : null;
            return o;
        } catch (Exception e) {
            lastError = e.toString();
            return null;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A schedule question with no possessive in it.
     *
     * "List the events happening tomorrow" was routed to a LOCAL EVENTS web
     * search and answered with a public protest and its venue - because the
     * personal-calendar guard demanded "my" or "our", and this has neither.
     * The user has a calendar loaded; on a device that knows your diary, a bare
     * "the events tomorrow" means yours.
     *
     * Only when nothing marks it as a question about the town. Someone asking
     * for concerts, festivals, gigs, things to do, or anything near them wants
     * the web, and that stays a search.
     */
    private static boolean ownDiary(String low) {
        boolean scheduleWord = low.matches(
                ".*\\b(events?|schedule|agenda|meetings?|appointments?|calendar)\\b.*");
        boolean whenWord = low.matches(".*\\b(today|tonight|tomorrow|this (morning|afternoon"
                + "|evening|week)|next week|monday|tuesday|wednesday|thursday|friday"
                + "|saturday|sunday|later|coming up|upcoming)\\b.*");
        boolean outThere = low.matches(".*\\b(concerts?|gigs?|festivals?|shows?|things to do"
                + "|near ?(me|by)|nearby|around here|downtown|in town|tickets?|venue"
                + "|live music|open mic|game|match)\\b.*")
                || low.matches(".*\\bin [a-z]+\\b.*");
        return scheduleWord && whenWord && !outThere;
    }

    // ---- intent routing ----------------------------------------------------
    public static String classify(String utterance) {
        String low = utterance.toLowerCase().trim();
        // personal calendar - already answered from context; never spend a search
        if (low.matches(".*\\b(my|our)\\b.{0,20}\\b(schedule|calendar|agenda|events?|meetings?|appointments?|plans?)\\b.*")
                || low.matches(".*\\b(my|the) (schedule|calendar|agenda)\\b.*")
                || low.matches(".*(\\bdo i have\\b|\\bam i (free|busy|available|around)\\b|\\bwhat do i have\\b|\\bwhat am i doing\\b|\\banything (on|planned|going on)\\b).*")
                || ownDiary(low)) {
            return null;
        }
        if (low.matches(".*\\b(directions?|routes?|(fastest|quickest|best) way|how (long|far)|drive|driving|commute|traffic|travel time|bike|biking|walk|walking|transit|bart|bus)\\b.*")
                && low.contains("to")) {
            return "directions";
        }
        if (low.matches(".*\\b(concerts?|shows?|things to do|what'?s (happening|on|going on))\\b.*")
                || low.matches(".*\\bevents?\\b.*")) {
            return "events";
        }
        if (low.matches(".*\\b(news|headlines?|latest on|breaking)\\b.*")) {
            return "news";
        }
        if (low.matches(".*\\b(near me|nearby|nearest|closest|around here)\\b.*")) {
            return "local";
        }
        if (low.matches(".*\\b(weather|forecast|temperature|rain|snow|humidity|wind|umbrella|how (hot|cold|warm))\\b.*")) {
            return "weather";
        }
        if (low.matches(".*\\bwhen (do|does|is|are)\\b.{0,30}\\b(open|close|closes|closing)\\b.*")
                || low.matches(".*\\bis\\b.{0,30}\\bopen\\b.*")
                || low.matches(".*\\bwhat time\\b.{0,30}\\b(open|close|closes)\\b.*")) {
            return "web";
        }
        if (low.matches(".*\\b(scores?|who won|who'?s winning|winning|standings|final score|price|prices|stock|stocks|shares|market cap|trading|exchange rate|time in|what time is it)\\b.*")) {
            return "web";
        }
        // Other things people actually ask a voice assistant, each of which the
        // model will answer from stale memory if it is not looked up.
        if (low.matches(".*\\b(flight|delayed|departure|arrival|gate)\\b.*")
                || low.matches(".*\\b(showtimes?|playing at|box office|now in theate?rs)\\b.*")
                || low.matches(".*\\b(recipe|how do i (make|cook|bake))\\b.*")
                || low.matches(".*\\b(population|how (tall|old|big|far|deep|heavy|much|many))\\b.*")
                || low.matches(".*\\b(who (is|was|are|were)|what (is|was) the)\\b.*")
                || low.matches(".*\\b(release date|comes out|when (did|does|is|was))\\b.*")) {
            return "web";
        }
        // FALLBACK. Everything above is a named intent; without this, anything
        // unlisted got no search at all and was answered from the model's own
        // memory - confidently, and out of date. A question about the world is
        // worth a lookup; a question about the USER, or a request to make
        // something up, is not.
        if (QUESTION_SHAPED.matcher(low).find() && !NEVER_SEARCH.matcher(low).find()) {
            return "web";
        }
        return null;
    }

    /** Opens like a question, or ends in a question mark. */
    private static final java.util.regex.Pattern QUESTION_SHAPED =
            java.util.regex.Pattern.compile(
                    "^(who|what|what'?s|when|where|which|why|how|is|are|was|were|does|do|did"
                    + "|can|could|has|have|will|should)\\b|\\?\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    /**
     * Questions a search cannot help with: arithmetic and translation the model
     * does better itself, anything creative, anything about the assistant, and
     * the command verbs that should never have reached this far anyway.
     */
    private static final java.util.regex.Pattern NEVER_SEARCH =
            java.util.regex.Pattern.compile(
                    "\\b(joke|poem|story|write|draft|rewrite|summari[sz]e|translate|spell"
                    + "|say .* in (spanish|french|german|italian|japanese|chinese)"
                    + "|convert|calculate|percent of|plus|minus|divided|times"
                    + "|how are you|who are you|what can you do|your name"
                    + "|remind|timer|countdown|to-?do|task|play|pause|skip|volume)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Parse (origin, destination, mode) out of a commute question. */
    static String[] originDestMode(String utterance) {
        String u = utterance;
        String low = u.toLowerCase();
        String mode = "best";
        for (String k : new String[]{"driving", "drive", "car", "transit", "bart", "subway",
                "bus", "train", "walking", "walk", "cycling", "biking", "bike", "traffic"}) {
            if (low.matches(".*\\b" + k + "\\b.*")) {
                mode = (k.equals("car") || k.equals("traffic")) ? "drive" : k;
                break;
            }
        }
        u = u.replaceAll("(?i)\\s+by\\s+(car|driving|drive|transit|bus|train|bart|subway|walking|walk|cycling|biking|bike)\\b.*$", "");
        String origin = null;
        Matcher mo = Pattern.compile("(?i)\\bfrom\\s+(.+?)\\s+to\\b").matcher(u);
        if (mo.find()) {
            origin = mo.group(1).replaceAll("^[\\s?.,]+|[\\s?.,]+$", "");
        }
        String[] parts = u.split("(?i)\\bto\\b");
        String dest = parts.length > 1
                ? parts[parts.length - 1].replaceAll("^[\\s?.,]+|[\\s?.,]+$", "") : null;
        return new String[]{origin, dest, mode};
    }

    // ---- engines -----------------------------------------------------------
    private static String webAnswer(Context ctx, String query, String location) {
        String p = "engine=google&q=" + enc(query) + "&hl=en&gl=us"
                + (location != null && !location.isEmpty() ? "&location=" + enc(location) : "");
        JSONObject d = call(ctx, p, 20000);
        if (d == null || d.has("error")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"answer_box", "knowledge_graph", "sports_results"}) {
            JSONObject v = d.optJSONObject(k);
            if (v != null) {
                String s = v.toString();
                sb.append(k).append(": ").append(s.substring(0, Math.min(420, s.length()))).append("\n");
            }
        }
        JSONArray org = d.optJSONArray("organic_results");
        for (int i = 0; org != null && i < Math.min(3, org.length()); i++) {
            JSONObject r = org.optJSONObject(i);
            if (r != null && r.has("title") && r.has("snippet")) {
                sb.append("- ").append(r.optString("title")).append(": ")
                  .append(r.optString("snippet")).append("\n");
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out.substring(0, Math.min(750, out.length()));
    }

    private static String directions(Context ctx, String dest, String origin, String mode) {
        int tm = 6;
        switch (mode) {
            case "drive": case "driving": case "car": tm = 0; break;
            case "bike": case "biking": case "cycling": tm = 1; break;
            case "walk": case "walking": tm = 2; break;
            case "transit": case "bus": case "bart": case "train": case "subway": tm = 3; break;
            default: tm = 6;
        }
        JSONObject d = call(ctx, "engine=google_maps_directions&start_addr=" + enc(origin)
                + "&end_addr=" + enc(dest) + "&travel_mode=" + tm, 20000);
        if (d == null || d.has("error")) {
            return null;
        }
        JSONArray dirs = d.optJSONArray("directions");
        for (int i = 0; dirs != null && i < dirs.length(); i++) {
            JSONObject row = dirs.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String dur = row.optString("formatted_duration", "");
            String dist = row.optString("formatted_distance", "");
            String via = row.optString("via", "");
            if (dur.isEmpty()) {
                JSONArray trips = row.optJSONArray("trips");
                if (trips != null && trips.length() > 0) {
                    JSONObject t = trips.optJSONObject(0);
                    dur = t == null ? "" : t.optString("formatted_duration", "");
                    dist = t == null ? "" : t.optString("formatted_distance", "");
                }
            }
            if (!dur.isEmpty()) {
                return origin + " -> " + dest + ": " + dur
                        + (dist.isEmpty() ? "" : " (" + dist + ")")
                        + (via.isEmpty() ? "" : " via " + via) + " by " + mode;
            }
        }
        return null;
    }

    private static String simpleList(Context ctx, String params, String arrayKey,
                                     String titleKey, int max) {
        JSONObject d = call(ctx, params, 20000);
        if (d == null || d.has("error")) {
            return null;
        }
        JSONArray a = d.optJSONArray(arrayKey);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < Math.min(max, a.length()); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || r.optString(titleKey, "").isEmpty()) {
                continue;
            }
            sb.append("- ").append(r.optString(titleKey));
            JSONObject src = r.optJSONObject("source");
            if (src != null) {
                sb.append(" (").append(src.optString("name", "")).append(")");
            }
            String addr = r.optString("address", "");
            if (!addr.isEmpty()) {
                sb.append(" ").append(addr);
            }
            sb.append("\n");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /** The entry point: a short context block, or "" when no search is wanted. */
    public static String context(Context ctx, String utterance) {
        if (!available(ctx) || "off".equals(Prefs.str(ctx, Prefs.SEARCH_MODE, "auto"))) {
            return "";
        }
        // The phone's actual position when it has one, the typed-in city when it
        // does not: on glasses, a typed city is wrong the moment you walk away.
        String location = Loc.searchLocation(ctx);
        String intent = "always".equals(Prefs.str(ctx, Prefs.SEARCH_MODE, "auto"))
                ? "web" : classify(utterance);
        if (intent == null) {
            return "";
        }
        String res = null;
        try {
            switch (intent) {
                case "directions": {
                    String[] odm = originDestMode(utterance);
                    // Coordinates beat a city name for a route: "how long is the
                    // drive" depends on which side of town you are standing on.
                    String origin = odm[0] != null ? odm[0] : Loc.origin(ctx);
                    if (odm[1] != null && origin != null && !origin.isEmpty()) {
                        res = directions(ctx, odm[1], origin, odm[2]);
                        if (res == null && !odm[1].contains(",") && origin.contains(",")) {
                            res = directions(ctx, odm[1] + ", "
                                    + origin.substring(origin.indexOf(',') + 1).trim(), origin, odm[2]);
                        }
                        if (res == null) {
                            res = webAnswer(ctx, "driving time from " + origin + " to " + odm[1]
                                    + " with current traffic", location);
                        }
                    }
                    break;
                }
                case "events":
                    res = webAnswer(ctx, utterance, location);
                    break;
                case "news":
                    res = simpleList(ctx, "engine=google_news&gl=us&hl=en&q="
                            + enc(utterance.replaceAll("(?i)\\b(what'?s the|news|headlines?|latest on|latest|about)\\b", " ").trim()),
                            "news_results", "title", 5);
                    break;
                case "local":
                    res = simpleList(ctx, "engine=google_local&hl=en&gl=us&q=" + enc(utterance)
                            + (location.isEmpty() ? "" : "&location=" + enc(location)),
                            "local_results", "title", 5);
                    break;
                default:
                    res = webAnswer(ctx, utterance, location);
            }
        } catch (Exception e) {
            return "";
        }
        // Per-intent guidance. The default system prompt asks for under 90
        // characters and no lists, which is right for most answers but makes the
        // model drop exactly the useful part of a local result: five restaurant
        // names fit, one name WITH its street address does not. Say which to keep.
        String guide = "";
        if ("local".equals(intent)) {
            guide = "Answer with the SINGLE best match and its STREET ADDRESS "
                    + "(up to 110 characters). The address is the point.\n";
        } else if ("directions".equals(intent)) {
            // The result is a SUMMARY only - time, distance, and one "via" road.
            // The model has NO turn-by-turn steps, so it must not invent a
            // sequence of streets: doing so produced confident directions down a
            // street ("55th St") that is nowhere near the actual route.
            guide = "Report ONLY the travel time, the distance, and the single "
                    + "'via' road named in the result line - as one short line, "
                    + "e.g. \"12 min walk via Monterey Blvd\". You do NOT have "
                    + "turn-by-turn directions: never invent a sequence of streets "
                    + "or turns, and never name a street or road that is not in the "
                    + "result line (up to 110 characters).\n";
        }
        return res == null ? ""
                : "Live search results (SerpApi - use only if relevant, be concise):\n"
                  + guide + res;
    }
}
