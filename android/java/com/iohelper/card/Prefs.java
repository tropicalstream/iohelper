package com.iohelper.card;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Settings, mirroring the PC build's config.json. SharedPreferences-backed. */
public final class Prefs {

    private static final String FILE = "iohelper";

    public static final String WAKE_TRIGGER = "wake.trigger";
    public static final String WAKE_MANGLES = "wake.soft_mangles";
    public static final String WAKE_SOURCE = "wake.source";
    public static final String GROQ_KEY = "llm.groq_api_key";
    public static final String GROQ_MODEL = "llm.groq_model";
    public static final String GEMINI_KEY = "llm.gemini_api_key";
    public static final String GEMINI_MODEL = "llm.gemini_model";
    /** "groq" (default), "openai" or "gemini". */
    public static final String BACKEND = "llm.backend";
    /** OpenAI platform key - one key serves the text models here and the voice
     *  model a later front end will use. */
    public static final String OPENAI_KEY = "llm.openai_api_key";
    /** Default gpt-5.6-luna: function calling at a fifth of a cent per million
     *  input tokens, which makes an assistant turn cost about a tenth of a cent. */
    public static final String OPENAI_MODEL = "llm.openai_model";
    public static final String OPENAI_BASE = "llm.openai_base_url";
    /** Let the model ACT through tools (timers, lists, calendar, music, maps)
     *  rather than only answer. Default on; off restores the plain Q&A path. */
    public static final String TOOLS = "llm.tools";
    public static final String SERPAPI_KEY = "search.serpapi_key";
    public static final String SEARCH_LOCATION = "search.location";
    public static final String SEARCH_MODE = "search.mode";
    public static final String BODY_LIMIT = "device.body_limit";
    /** Characters the glasses render on the TITLE line - the second of the three
     *  they draw. Measured 2026-09-06 with an on-lens ruler: ~59. */
    public static final String TITLE_LIMIT = "device.title_limit";
    public static final String DEFER_NATIVE = "display.defer_after_native";
    /** How long a card stays in the PHONE's notification shade before it is
     *  taken back out, in ms. The notification is only the transport to the
     *  lens; leaving it behind put a "Jarvis" entry in the shade after every
     *  single answer. 0 leaves them there. */
    public static final String SHADE_MS = "display.shade_ms";
    /** Send cards on the silent channel: no heads-up banner, no sound, no
     *  vibration on the phone. The card still reaches the glasses - the relay
     *  is a notification listener and gets it either way. */
    public static final String QUIET_CARDS = "display.quiet_cards";
    /** How often a live navigation card may refresh while only the distance is
     *  ticking, in ms. DEFAULT 0: a card per real event - the maneuver
     *  becoming current, and the turn going imminent - and none for the
     *  counter moving, which redrew the same turn the whole way to it. */
    public static final String NAV_UPDATE_MS = "nav.update_ms";
    /** Mirror the phone's on-screen YouTube captions to the glasses. */
    public static final String CAPTIONS = "captions.relay";
    /**
     * Apps whose notifications iohelper relays to the glasses IN FULL, paged
     * across cards, comma-separated package names. RayNeo's own mirror keeps
     * only the title and a truncated line and drops action buttons, so a
     * notification with real detail arrives on the lens with the important part
     * cut off; iohelper reads the whole thing and pages it. DEFAULT EMPTY - off
     * until the user names a package here, so no personal app list ships in the
     * code; set it on-device (this pref) to opt a chosen app in.
     */
    public static final String NOTIFY_RELAY = "notify.relay_packages";
    /** While turn-by-turn is running, hold back cards nobody asked for -
     *  calendar heads-up, "now playing", captions, timer countdowns. Answers to
     *  questions you actually asked still come through. */
    public static final String NAV_FOCUS = "nav.focus";
    /** Relay Google Maps live turn-by-turn to the glasses (NavListener). */
    public static final String NAV_RELAY = "nav.relay";
    public static final String RUNNING = "service.running";
    /** Whether the user WANTS the assistant running - which is not the same as
     *  whether it is. {@link #RUNNING} is cleared by onDestroy, and a reboot
     *  destroys the service, so restarting on RUNNING would restart nothing.
     *  This is written only by the explicit start/stop calls, so it survives
     *  the process dying and is what BootReceiver reads. */
    public static final String WANTED = "service.wanted";
    public static final String TIMER_PROGRESS = "timer.progress";
    public static final String TIMER_PROGRESS_WINDOW = "timer.progress_window";
    /** Set once wireless-debugging pairing has succeeded; the pairing itself
     *  lives in adbd, this is only so the UI can stop nagging. */
    public static final String WIRELESS_PAIRED = "wireless.paired";
    /** Prefer the reboot-proof TLS path over classic tcpip 5555. */
    public static final String WIRELESS_ENABLED = "wireless.enabled";
    /** Spotify app credentials. An id + secret is enough to SEARCH, which is all
     *  that is needed to turn a spoken song name into a playable URI - no user
     *  login and no Premium. */
    public static final String SPOTIFY_ID = "media.spotify_client_id";
    public static final String SPOTIFY_SECRET = "media.spotify_client_secret";
    /** User OAuth refresh token. The only credential that can command a player;
     *  without it playback falls back to a deep link plus a media key. */
    public static final String SPOTIFY_REFRESH = "media.spotify_refresh_token";
    /** YouTube Data API v3 key. Optional: without it "play X on YouTube" opens a
     *  search rather than starting the top result. */
    public static final String YOUTUBE_KEY = "media.youtube_api_key";
    /** Show a card when the playing track changes. */
    public static final String MEDIA_CARDS = "media.show_track_changes";
    /** The Sonos room last controlled - what a bare "on the Sonos" means. */
    public static final String SONOS_LAST = "sonos.last_room";
    /** Cached speakers, so one failed probe does not mean "no Sonos here". */
    public static final String SONOS_ZONES = "sonos.zones";
    /** "sid|sn|token" learned from the speaker's own Spotify favourites. */
    public static final String SONOS_SPOTIFY = "sonos.spotify_params";

