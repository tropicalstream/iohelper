package com.iohelper.card;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * The phone's media sessions, reached through the real framework API rather
 * than by parsing `dumpsys media_session`.
 *
 * This is possible only because NavListener is an ENABLED
 * NotificationListenerService: getActiveSessions() takes the component name of
 * one as proof of permission, and refuses anything else. iohelper already holds
 * that grant for the navigation relay, so nothing new is asked of the wearer.
 *
 * Why it matters beyond tidiness: the dumpsys text says what a player IS doing,
 * but a MediaController can TELL it to do something - including
 * playFromSearch(), which is how Android Auto and Assistant start a named
 * podcast. There is no shell equivalent, and the activity intent that looks
 * like one (ACTION_MEDIA_PLAY_FROM_SEARCH) only opens the app: measured against
 * Pocket Casts, it left the session in state=ERROR(7) with the app's main
 * screen showing and nothing playing.
 */
public final class Sessions {

    private static final String TAG = "iohelperMedia";

    /** Pocket Casts. Its session advertises PLAY_FROM_SEARCH, which is the point. */
    static final String POCKETCASTS = "au.com.shiftyjelly.pocketcasts";

    private Sessions() {
    }

    /**
     * Every active session, or an empty list if the grant is missing.
     *
     * A SecurityException here means notification access was revoked - a real
     * possibility, since reinstalling the app has dropped it before. It is not
     * worth crashing a media command over; the callers all fall back to the
     * keyevent path, which needs no permission at all.
     */
    private static List<MediaController> all(Context ctx) {
        try {
            MediaSessionManager m = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (m == null) {
                return Collections.emptyList();
            }
            return m.getActiveSessions(new ComponentName(ctx, NavListener.class));
        } catch (SecurityException e) {
            Log.i(TAG, "media sessions need notification access: " + e);
            return Collections.emptyList();
        } catch (Exception e) {
            Log.i(TAG, "getActiveSessions failed: " + e);
            return Collections.emptyList();
        }
    }

    /**
     * Whether an app is the session a media key would reach.
     *
     * getActiveSessions() returns controllers in decreasing priority, so the
     * first is the one holding the media buttons. This is the right question to
     * ask before substituting a transport control for a key press: "is it
     * playing" is not, because a PAUSED podcast still owns the keys, and
     * answering no there sent "skip" down the keyevent path to an app that
     * ignores it while the card said it had worked.
     */
    static boolean ownsMediaKeys(Context ctx, String pkg) {
        List<MediaController> l = all(ctx);
        return !l.isEmpty() && pkg.equals(l.get(0).getPackageName());
    }

    /** The controller for one app, or null if it holds no session right now. */
    static MediaController find(Context ctx, String pkg) {
        for (MediaController c : all(ctx)) {
            if (pkg.equals(c.getPackageName())) {
                return c;
            }
        }
        return null;
    }

    /** Whether an app's own session says it is playing (or buffering towards it). */
    static boolean isPlaying(Context ctx, String pkg) {
        return isPlaying(find(ctx, pkg));
    }

    /** The same question of a controller already in hand - one lookup, not two. */
    static boolean isPlaying(MediaController c) {
        return c != null && playing(c.getPlaybackState());
    }

    private static boolean playing(PlaybackState s) {
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    /** Whether a session supports an action, from its own advertised mask. */
    static boolean supports(MediaController c, long action) {
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s != null && (s.getActions() & action) != 0;
    }

    /**
     * Ask an app to find something and play it.
     *
     * Returns false if the app holds no session or does not advertise
     * PLAY_FROM_SEARCH - saying so is better than firing a control into the void
     * and reporting success, which is what the activity-intent route did.
     */
    static boolean playFromSearch(Context ctx, String pkg, String query) {
        MediaController c = find(ctx, pkg);
        if (c == null) {
            Log.i(TAG, pkg + " holds no media session");
            return false;
        }
        if (!supports(c, PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
            Log.i(TAG, pkg + " does not advertise PLAY_FROM_SEARCH");
            return false;
        }
        c.getTransportControls().playFromSearch(query, null);
        return true;
    }

    /** What an app is showing as its current item, "episode — show". */
    static String nowPlaying(Context ctx, String pkg) {
        MediaController c = find(ctx, pkg);
        MediaMetadata m = c == null ? null : c.getMetadata();
        if (m == null) {
            return null;
        }
        String title = text(m, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
        if (title == null) {
            return null;
        }
        return artist == null || artist.isEmpty() ? title : title + " — " + artist;
    }

    private static String text(MediaMetadata m, String key) {
        CharSequence cs = m.getText(key);
        return cs == null ? null : cs.toString().trim();
    }
}
