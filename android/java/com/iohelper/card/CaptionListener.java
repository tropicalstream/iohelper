package com.iohelper.card;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mirrors the phone's on-screen YouTube captions onto the glasses.
 *
 * Captions are DRAWN, not broadcast: there is no caption API, no notification,
 * nothing on the media session that carries the words. The only route to them
 * is the accessibility tree, which is where a screen reader would read them
 * from - so this is an AccessibilityService scoped to YouTube alone, and it
 * reads the subtitle view's text exactly as rendered. Confirmed on device: the
 * text lives under a node whose id contains "subtitle_window_identifier".
 *
 * That means it relays whatever YouTube already has on screen. If captions are
 * off, there is no text to find and nothing is sent; this never turns captions
 * on, and never invents them.
 *
 * Two things keep it from flooding the lens: a minimum gap between samples
 * (caption lines change faster than anyone can read a HUD), and a rule that a
 * caption never overwrites an answer the wearer just asked for.
 *
 * Enabling it takes a deliberate grant in Settings > Accessibility AND the
 * app's own switch - two separate gates, which is why {@link #probe()} reports
 * both. On Android 13+ a sideloaded build is additionally blocked until
 * "Restricted settings" is allowed for the app in App info; the toggle simply
 * does nothing until then, with no error.
 */
public class CaptionListener extends AccessibilityService {

    private static final String TAG = "iohelperCaps";
    private static final String YT = "com.google.android.youtube";
    /** Caption lines turn over every second or two - far faster than a card can
     *  be read on the lens, so they are sampled rather than followed. */
    private static final long MIN_GAP_MS = 1500;
    /** Don't stomp an answer the wearer just asked for with a caption. */
    private static final long ANSWER_GRACE_MS = 6000;

    /**
     * Ids YouTube has used for the subtitle view. These MOVE between releases,
     * so they are only a fast path - {@link #scan} finds it by shape when none
     * of them match, and CAPPROBE reports what is actually on screen.
     */
    private static final String[] KNOWN_IDS = {
        YT + ":id/subtitle_window",
        YT + ":id/subtitle_window_identifier",
        YT + ":id/player_video_subtitle_view",
        YT + ":id/subtitle_text",
        YT + ":id/caption_window",
    };

    /** The live service, so the CAPPROBE diagnostic can reach the node tree. */
    private static volatile CaptionListener instance;

    /** The bound service, for callers that need to read the screen. */
    static CaptionListener service() {
        return instance;
    }