    private Prefs() {
    }

    public static SharedPreferences of(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String str(Context c, String key, String def) {
        try {
            String v = of(c).getString(key, null);
            return v == null || v.isEmpty() ? def : v;
        } catch (ClassCastException e) {
            // The key was stored under a different type - e.g. putTyped turned a
            // provisioned "true" into a real boolean, but this key is read as a
            // string ("1"/"0" flags like wireless.enabled). Coerce instead of
            // letting it throw, which was crashing the whole adb-connect path.
            Object o = of(c).getAll().get(key);
            return o == null ? def : String.valueOf(o);
        }
    }

    public static int integer(Context c, String key, int def) {
        try {
            return Integer.parseInt(str(c, key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean bool(Context c, String key, boolean def) {
        try {
            return of(c).getBoolean(key, def);
        } catch (ClassCastException e) {
            // Mirror image of str(): the key holds a string where a boolean is
            // read. Treat "1"/"true" as true, anything else as false.
            Object o = of(c).getAll().get(key);
            if (o instanceof Boolean) {
                return (Boolean) o;
            }
            if (o == null) {
                return def;
            }
            String s = o.toString();
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
    }

    public static void put(Context c, String key, String value) {
        of(c).edit().putString(key, value).apply();
    }

    /**
     * Write a provisioned value under the right TYPE.
     *
     * SharedPreferences is typed: {@link #bool} reads with getBoolean, so a
     * "true" stored as a String makes the very next read throw
     * ClassCastException. Every on/off setting - nav.relay, display.quiet_cards,
     * captions.relay, nav.focus - was therefore unreachable from the adb config
     * path, which writes everything as text. Recognising the two boolean
     * literals here is what makes those settings provisionable at all.
     */
    public static void putTyped(Context c, String key, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            put(c, key, Boolean.parseBoolean(value));
        } else {
            put(c, key, value);
        }
    }

    public static void put(Context c, String key, boolean value) {
        of(c).edit().putBoolean(key, value).apply();
    }

    /** Comma-separated list, e.g. the known ASR mangles of the wake word. */
    public static List<String> list(Context c, String key, String def) {
        List<String> out = new ArrayList<>();
        for (String s : str(c, key, def).split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Defaults chosen from what actually works on this hardware. */
    public static String trigger(Context c) {
        return str(c, WAKE_TRIGGER, "jarvis");
    }

    public static List<String> mangles(Context c) {
        return list(c, WAKE_MANGLES, "travis,jervis,jarvise,jarvas,darvis");
    }

    /** "assistant" (crown) or "alwayson" (Life Log). Crown is the working one. */
    public static String source(Context c) {
        return str(c, WAKE_SOURCE, "assistant");
    }

    public static List<String> defaultManglesFor(String trigger) {
        return Arrays.asList(trigger + "e", trigger + "s");
    }
}
