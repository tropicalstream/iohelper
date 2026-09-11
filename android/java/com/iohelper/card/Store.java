package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Timers and to-dos, persisted as JSON in the app's private files dir.
 * Port of the store half of commands.py. Written atomically (tmp + rename) so a
 * kill mid-write cannot leave a half-file that loses every timer.
 */
public final class Store {

    private static final String FILE = "store.json";

    private Store() {
    }

    public static synchronized JSONObject load(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (f.exists()) {
                RandomAccessFile r = new RandomAccessFile(f, "r");
                byte[] b = new byte[(int) r.length()];
                r.readFully(b);
                r.close();
                JSONObject o = new JSONObject(new String(b, "UTF-8"));
                if (!o.has("timers")) {
                    o.put("timers", new JSONArray());
                }
                if (!o.has("todos")) {
                    o.put("todos", new JSONArray());
                }
                if (!o.has("notified")) {
                    o.put("notified", new JSONObject());
                }
                if (!o.has("notes")) {
                    o.put("notes", new JSONArray());
                }
                if (!o.has("dismissed")) {
                    o.put("dismissed", new JSONObject());
                }
                return o;
            }
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("timers", new JSONArray())
                    .put("todos", new JSONArray()).put("notes", new JSONArray())
                    .put("dismissed", new JSONObject())
                    .put("notified", new JSONObject());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static synchronized void save(Context ctx, JSONObject d) {
        try {
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            os.write(d.toString(2).getBytes("UTF-8"));
            os.close();
            tmp.renameTo(new File(ctx.getFilesDir(), FILE));
        } catch (Exception ignored) {
        }
    }

    private static String newId(String prefix) {
        return prefix + (System.currentTimeMillis() % 100000000L);
    }

    public static JSONObject addTimer(Context ctx, int seconds, String label) throws Exception {
        JSONObject d = load(ctx);
        JSONObject t = new JSONObject()
                .put("id", newId("t"))
                .put("label", label == null || label.trim().isEmpty() ? "Timer" : label.trim())
                .put("set_at", System.currentTimeMillis() / 1000.0)
                .put("fire_at", System.currentTimeMillis() / 1000.0 + seconds)
                .put("notified", false);
        d.getJSONArray("timers").put(t);
        save(ctx, d);
        return t;
    }

    public static JSONObject addTodo(Context ctx, String text) throws Exception {
        JSONObject d = load(ctx);
        // Don't stack duplicates: "add buy milk" twice is one to-do, not two.
        // (The card was showing "buy milk" twice for exactly this reason.)
        String want = text.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
        JSONArray existing = d.getJSONArray("todos");
        for (int i = 0; i < existing.length(); i++) {
            JSONObject e = existing.getJSONObject(i);
            if (!e.optBoolean("done")
                    && e.optString("text").trim().toLowerCase()
                        .replaceAll("[^a-z0-9]", "").equals(want)) {
                return e;                       // already on the list
            }
        }
        JSONObject t = new JSONObject()
                .put("id", newId("d"))
                .put("text", text.trim())
                .put("done", false)
                .put("created", System.currentTimeMillis() / 1000.0);
        d.getJSONArray("todos").put(t);
        save(ctx, d);
        return t;
    }

    /** Mark the first open to-do whose text contains `match`. Null if none. */
    public static JSONObject completeTodo(Context ctx, String match) throws Exception {
        JSONObject d = load(ctx);
        JSONArray todos = d.getJSONArray("todos");
        String m = match.toLowerCase().trim();
        for (int i = 0; i < todos.length(); i++) {
            JSONObject t = todos.getJSONObject(i);
            if (!t.optBoolean("done")
                    && (t.optString("text").toLowerCase().contains(m) || m.equals(t.optString("id")))) {
                t.put("done", true);
                save(ctx, d);
                return t;
            }
        }
        return null;
    }

    /**
     * The to-do list as a block for the LLM.
     *
     * Without this the assistant could add to-dos and list them back, but could
     * not REASON about them - "is there anything I can do before my 3 o'clock"
     * had nothing to work from. The calendar has been in the prompt all along;
     * this puts the app's own data on the same footing.
     */
    public static String todoSnapshot(Context ctx) {
        java.util.List<String> open = openTodos(ctx);
        if (open.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[To-do list, " + open.size() + " open]\n");
        for (int i = 0; i < open.size() && i < 20; i++) {
            sb.append("- ").append(open.get(i)).append('\n');
        }
        if (open.size() > 20) {
            sb.append("- (+").append(open.size() - 20).append(" more)\n");
        }
        return sb.toString().trim();
    }

    /**
     * Open to-dos as objects, OLDEST first - id, text and created are all
     * needed, and the order is the point.
     *
     * The card holds only a few, and taking the newest would mean a to-do
     * already on the glasses gets pushed off by a newer one - reintroducing
     * exactly the disappearance the whole persistent-to-do idea exists to stop.
     * Oldest first means anything shown stays shown until it is done, and the
     * next one takes its place when it goes.
     */
    /**
     * Cards the user has finished with, and when they may be forgotten.
     *
     * A mirrored event is a COPY of something in a real calendar, so dismissing
     * it must not delete the original - the entry is removed from the glasses
     * and remembered here, or the very next mirror pass would put it straight
     * back. The note expires when the event would have left the card anyway,
     * which keeps this from growing without bound.
     */
    public static synchronized void dismiss(Context ctx, String key, long untilMs) {
        try {
            JSONObject d = load(ctx);
            d.getJSONObject("dismissed").put(key, untilMs);
            save(ctx, d);
        } catch (Exception ignored) {
        }
    }

    /** Keys still dismissed, pruning any whose moment has passed. */
    public static synchronized java.util.Set<String> dismissed(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONObject d = load(ctx);
            JSONObject m = d.getJSONObject("dismissed");
            long now = System.currentTimeMillis();
            boolean pruned = false;
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                if (m.optLong(k) > now) {
                    out.add(k);
                } else {
                    it.remove();
                    pruned = true;
                }
            }
            if (pruned) {
                save(ctx, d);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static java.util.List<JSONObject> openTodoItems(Context ctx) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            JSONArray a = load(ctx).getJSONArray("todos");
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                if (!t.optBoolean("done")) {
                    out.add(t);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static java.util.List<String> openTodos(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray a = load(ctx).getJSONArray("todos");
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                if (!t.optBoolean("done")) {
                    out.add(t.optString("text"));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