    private volatile String lastCaption = "";
    private volatile long lastAt;

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "caption listener connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        // Gemini's screens are watched ONLY while a question is in flight -
        // Assist.pending() is false the rest of the time, so nothing on that
        // app is read unless the wearer just asked it something.
        if (Assist.pending()
                && (Assist.GEMINI_UI.contentEquals(pkg) || Assist.GEMINI_APP.contentEquals(pkg))) {
            Assist.onEvent(this);
            return;
        }
        if (!Prefs.bool(this, Prefs.CAPTIONS, false)) {
            return;                                  // off by default; user gate
        }
        if (!YT.contentEquals(pkg)) {
            return;                                  // captions: YouTube only
        }
        long now = System.currentTimeMillis();
        if (now - lastAt < MIN_GAP_MS) {
            return;                                  // sampled, not followed
        }
        // Stamp the SAMPLE, not the post. YouTube fires window-content events
        // several times a second, and most of them find no caption or an
        // unchanged one; stamping only on a successful post left those two
        // common cases completely unthrottled, running the whole tree walk -
        // dozens of binder round-trips - on every event.
        lastAt = now;
        if (!graceClear()) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        // packageNames in the service config filters which EVENTS arrive; it
        // does NOT restrict what getRootInActiveWindow returns. The active
        // window can be something else entirely by the time this runs - a
        // notification shade, another app, YouTube floating in picture-in-
        // picture over something else - so check whose tree this is before
        // reading a single node out of it.
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !YT.contentEquals(rootPkg)) {
            return;
        }
        try {
            String caption = captionOf(root);
            if (caption.isEmpty() || caption.equals(lastCaption)) {
                return;                              // captions off, or unchanged
            }
            // Re-check: the walk above takes real time, and an answer card
            // posted DURING it would otherwise be stomped the instant it
            // appeared, with none of the grace it is owed.
            if (!graceClear()) {
                return;
            }
            lastCaption = caption;
            Cards.post(this, Cards.title(this), caption, "caption");
        } catch (Exception e) {
            Log.w(TAG, "caption read failed: " + e);
        }
    }

    /**
     * Whether the display is free for a caption. An answer the wearer actually
     * asked for owns the glasses for a few seconds; a caption arriving on top of
     * it would wipe the thing they wanted.
     */
    private boolean graceClear() {
        return "caption".equals(Cards.lastKind) || Cards.msSinceLastCard() >= ANSWER_GRACE_MS;
    }

    /** The caption text currently on screen, or "" when there is none. */
    private String captionOf(AccessibilityNodeInfo root) {
        for (String id : KNOWN_IDS) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (AccessibilityNodeInfo n : hits) {
                    append(sb, textOf(n));
                }
                String s = Cards.sanitize(sb.toString());
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        scan(root, sb, 0);
        return Cards.sanitize(sb.toString());
    }

    /**
     * Find the subtitle view by SHAPE rather than by id: any node whose view id
     * mentions a subtitle or caption, and the text under it. Bounded in depth
     * because the player hierarchy is deep and this runs on every sampled
     * event.
     */
    private static void scan(AccessibilityNodeInfo n, StringBuilder out, int depth) {
        if (n == null || depth > 24 || out.length() > 300) {
            return;
        }
        String id = n.getViewIdResourceName();
        if (id != null) {
            String low = id.toLowerCase(Locale.US);
            if (low.contains("subtitle") || low.contains("caption")) {
                append(out, textOf(n));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            scan(n.getChild(i), out, depth + 1);
        }
    }

    /** A node's own text, or its children's when it is only a container. */
    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.getChildCount(); i++) {
            append(sb, textOf(n.getChild(i)));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(s.trim());
    }

    /**
     * What the accessibility tree actually holds right now, for finding the
     * caption node on a YouTube build whose ids have moved - and, just as
     * often, for telling apart the two separate gates. Reports the id and the
     * OPENING of each text node, enough to recognise the caption line without
     * transcribing the screen.
     */
    static String probe() {
        CaptionListener s = instance;
        if (s == null) {
            return "Captions: accessibility access is not granted";
        }
        // Report the GATES first. Accessibility access and the app's own switch
        // are two separate grants, and a caption that reads perfectly here while
        // the switch is off looks identical, from the glasses, to one that
        // cannot be read at all.
        boolean on = Prefs.bool(s, Prefs.CAPTIONS, false);
        Log.i(TAG, "probe: captions.relay=" + on);
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) {
            return "Captions: nothing readable in the active window";
        }
        CharSequence rp = root.getPackageName();
        Log.i(TAG, "probe: activeWindow=" + (rp == null ? "?" : rp)
                + (YT.contentEquals(rp == null ? "" : rp) ? "" : " (NOT YouTube)"));
        // The node dump runs regardless of the captions switch: this is an
        // explicit diagnostic, and "the switch is off" is the one thing it
        // exists to tell you apart from "nothing is readable here".
        List<String> rows = new ArrayList<>();
        collect(root, rows, 0);
        for (String r : rows) {
            Log.i(TAG, "probe: " + r);
        }
        String caption = s.captionOf(root);
        Log.i(TAG, "probe: captionOf -> " + (caption.isEmpty() ? "(nothing)" : caption));
        return "Captions: " + rows.size() + " text nodes, "
                + (caption.isEmpty() ? "no caption found" : "caption found")
                + (on ? "" : " (switch OFF)");
    }

    private static void collect(AccessibilityNodeInfo n, List<String> rows, int depth) {
        if (n == null || depth > 24 || rows.size() > 60) {
            return;
        }
        // Clickable controls too, by content description: a button carries no
        // text, so a text-only dump cannot find the one control this feature
        // has to press.
        CharSequence cd = n.getContentDescription();
        if (cd != null && cd.length() > 0) {
            // Compose surfaces advertise a click ACTION rather than setting the
            // clickable flag, so isClickable() alone misses their buttons.
            boolean act = false;
            for (AccessibilityNodeInfo.AccessibilityAction x : n.getActionList()) {
                if (x.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                    act = true;
                    break;
                }
            }
            String id = n.getViewIdResourceName();
            rows.add("[" + (n.isClickable() ? "C" : "-") + (act ? "A" : "-") + "] "
                    + (id == null ? "(no id)" : id) + " ~ "
                    + cd.toString().replaceAll("\s+", " ").trim());
        }
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            String id = n.getViewIdResourceName();
            String head = t.toString().replaceAll("\\s+", " ").trim();
            if (head.length() > 40) {
                head = head.substring(0, 40) + "…";
            }
            rows.add((id == null ? "(no id)" : id) + " = " + head);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), rows, depth + 1);
        }
    }
}
