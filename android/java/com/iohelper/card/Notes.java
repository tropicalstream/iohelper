package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Notes the assistant owns, and can therefore answer questions about.
 *
 * WHY NOT GOOGLE KEEP OR SAMSUNG NOTES. Neither exposes a content provider a
 * third-party app may read - Keep has no public API outside Workspace, and
 * Samsung Notes keeps its database private. So a note dictated to the glasses
 * could be filed into one of those apps only by launching it, and could never
 * be read back. Read-back is the entire point: the reason for moving this into
 * the assistant rather than leaving it to RayNeo is that RayNeo can display
 * your data but cannot answer a question about it.
 *
 * Notes therefore live in the same store as to-dos and timers, next to the
 * calendar the assistant reads straight out of CalendarContract. That gives one
 * place that holds everything and one prompt that can see all of it.
 */
public final class Notes {

    /** Kept small on purpose: the whole set is pasted into every LLM prompt. */
    private static final int CONTEXT_MAX = 12;
    private static final int SNIPPET = 160;

    private Notes() {
    }

    public static JSONObject add(Context ctx, String text) throws Exception {
        JSONObject d = Store.load(ctx);
        JSONObject n = new JSONObject()
                .put("id", "n" + (System.currentTimeMillis() % 100000000L))
                .put("text", text.trim())
                .put("created", System.currentTimeMillis() / 1000.0);
        d.getJSONArray("notes").put(n);
        Store.save(ctx, d);
        return n;
    }

    /** Newest first. */
    public static List<JSONObject> all(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = Store.load(ctx).getJSONArray("notes");
            for (int i = a.length() - 1; i >= 0; i--) {
                out.add(a.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Notes whose text contains every word of the query. Deliberately dumb:
     * the LLM does the understanding, this only narrows what it is shown.
     */
    public static List<JSONObject> search(Context ctx, String query) {
        List<JSONObject> out = new ArrayList<>();
        String[] words = query == null ? new String[0]
                : query.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        for (JSONObject n : all(ctx)) {
            String t = n.optString("text").toLowerCase();
            boolean hit = words.length > 0;
            for (String w : words) {
                if (w.length() > 2 && !t.contains(w)) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                out.add(n);
            }
        }
        return out;
    }

    /** One line per note for the glasses; the card clips it. */
    public static String line(Context ctx, String query) {
        List<JSONObject> hits = query == null || query.trim().isEmpty()
                ? all(ctx) : search(ctx, query);
        if (hits.isEmpty()) {
            return all(ctx).isEmpty() ? "▤ No notes yet." : "▤ Nothing matching that.";
        }
        StringBuilder sb = new StringBuilder("▤ ");
        for (int i = 0; i < hits.size() && i < 8; i++) {
            sb.append(i == 0 ? "" : " · ").append(hits.get(i).optString("text"));
        }
        return sb.toString();
    }

    /**
     * Delete the notes matching a phrase, returning how many went.
     *
     * A notes store with no delete is not a notes store: a note that turns out
     * to be wrong is worse than no note, because the assistant will keep
     * stating it as fact. Matching is the same dumb contains-every-word as
     * search, and the caller reports the count, so a phrase that hits more than
     * intended is at least visible rather than silent.
     */
    public static int remove(Context ctx, String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return 0;
        }
        List<JSONObject> doomed = search(ctx, query);
        if (doomed.isEmpty()) {
            return 0;
        }
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (JSONObject n : doomed) {
            ids.add(n.optString("id"));
        }
        JSONObject d = Store.load(ctx);
        JSONArray kept = new JSONArray();
        JSONArray a = d.getJSONArray("notes");
        for (int i = 0; i < a.length(); i++) {
            if (!ids.contains(a.getJSONObject(i).optString("id"))) {
                kept.put(a.getJSONObject(i));
            }
        }
        d.put("notes", kept);
        Store.save(ctx, d);
        return ids.size();
    }

    /** The block handed to the LLM, so it can reason over notes like anything else. */
    public static String snapshot(Context ctx) {
        List<JSONObject> notes = all(ctx);
        if (notes.isEmpty()) {
            return "";
        }
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM", Locale.US);
        StringBuilder sb = new StringBuilder("[Notes, newest first]\n");
        for (int i = 0; i < notes.size() && i < CONTEXT_MAX; i++) {
            JSONObject n = notes.get(i);
            String text = n.optString("text");
            if (text.length() > SNIPPET) {
                text = text.substring(0, SNIPPET) + "…";
            }
            sb.append("- ")
              .append(when.format(new Date((long) (n.optDouble("created") * 1000))))
              .append(": ").append(text).append('\n');
        }
        if (notes.size() > CONTEXT_MAX) {
            sb.append("- (+").append(notes.size() - CONTEXT_MAX).append(" older)\n");
        }
        return sb.toString().trim();
    }
}
