package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Unprompted pushes: fired timers, and a heads-up before a calendar event.
 * Port of proactive.py, run on a poll inside AssistantService.
 *
 * A fired timer is only removed once the card has actually been posted, so a
 * sleeping link means it retries on the next poll rather than losing the timer.
 */
public final class Proactive {

    /** The track last announced, so the same one is not re-posted every poll. */
    private static volatile String lastTrackKey;
    /** Whether the first observation has been taken as the baseline. */
    private static volatile boolean trackSeeded;
    /** When media first looked stopped; 0 while something is playing. */
    private static volatile long stoppedSince;
    private static volatile long lastMediaPoll;
    /**
     * Media state is read over the adb socket with `dumpsys media_session`,
     * which is far too expensive for the five-second heartbeat the calendar
     * mirror wants - and every extra read is another chance to catch a blip.
     */
    private static final long MEDIA_POLL_MS = 15_000;
    /**
     * How long media must stay stopped before a later play counts as a new
     * listening session worth announcing.
     */
    private static final long SESSION_OVER_MS = 5 * 60_000;
    /**
     * Recently announced tracks, so a title that flaps away and back does not
     * announce twice.
     *
     * A YouTube ad swaps the session metadata to the advert and back to the
     * video, which is two "changes" and was two cards - the same video named
     * again a minute later. The same flap happens when two paused sessions
     * (a video and a podcast) trade places in the dump while nothing plays.
     * Genuinely moving to something new is unaffected; only a return to
     * something just announced is suppressed.
     */
    private static final java.util.LinkedHashMap<String, Long> RECENT =
            new java.util.LinkedHashMap<String, Long>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
                    return size() > 8;
                }
            };
    private static final long RECENT_MS = 10 * 60_000;
    /**
     * When the assistant itself last started playback, and how long after that
     * the poll stays quiet and only re-baselines.
     *
     * Measured: "play X" through the voice session, and a moment later the
     * glasses showed the PREVIOUS YouTube video's title. The play card had
     * already said what was starting; then the 15 s poll read the media
     * sessions mid-switch, found the old video still reporting as the active
     * one, saw a key that was not the remembered track (it had been retired
     * by the 5-minute stop rule), and announced it as news. The seeding fix
     * below covers only the first poll after startup; this covers every play
     * the assistant starts. During the grace window whatever the sessions
     * show is silently adopted as the baseline - by the window's end that is
     * the new track, so it is neither announced twice nor mistaken for a
     * change.
     */
    private static volatile long playbackStartedAt;
    private static final long PLAY_GRACE_MS = 45_000;

    static void playbackStarted() {
        playbackStartedAt = System.currentTimeMillis();
    }

    private static synchronized boolean announcedRecently(String key) {
        Long at = RECENT.get(key);
        return at != null && System.currentTimeMillis() - at < RECENT_MS;
    }

    private static synchronized void rememberAnnounced(String key) {
        RECENT.put(key, System.currentTimeMillis());
    }

    private Proactive() {
    }

    /** One poll. Returns a short description of what it pushed, or null. */
    public static String poll(Context ctx) {
        // Keep the glasses' dashboard stocked with the next few real events.
        // Rate-limited inside; this is just the heartbeat it rides on.
        Mirror.tick(ctx);
        double now = System.currentTimeMillis() / 1000.0;
        String pushed = null;
        JSONObject d = Store.load(ctx);
        boolean dirty = false;
        try {
            // ---- timers ----------------------------------------------------
            JSONArray timers = d.getJSONArray("timers");
            JSONObject soonest = null;
            for (int i = timers.length() - 1; i >= 0; i--) {
                JSONObject t = timers.getJSONObject(i);
                double fireAt = t.optDouble("fire_at", Double.MAX_VALUE);
                if (fireAt <= now) {
                    String label = t.optString("label", "Timer");
                    Cards.post(ctx, Cards.title(ctx),
                            "⏰ Timer done" + ("Timer".equalsIgnoreCase(label) ? "" : ": " + label),
                            "timer");
                    timers.remove(i);
                    dirty = true;
                    pushed = "timer: " + label;
                } else if (soonest == null
                        || fireAt < soonest.optDouble("fire_at", Double.MAX_VALUE)) {
                    soonest = t;
                }
            }

            // ---- countdown --------------------------------------------------
            // Each repost re-renders on the glasses, so this is deliberately
            // restrained: only the soonest timer, only inside the final window,
            // and only when the card on screen is already ours - never stomping
            // an answer the user just asked for.
            AssistantService.diagPush = "timers=" + timers.length()
                    + " soonest=" + (soonest != null)
                    + " kind=" + Cards.lastKind;
            if (pushed == null && soonest != null
                    && Prefs.bool(ctx, Prefs.TIMER_PROGRESS, true)
                    && ("timer".equals(Cards.lastKind) || "progress".equals(Cards.lastKind))) {
                double remain = soonest.optDouble("fire_at") - now;
                double total = Math.max(1, soonest.optDouble("fire_at")
                        - soonest.optDouble("set_at", soonest.optDouble("fire_at") - remain));
                int window = Prefs.integer(ctx, Prefs.TIMER_PROGRESS_WINDOW, 300);
                if (remain > 0 && remain <= window) {
                    String label = soonest.optString("label", "Timer");
                    double done = Math.max(0, Math.min(1, 1 - remain / total));
                    Cards.post(ctx, Cards.title(ctx),
                            "⏳ " + Cards.bar(done, 10) + " " + Commands.span((int) Math.round(remain))
                            + " left" + ("Timer".equalsIgnoreCase(label) ? "" : " - " + label),
                            "progress");
                    pushed = "countdown: " + Math.round(remain) + "s";
                } else {
                    AssistantService.diagPush += " remain=" + Math.round(remain)
                            + " window=" + window;
                }
            }

            // ---- what is playing -------------------------------------------
            // A card when the track changes, so the glasses say what is on
            // without being asked. Two restraints matter: only when something is
            // actually PLAYING (a paused session is not news), and never over a
            // card posted in the last 15 seconds - an unprompted track name
            // wiping an answer the wearer is mid-read is worse than no card.
            if (pushed == null && Prefs.bool(ctx, Prefs.MEDIA_CARDS, true)
                    && System.currentTimeMillis() - lastMediaPoll >= MEDIA_POLL_MS) {
                lastMediaPoll = System.currentTimeMillis();
                Media.Track t = Media.current(ctx);
                if (t == null) {
                    // A FAILED OR EMPTY READ IS NOT A STATE CHANGE. This used to
                    // fall through and clear lastTrackKey, so one flaky
                    // `dumpsys media_session` over the adb socket made the video
                    // already on screen look new - and the same YouTube video
                    // was announced again every minute or so. Hold the last
                    // known state and wait for the next read.
                    AssistantService.diagPush += " media=unreadable";
                } else if (!trackSeeded) {
                    // First poll after starting: whatever is already playing is
                    // not news. Without this the service announces the track
                    // that happened to be loaded - which is how a fresh "play X"
                    // got answered with the PREVIOUS song, read from a session
                    // that had not switched over yet.
                    trackSeeded = true;
                    lastTrackKey = t.key();
                    stoppedSince = 0;
                } else if (System.currentTimeMillis() - playbackStartedAt < PLAY_GRACE_MS) {
                    // The assistant just started something itself and its own
                    // card already named it. Adopt whatever the sessions show
                    // as the baseline, silently - see playbackStartedAt.
                    lastTrackKey = t.key();
                    stoppedSince = 0;
                } else if (t.playing) {
                    stoppedSince = 0;
                    String key = t.key();
                    if (!key.equals(lastTrackKey)) {
                        boolean safe = ("media".equals(Cards.lastKind)
                                || Cards.msSinceLastCard() > 15_000)
                                && !announcedRecently(key);
                        if (safe) {
                            Cards.post(ctx, Cards.title(ctx), "♪ " + t.label(), "media");
                            rememberAnnounced(key);
                            pushed = "track: " + t.label();
                        }
                        // Remember it either way: a track skipped because an
                        // answer was on screen must not pop up later out of
                        // context, when it is no longer what changed.
                        lastTrackKey = key;
                    }
                } else {
                    // Paused. A pause is NOT a switch, so the track is
                    // remembered and resuming the same thing says nothing -
                    // pausing a video and starting it again should not announce
                    // it, and an ad break or a buffering stall must not either.
                    // Only a sustained stop retires the memory, so that picking
                    // something up hours later still counts as news.
                    if (stoppedSince == 0) {
                        stoppedSince = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - stoppedSince > SESSION_OVER_MS) {
                        lastTrackKey = null;
                    }
                }
            }

            // ---- calendar heads-up ----------------------------------------
            JSONObject notified = d.getJSONObject("notified");
            int lead = 10;                                  // minutes
            for (Cal.Event e : Cal.events(ctx, 12)) {
                if (e.allDay) {
                    continue;
                }
                double mins = (e.begin / 1000.0 - now) / 60.0;
                String key = e.begin + ":" + e.title;
                if (mins > 0 && mins <= lead && !notified.optBoolean(key, false)) {
                    // "push", not the default "answer": this arrives uninvited,
                    // which is what lets nav focus hold it back while driving.
                    Cards.post(ctx, Cards.title(ctx), "▦ " + e.title + " in "
                            + Math.round(mins) + " min (" + Cal.time(e.begin) + ")", "push");
                    notified.put(key, true);
                    dirty = true;
                    pushed = "calendar: " + e.title;
                }
                // ...and again AT the time. A heads-up ten minutes early is not a
                // reminder: it arrives while you are still doing something else,
                // and nothing marks the moment it was actually for.
                String atKey = "at:" + key;
                if (mins <= 0 && mins > -3 && !notified.optBoolean(atKey, false)) {
                    Cards.post(ctx, Cards.title(ctx), "▦ Now: " + e.title, "push");
                    notified.put(atKey, true);
                    dirty = true;
                    pushed = "calendar now: " + e.title;
                }
            }

            // prune keys for events that are well past
            if (notified.length() > 200) {
                d.put("notified", new JSONObject());
                dirty = true;
            }
        } catch (Exception ignored) {
        }
        if (dirty) {
            Store.save(ctx, d);
        }
        return pushed;
    }

    /** "3 open: buy milk; call plumber" - used by the "what are my to-dos" path. */
    public static String todoSummary(Context ctx) {
        List<String> open = Store.openTodos(ctx);
        if (open.isEmpty()) {
            return "✓ No open to-dos.";
        }
        return "✓ " + open.size() + " open: " + String.join("; ", open);
    }
}
