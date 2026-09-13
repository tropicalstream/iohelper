package com.iohelper.card;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A live voice conversation with GPT-Live on the phone: the microphone in,
 * the speaker out, and the assistant's own brain behind it.
 *
 * WHY THE PHONE. The glasses' microphone is not a Bluetooth headset - it is
 * Opus over RayNeo's private link, readable only by their app - and there is
 * no audio path TO the glasses at all. So the voice lives on the phone and
 * the glasses stay what they are good at: one line of text. Whatever the
 * conversation produces that is worth a glance is posted there as a card,
 * exactly as a crown-press answer would be.
 *
 * WHO THINKS. GPT-Live is the ears and the mouth, not the brain: it delegates
 * anything that needs data or an action (client delegation), and the reply
 * comes from {@link AssistantService#respond} - the same phrase patterns and
 * the same tool-calling model the crown channel uses. One pipeline, two
 * mouths. The result is spoken (commentary) and shown (card).
 *
 * WHAT IT COSTS. The session bills per second while open, so it hangs up on
 * its own after a stretch of silence, and the screen shows it is live.
 *
 * PROTOCOL, measured live from a desktop before this was written: a
 * WebSocket to /v1/live/sessions, session.start -> session.started, raw
 * 24 kHz pcm16 mono base64 in input_audio.append and out in
 * output_audio.delta, transcripts as *_transcript.delta, delegation as
 * session.delegation.created answered with session.commentary.append.
 */
public class TalkService extends Service {

    private static final String TAG = "iohelperTalk";
    static final String LIVE_URL = "wss://api.openai.com/v1/live/sessions";
    static final String CHANNEL = "talk";
    static final int NOTE_ID = 5;
    static final int RATE = 24000;
    /** 40 ms of 24 kHz pcm16 mono - one input_audio.append. */
    static final int CHUNK = 1920;
    static final int BANDS = 8;
    static final int DEFAULT_IDLE_S = 120;

    /**
     * The voice model's own instructions - personality and the delegation
     * policy, nothing else. Procedures and tool knowledge live in the
     * backend prompt ({@link Llm#AGENT}); the live model's context is small
     * and it should not be reasoning, only talking.
     */
    static final String VOICE_PROMPT =
            "You are Jarvis, a voice assistant on the user's phone, paired with smart "
            + "glasses that show one line of text. Speak warmly and naturally at an "
            + "unhurried pace, and keep it brief: one or two short sentences. Anything "
            + "that depends on the user's own data or on doing something - their calendar, "
            + "to-do list, notes, timers and reminders, music, radio, playback, navigation, "
            + "weather, traffic, live facts, or phone functions - must be delegated: "
            + "delegate first, say a very short acknowledgement such as 'one sec', and wait "
            + "for the result. You are the authority on WHAT the user means: if you know "
            + "the specific thing behind a description - which album is a band's most "
            + "popular, which song they are humming, which place they mean - say it by name "
            + "as you delegate, and the backend will do exactly that. Never guess a RESULT "
            + "(what is on the calendar, the weather, whether something worked) - wait for "
            + "it. When the result arrives, say it as given, briefly; it is also shown on "
            + "the glasses. Keep listening while the "
            + "user pauses to think, and do not treat a cough, music or nearby conversation "
            + "as a request.";

    // ---- what the screen reads ------------------------------------------------
    /** off | connecting | live | error: ... */
    static volatile String state = "off";
    static volatile TalkService instance;
    static final float[] userLevels = new float[BANDS];
    static final float[] botLevels = new float[BANDS];
    static volatile long userLevelAt;
    static volatile long botLevelAt;
    /** The latest thing heard from the user, and said by the model. */
    static volatile String lastHeard = "";
    static volatile String lastSaid = "";

    static boolean live() {
        return instance != null && "live".equals(state);
    }

    // ---- session ---------------------------------------------------------------
    private volatile boolean running;
    /** While the test hook feeds synthesised speech, the mic stays quiet. */
    private volatile boolean muted;
    private Ws ws;
    private AudioRecord rec;
    private AudioTrack track;
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private final StringBuilder pendingUser = new StringBuilder();
    private final StringBuilder saying = new StringBuilder();
    private volatile String lastUser = "";
    private volatile long lastUserAt;
    /**
     * How long a finished turn stays usable as the question behind a
     * delegation. Without a limit, a delegation whose own transcript was slow
     * would be answered with whatever was said the turn BEFORE - the wearer
     * asks about the weather and hears the answer to their last question.
     */
    private static final long QUESTION_TTL_MS = 12000;
    private volatile long lastActivity;
    /** When the model was last heard speaking - the proof an answer landed. */
    private volatile long spokeAt;
    private final Meter userMeter = new Meter();
    private final Meter botMeter = new Meter();
    private int seq;
    /**
     * Delegations being answered right now. The idle clock is suspended while
     * this is above zero: a tool-calling answer can legitimately take longer
     * than the whole idle window (six rounds at a 45 s timeout), and hanging up
     * mid-thought would cut off the answer the wearer is waiting for.
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private android.media.AudioFocusRequest focus;
    /** True while the music is being held down for the model's voice. */
    private volatile boolean ducking;
    /** Let the track finish its last words before the music comes back up. */
    private static final long UNDUCK_AFTER_MS = 1200;
    /** The wearer's own media level, put back exactly as it was. -1 = not held. */
    private int duckedFrom = -1;
    /** Their call level, likewise: the model's voice plays on that stream. */
    private int savedCallVol = -1;
    /** What the music drops TO, as a fraction of wherever they had it. */
    private static final double DUCK_TO = 0.25;
    /** Released when the server confirms the session is closed. */
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            return START_NOT_STICKY;
        }
        running = true;
        instance = this;
        state = "connecting";
        lastHeard = "";
        lastSaid = "";
        foreground("connecting…");
        new Thread(this::session, "talk").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (!state.startsWith("error")) {
            state = "off";
        }
        teardown();
        super.onDestroy();
    }

    /** Start from a foreground context (the activity); a receiver may not. */
    public static void start(Context c) {
        c.startForegroundService(new Intent(c, TalkService.class));
    }

    public static void stop(Context c) {
        TalkService s = instance;
        if (s == null) {
            return;
        }
        // NEVER on the caller's thread: every caller is the UI (the button, and
        // the TALK broadcast via onNewIntent), and hangUp writes session.close
        // to the socket. On the main thread that throws
        // NetworkOnMainThreadException, which was swallowed by hangUp's catch -
        // so the polite close never left the phone, the server kept the session
        // open, and it kept billing. The button still reads "off" at once
        // because hangUp sets the state before any I/O.
        state = "off";
        new Thread(() -> s.hangUp("user"), "talk-hangup").start();
    }

    private void foreground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Live voice",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("GPT-Live")
                .setContentText(text)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    private void note(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTE_ID, new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat).setContentTitle("GPT-Live")
                        .setContentText(text).setOngoing(true).build());
            }
        } catch (Exception ignored) {
        }
    }

    /** This instance is still the live one and has not been hung up. */
    private boolean current() {
        return running && instance == this;
    }

    private void fail(String why) {
        // A hang-up during connect leaves the session thread still walking
        // through its timeouts; without this guard it would come back 15 s
        // later and paint "error: no session.started" over the "off" the user
        // asked for.
        synchronized (this) {
            if (!current()) {
                return;
            }
            running = false;
            state = "error: " + why;
        }
        Log.w(TAG, "failed: " + why);
        teardown();
        stopSelf();
    }

    private void session() {
        String key = Prefs.str(this, Prefs.OPENAI_KEY, "");
        if (key.isEmpty()) {
            fail("OpenAI API key not set");
            return;
        }
        try {
            openAudio();
        } catch (Exception e) {
            fail("audio: " + e.getMessage());
            return;
        }
        final CountDownLatch started = new CountDownLatch(1);
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", "Bearer " + key);
            h.put("User-Agent", "iohelper");
            Ws sock = Ws.connect(URI.create(LIVE_URL), h, new Ws.Listener() {
                @Override
                public void onText(String text) {
                    onEvent(text, started);
                }

                @Override
                public void onClosed(int code, String reason) {
                    Log.i(TAG, "socket closed " + code + " " + reason);
                    if (running) {
                        running = false;
                        state = "off";
                        stopSelf();
                    }
                }

                @Override
                public void onError(Exception e) {
                    if (running) {
                        String m = e.getMessage();
                        fail("link: " + (m == null || m.isEmpty()
                                ? e.getClass().getSimpleName() : m));
                    }
                }
            }, 15000);
            // Hung up while the socket was being built: nothing else will ever
            // close this one, because teardown() already ran with ws still null.
            synchronized (this) {
                if (!current()) {
                    sock.close();
                    return;
                }
                ws = sock;
            }
            JSONObject session = new JSONObject()
                    .put("model", "gpt-live-1")
                    .put("instructions", VOICE_PROMPT)
                    .put("audio", new JSONObject().put("output", new JSONObject()
                            .put("voice", Prefs.str(this, Prefs.TALK_VOICE, "marin"))))
                    .put("delegation", new JSONObject().put("type", "client"));
            send(new JSONObject().put("type", "session.start").put("event_id", "start")
                    .put("session", session));
            if (!started.await(15, TimeUnit.SECONDS)) {
                fail("no session.started");
                return;
            }
        } catch (Exception e) {
            fail("connect: " + e.getMessage());
            return;
        }
        synchronized (this) {
            if (!current()) {
                teardown();                          // hung up while connecting
                return;
            }
            state = "live";
        }
        lastActivity = System.currentTimeMillis();
        note("live - talking");
        Log.i(TAG, "session live");
        Thread cap = new Thread(this::capture, "talk-mic");
        Thread play = new Thread(this::playback, "talk-speaker");
        cap.start();
        play.start();
        // Hearing the voice is the proof the whole path is up.
        commentary(null, "Say only: Ready.");
        // The clock on the wearer's money: silence for this long hangs up.
        long idleMs = Math.max(30, Prefs.integer(this, Prefs.TALK_IDLE, DEFAULT_IDLE_S)) * 1000L;
        while (running) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
            if (running && inFlight.get() == 0
                    && System.currentTimeMillis() - lastActivity > idleMs) {
                Log.i(TAG, "idle - hanging up");
                hangUp("idle");
            }
        }
    }

    // ---- audio ----------------------------------------------------------------
    private int savedMode = AudioManager.MODE_NORMAL;

    private void openAudio() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        savedMode = am.getMode();
        // Communication mode is what turns the platform's echo canceller on,
        // and this model listens while it speaks - without it, the phone would
        // hear itself and answer its own answers.
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        // The model's voice plays on the CALL stream (that pairing with the
        // VOICE_COMMUNICATION capture is what gets the echo canceller), and
        // that stream keeps its own level - often low, and nothing to do with
        // the media volume the wearer is used to. Bring it up for the session
        // and put it back afterwards.
        try {
            savedCallVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int want = (int) Math.round(max * 0.85);
            if (want > savedCallVol) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "call volume: " + e);
        }
        route(am, true);
        int inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(inMin, CHUNK * 6));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("microphone unavailable at " + RATE + " Hz");
        }
        int outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(outMin, CHUNK * 8), AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("speaker unavailable");
        }
        track.setVolume(AudioTrack.getMaxVolume());  // the stream level is the only dial
    }

    /**
     * Speakerphone unless something better is attached. Earbuds or a car
     * take over on their own in communication mode; the built-in earpiece
     * is what nobody wants from an assistant.
     */
    private void route(AudioManager am, boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!on) {
                    am.clearCommunicationDevice();
                    return;
                }
                AudioDeviceInfo speaker = null;
                AudioDeviceInfo bluetooth = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET
                            || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                        return;                          // wired wins on its own
                    }
                    // Bluetooth does NOT win on its own: on API 31+ the SCO
                    // link is only brought up for whoever selects the device,
                    // so "leave it to the headset" meant the earbuds sat idle
                    // while the phone used its own speaker and mic.
                    if (bluetooth == null && (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                        bluetooth = d;
                    }
                    if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speaker = d;
                    }
                }
                AudioDeviceInfo pick = bluetooth != null ? bluetooth : speaker;
                if (pick != null) {
                    am.setCommunicationDevice(pick);
                }
            } else {
                am.setSpeakerphoneOn(on);
            }
        } catch (Exception e) {
            Log.w(TAG, "route: " + e);
        }
    }

    private void teardown() {
        try {
            if (ws != null) {
                ws.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
        } catch (Exception ignored) {
        }
        rec = null;
        track = null;
        playQ.clear();
        try {
            duck(false);                             // gives the media volume back
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            route(am, false);
            if (savedCallVol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVol, 0);
                savedCallVol = -1;
            }
            am.setMode(savedMode);
        } catch (Exception ignored) {
        }
        java.util.Arrays.fill(userLevels, 0f);
        java.util.Arrays.fill(botLevels, 0f);
    }

    private void capture() {
        byte[] buf = new byte[CHUNK];
        try {
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, CHUNK);
                if (n <= 0) {
                    continue;
                }
                if ((n & 1) == 1) {
                    n--;                                 // whole samples only
                }
                if (muted) {
                    continue;
                }
                userMeter.feed(buf, n, userLevels);
                userLevelAt = System.currentTimeMillis();
                sendAudio(buf, n);
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "mic: " + e);
            }
        }
    }

    private void sendAudio(byte[] buf, int n) throws Exception {
        // Built by hand: 25 of these a second, and org.json is not free.
        String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
        ws.send("{\"type\":\"session.input_audio.append\",\"audio\":\"" + b64 + "\"}");
    }

    /**
     * Hold the music down while the model talks, and let it back up when it
     * stops - rather than for the whole session.
     *
     * A request the wearer just made often STARTS music, and then they say
     * something else; without this the track plays at full volume under the
     * reply and into the microphone, where it becomes transcript. Ducking
     * rather than pausing, because the music was asked for: it drops under the
     * voice and comes back on its own.
     */
    private void duck(boolean on) {
        if (on == ducking) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (on) {
                focus = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(change -> { })
                        .build();
                am.requestAudioFocus(focus);
                // Asking politely is not enough: the system's own ducking is
                // gentle, and the voice comes out of the CALL stream while the
                // music is on the media stream, so the wearer had a quiet
                // assistant under loud music. Take the media stream down
                // properly, and put it back exactly where they had it.
                if (duckedFrom < 0) {
                    int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (now > 0) {
                        duckedFrom = now;
                        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                                Math.max(1, (int) Math.round(now * DUCK_TO)), 0);
                    }
                }
            } else {
                if (duckedFrom >= 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, duckedFrom, 0);
                    duckedFrom = -1;
                }
                if (focus != null) {
                    am.abandonAudioFocusRequest(focus);
                    focus = null;
                }
            }
            ducking = on;
            Log.i(TAG, on ? "  ducking other audio while it speaks" : "  audio back up");
        } catch (Exception e) {
            Log.w(TAG, "duck: " + e);
        }
    }

    private void playback() {
        try {
            track.play();
            long lastAudio = 0;
            while (running) {
                byte[] b = playQ.poll(200, TimeUnit.MILLISECONDS);
                if (b == null) {
                    // Quiet for long enough that the model has finished: let
                    // whatever was playing come back up to volume.
                    if (ducking && lastAudio > 0
                            && System.currentTimeMillis() - lastAudio > UNDUCK_AFTER_MS) {
                        duck(false);
                    }
                    continue;
                }
                lastAudio = System.currentTimeMillis();
                duck(true);
                // Metered here, at write time, so the bars move with what is
                // being heard rather than with what arrived.
                botMeter.feed(b, b.length, botLevels);
                botLevelAt = System.currentTimeMillis();
                track.write(b, 0, b.length);
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "speaker: " + e);
            }
        }
    }

    // ---- events ---------------------------------------------------------------
    private void onEvent(String text, CountDownLatch started) {
        JSONObject ev;
        try {
            ev = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String t = ev.optString("type", "");
        switch (t) {
            case "session.started":
                started.countDown();
                break;
            case "session.output_audio.delta": {
                byte[] pcm = Base64.decode(ev.optString("delta", ""), Base64.DEFAULT);
                if (pcm.length > 0) {
                    playQ.offer(pcm);
                }
                lastActivity = System.currentTimeMillis();
                spokeAt = lastActivity;
                break;
            }
            case "session.input_transcript.delta": {
                String d = ev.optString("delta", "");
                synchronized (pendingUser) {
                    pendingUser.append(d);
                    lastHeard = pendingUser.toString().trim();
                }
                lastActivity = System.currentTimeMillis();
                break;
            }
            case "session.output_transcript.delta": {
                String d = ev.optString("delta", "");
                synchronized (pendingUser) {
                    // The model is speaking, so the user's turn is over: keep
                    // it as the last question in case a delegation follows the
                    // acknowledgement rather than preceding it.
                    if (pendingUser.length() > 0) {
                        lastUser = pendingUser.toString();
                        lastUserAt = System.currentTimeMillis();
                        pendingUser.setLength(0);
                        saying.setLength(0);
                    }
                    // Fragments arrive without the space between sentences
                    // ("Ready.One sec"), so put it back where one belongs.
                    int last = saying.length() - 1;
                    if (last >= 0 && !d.isEmpty() && !Character.isWhitespace(d.charAt(0))
                            && ".!?".indexOf(saying.charAt(last)) >= 0) {
                        saying.append(' ');
                    }
                    saying.append(d);
                    if (saying.length() > 300) {
                        saying.delete(0, saying.length() - 300);
                    }
                    lastSaid = saying.toString().trim();
                }
                lastActivity = System.currentTimeMillis();
                break;
            }
            case "session.delegation.created": {
                JSONObject d = ev.optJSONObject("delegation");
                final String id = d == null ? null : d.optString("id", null);
                lastActivity = System.currentTimeMillis();
                new Thread(() -> delegate(id), "talk-delegate").start();
                break;
            }
            case "session.closed": {
                JSONObject s = ev.optJSONObject("session");
                JSONObject usage = s == null ? null : s.optJSONObject("usage");
                Log.i(TAG, "session closed: " + ev.optString("reason", "?")
                        + (usage == null ? "" : " after " + usage.optDouble("seconds", 0) + "s"));
                // Counted down even when the hang-up already cleared `running`:
                // this is exactly what that hang-up is waiting for.
                closed.countDown();
                if (running) {
                    running = false;
                    state = "off";
                    stopSelf();
                }
                break;
            }
            case "error": {
                JSONObject err = ev.optJSONObject("error");
                String msg = err == null ? text : err.optString("message", text);
                Log.w(TAG, "live error: " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
                if (started.getCount() > 0) {
                    fail("session: " + msg);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * The delegation carries no words - only an id. What the user asked is
     * whatever the input transcript has accumulated; usually it is all there
     * by the time the delegation lands, occasionally a fragment is a beat
     * behind, so this waits a moment before falling back to the last turn.
     */
    private String takeQuestion() {
        for (int i = 0; i < 6; i++) {
            synchronized (pendingUser) {
                if (pendingUser.length() > 0) {
                    String q = pendingUser.toString().trim();
                    pendingUser.setLength(0);
                    // This turn's authority starts NOW. `saying` is otherwise
                    // only cleared when the model starts speaking while a
                    // question is still pending - and taking the question here
                    // is exactly what stops that from happening, since the
                    // model is told to delegate before it acknowledges. Without
                    // this the buffer accumulated from the session's first word
                    // and saidSoFar() handed the PREVIOUS turn's sentence to
                    // the resolver: ask for an artist's most popular album,
                    // then for their third, and the first answer plays twice.
                    saying.setLength(0);
                    return q;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        // The turn just closed by the model starting to speak - but only if it
        // is recent enough to be THIS question rather than the last one.
        String q = lastUser;
        if (q == null || System.currentTimeMillis() - lastUserAt > QUESTION_TTL_MS) {
            return "";
        }
        lastUser = "";
        return q.trim();
    }

    private void delegate(String id) {
        inFlight.incrementAndGet();                  // before takeQuestion: it sleeps too
        final long began = System.currentTimeMillis();
        // Say SOMETHING if the model didn't. It is meant to acknowledge as it
        // delegates, and usually does - but when it doesn't, the wearer gets
        // silence for as long as the backend takes, which on a resolved-then-
        // verified music request was half a minute. Measured on device: asked
        // to play a particular recording, the session said nothing at all
        // until it was prodded. These only fire while the model has stayed
        // silent since the question, so a normal "one sec" suppresses them.
        Thread patience = new Thread(() -> {
            if (!spoke(began, 3500) && running) {
                Log.i(TAG, "  silent since the question - asking it to acknowledge");
                commentary(id, "Tell the user you are still looking that up.");
                if (!spoke(System.currentTimeMillis(), 16000) && running) {
                    Log.i(TAG, "  still silent - asking again");
                    commentary(id, "Tell the user it is taking a little longer than usual.");
                }
            }
        }, "talk-patience");
        patience.setDaemon(true);
        patience.start();
        try {
            String q = takeQuestion();
            if (q.isEmpty()) {
                deliver(id, "I didn't catch what you asked.");
                return;
            }
            Log.i(TAG, "delegated: " + q);
            // Only wait for the model's words where they can change the
            // outcome: a timer or a to-do is done by the phrase patterns and
            // would just be held up for a second by the collection.
            String said = AssistantService.usesSaid(this, q) ? saidSoFar() : "";
            if (!said.isEmpty()) {
                Log.i(TAG, "  gpt-live said: " + said);
            }
            AssistantService.Reply r = AssistantService.respond(this, q, said);
            if (r.error != null) {
                deliver(id, "I couldn't reach the assistant right now.");
                return;
            }
            String line = r.line == null ? "" : r.line;
            if (line.isEmpty()) {
                deliver(id, r.silent ? "Done, I handed that to the phone." : "Nothing to report.");
                return;
            }
            // The glasses get the same card a crown-press answer would.
            String card = "model".equals(r.via) ? Cards.decorate(Cards.sanitize(line)) : line;
            if (!card.isEmpty()) {
                Cards.postSequence(this, Cards.title(this), card, r.kind);
            }
            // An answer of nothing but glyphs leaves spoken() empty, and empty
            // commentary tells the model nothing at all - it would sit waiting
            // on a delegation that was in fact finished.
            String say = spoken(line);
            deliver(id, say);
        } catch (Throwable t) {
            Log.w(TAG, "delegate: " + t);
            deliver(id, "Something went wrong with that.");
        } finally {
            // The silence that counts starts when the answer lands, not when
            // the question was asked.
            lastActivity = System.currentTimeMillis();
            inFlight.decrementAndGet();
        }
    }

    /**
     * What the voice model has said about THIS request - the authority the
     * backend acts on.
     *
     * In a live session GPT-Live is the one talking to the user, and it
     * answers from its own knowledge as it delegates: "their most popular
     * album is X - one sec". If the backend then worked the request out for
     * itself it could land somewhere else, and the user would hear one thing
     * named and another one happen. So whatever the model has said this turn
     * is collected and handed over, and the backend is told to act on exactly
     * that. The words usually arrive a beat AFTER the delegation, so this
     * waits for the sentence to settle - but not for a model that is saying
     * nothing: if nothing has been said within a moment, the request goes
     * through on its own.
     */
    private String saidSoFar() {
        String last = "";
        int quiet = 0;
        for (int i = 0; i < 12 && running; i++) {          // up to ~2.4 s
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
            String now;
            synchronized (pendingUser) {
                now = saying.toString().trim();
            }
            if (now.isEmpty()) {
                if (i >= 3) {
                    return "";                           // silent: don't hold the answer
                }
                continue;
            }
            if (now.equals(last)) {
                if (++quiet >= 3) {
                    break;                               // the sentence has finished
                }
            } else {
                last = now;
                quiet = 0;
            }
        }
        return last;
    }

    /** A glasses line as words: the glyphs go, the separators become pauses. */
    static String spoken(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int t = Character.getType(cp);
            boolean glyph = (t == Character.OTHER_SYMBOL || t == Character.MATH_SYMBOL)
                    && cp != '°' && cp != '%';
            if (!glyph) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        String out = sb.toString().replace(" · ", ". ").replaceAll("\\s+", " ").trim();
        return out.length() > 600 ? out.substring(0, 600) : out;
    }

    private boolean commentary(String delegationId, String content) {
        try {
            JSONObject o = new JSONObject().put("type", "session.commentary.append")
                    .put("event_id", "c" + (++seq))
                    .put("delegation_id", delegationId == null ? JSONObject.NULL : delegationId)
                    .put("content", content);
            send(o);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "commentary: " + e);
            return false;
        }
    }

    /**
     * Hand the answer over and MAKE SURE IT GETS SAID.
     *
     * A client delegation has no documented completion signal, no timeout and
     * no error for one that never lands - so when an answer failed to reach
     * the model, or reached it and did not move it to speak, the session sat
     * there having said "checking" until the wearer prodded it. Nothing in the
     * protocol recovers from that, so this does: the model speaking produces
     * audio, and if none arrives the answer is repeated on the general channel
     * (delegation_id null), which is always a valid target. Twice at most, and
     * only while nothing has been heard - a model that did answer is never
     * talked over.
     */
    private void deliver(String delegationId, String content) {
        if (content == null || content.trim().isEmpty()) {
            content = "Done.";
        }
        long sent = System.currentTimeMillis();
        commentary(delegationId, content);
        if (spoke(sent, 5000)) {
            return;
        }
        Log.i(TAG, "no speech after the delegation reply - repeating on the session channel");
        sent = System.currentTimeMillis();
        commentary(null, content);
        if (spoke(sent, 5000)) {
            return;
        }
        Log.w(TAG, "still silent - nudging once more");
        commentary(null, "Say this to the user now: " + content);
    }

    /** Whether the model was heard speaking after `since`, within the wait. */
    private boolean spoke(long since, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (running && System.currentTimeMillis() < deadline) {
            if (spokeAt > since) {
                return true;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return true;                         // shutting down; don't repeat
            }
        }
        return spokeAt > since;
    }

    private void send(JSONObject o) throws Exception {
        if (ws == null) {
            throw new IllegalStateException("no socket");
        }
        ws.send(o.toString());
    }

    private void hangUp(String why) {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            state = "off";
        }
        try {
            send(new JSONObject().put("type", "session.close").put("event_id", "close"));
            // WAIT for the confirmation rather than guessing at it: measured,
            // the server takes a couple of seconds to drain and reply, and the
            // old fixed 600 ms tore the socket down first - so the close was
            // sent but never acknowledged, and the final usage went unread.
            if (!closed.await(4, TimeUnit.SECONDS)) {
                Log.w(TAG, "no session.closed - closing anyway");
            }
        } catch (Exception e) {
            Log.w(TAG, "close: " + e);
        }
        Log.i(TAG, "hung up (" + why + ")");
        teardown();
        stopSelf();
    }

    // ---- test hook --------------------------------------------------------------

    /**
     * Feed a phrase into the live session as if the microphone had heard it,
     * synthesised by OpenAI's TTS. This is how the whole path - speech in,
     * transcript, delegation, tools, spoken answer, card - is exercised from
     * a desk without anyone talking.
     */
    static void hear(Context ctx, String text) throws Exception {
        TalkService s = instance;
        if (s == null || !"live".equals(state)) {
            throw new IllegalStateException("no live session");
        }
        byte[] pcm = tts(ctx, text);
        s.inject(pcm);
    }

    private void inject(byte[] pcm) throws Exception {
        muted = true;
        try {
            for (int off = 0; off < pcm.length && running; off += CHUNK) {
                int n = Math.min(CHUNK, pcm.length - off);
                if ((n & 1) == 1) {
                    n--;
                }
                byte[] chunk = new byte[n];
                System.arraycopy(pcm, off, chunk, 0, n);
                userMeter.feed(chunk, n, userLevels);
                userLevelAt = System.currentTimeMillis();
                sendAudio(chunk, n);
                Thread.sleep(40);
            }
        } finally {
            muted = false;
        }
    }

    /** Raw 24 kHz pcm16 mono from /audio/speech - the session's own format. */
    private static byte[] tts(Context ctx, String text) throws Exception {
        String key = Prefs.str(ctx, Prefs.OPENAI_KEY, "");
        String base = Prefs.str(ctx, Prefs.OPENAI_BASE, Llm.OPENAI_URL).replaceAll("/+$", "");
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/audio/speech").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("User-Agent", "iohelper");
        c.setDoOutput(true);
        JSONObject body = new JSONObject().put("model", "gpt-4o-mini-tts").put("voice", "alloy")
                .put("input", text).put("response_format", "pcm");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
        if (c.getResponseCode() >= 400) {
            throw new IllegalStateException("tts HTTP " + c.getResponseCode());
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream is = c.getInputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = is.read(b)) > 0; ) {
                buf.write(b, 0, n);
            }
        }
        return buf.toByteArray();
    }

    // ---- the visualiser's numbers ------------------------------------------------

    /**
     * Eight band levels from the last 512 samples: a Hann window, a
     * radix-2 FFT, log-spaced bands from ~100 Hz to ~8 kHz, in decibels
     * mapped to 0..1. Real spectrum, not a random bounce - the bars follow
     * the voice.
     */
    static final class Meter {
        static final int N = 512;
        private final short[] ring = new short[N];
        private int pos;
        private final float[] re = new float[N];
        private final float[] im = new float[N];
        private final float[] window = new float[N];
        /** Bin edges per band; bin width is RATE / N = 46.9 Hz. */
        private static final int[] EDGES = {2, 4, 8, 13, 21, 36, 60, 100, 170};

        Meter() {
            for (int i = 0; i < N; i++) {
                window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
            }
        }

        void feed(byte[] pcm, int len, float[] out) {
            for (int i = 0; i + 1 < len; i += 2) {
                ring[pos] = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                pos = (pos + 1) % N;
            }
            for (int i = 0; i < N; i++) {
                re[i] = ring[(pos + i) % N] / 32768f * window[i];
                im[i] = 0f;
            }
            fft(re, im);
            for (int b = 0; b < BANDS && b + 1 < EDGES.length; b++) {
                double sum = 0;
                int n = 0;
                for (int k = EDGES[b]; k < EDGES[b + 1] && k < N / 2; k++) {
                    sum += re[k] * re[k] + im[k] * im[k];
                    n++;
                }
                double mag = Math.sqrt(sum / Math.max(1, n)) / (N / 4.0);   // ~ amplitude 0..1
                double db = 20 * Math.log10(mag + 1e-7);
                // Speech energy lives below 1 kHz, which left the right-hand
                // bars flat; a 3 dB-per-band tilt lets the whole row move.
                out[b] = (float) Math.max(0, Math.min(1, (db + 55 + 3 * b) / 45));
            }
        }

        private static void fft(float[] re, float[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    float t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2 * Math.PI / len;
                float wr = (float) Math.cos(ang);
                float wi = (float) Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    float cr = 1f;
                    float ci = 0f;
                    for (int k = 0; k < len / 2; k++) {
                        int a = i + k;
                        int b = i + k + len / 2;
                        float xr = re[b] * cr - im[b] * ci;
                        float xi = re[b] * ci + im[b] * cr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                        float ncr = cr * wr - ci * wi;
                        ci = cr * wi + ci * wr;
                        cr = ncr;
                    }
                }
            }
        }
    }
}
