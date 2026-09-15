package com.iohelper.card;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Settings + start/stop. Built in code rather than XML layouts so the whole app
 * still compiles with the Gradle-free aapt2/javac/d8 pipeline.
 */
public class MainActivity extends Activity {

    private TextView statusView;
    /** Talk to the live model: the phone's mic and speaker, the assistant's brain. */
    private Button talkBtn;
    private MeterView meter;
    private TextView talkLine;
    private boolean resumed;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 20 fps while the screen is up: enough for bars to feel alive. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshTalk();
            if (resumed) {
                ui.postDelayed(this, 50);
            }
        }
    };
    private static final float[] ZERO = new float[TalkService.BANDS];
    private static final int REQ_MIC = 7;
    /** The glasses channel: reading crown-press transcripts, or not. */
    private Button listenButton;

    private static final int BG = Color.parseColor("#0B0D12");
    private static final int CARD = Color.parseColor("#161A24");
    private static final int INPUT = Color.parseColor("#10131B");
    private static final int LINE = Color.parseColor("#242A3A");
    private static final int FG = Color.parseColor("#EDEFF5");
    private static final int MUTED = Color.parseColor("#8A90A3");
    private static final int ACCENT = Color.parseColor("#7C5CFF");
    private static final int OK = Color.parseColor("#3ECF8E");

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The page draws its own heading; the system bar was repeating it.
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(heading(Cards.title(this)));
        // First and unmistakable: talk. A live voice conversation with
        // GPT-Live through the phone's mic and speaker, the assistant's own
        // brain behind it, and a small spectrum beside the button that moves
        // with whoever is speaking - purple for you, green for it.
        LinearLayout talkRow = new LinearLayout(this);
        talkRow.setOrientation(LinearLayout.HORIZONTAL);
        talkRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        talkRow.setLayoutParams(rlp);
        meter = new MeterView(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(64), dp(44));
        mlp.rightMargin = dp(10);
        meter.setLayoutParams(mlp);
        talkRow.addView(meter);
        talkBtn = new Button(this);
        talkBtn.setAllCaps(false);
        talkBtn.setTextSize(17);
        talkBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        talkBtn.setStateListAnimator(null);
        talkBtn.setPadding(dp(16), dp(16), dp(16), dp(16));
        talkBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        talkBtn.setOnClickListener(v -> toggleTalk());
        talkRow.addView(talkBtn);
        root.addView(talkRow);
        talkLine = new TextView(this);
        talkLine.setTextColor(MUTED);
        talkLine.setTextSize(12);
        talkLine.setLineSpacing(dp(2), 1f);
        talkLine.setPadding(dp(4), dp(8), dp(4), 0);
        talkLine.setVisibility(View.GONE);
        root.addView(talkLine);

        // The glasses channel - reading crown-press transcripts - second and
        // quieter now that talking is the headline. Start and Stop used to be
        // two more purple buttons under the settings, which is where "I
        // clicked stop and nothing happened" came from.
        listenButton = new Button(this);
        listenButton.setAllCaps(false);
        listenButton.setTextSize(14);
        listenButton.setStateListAnimator(null);
        listenButton.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        tlp.bottomMargin = dp(12);
        listenButton.setLayoutParams(tlp);
        listenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });
        root.addView(listenButton);

        statusView = new TextView(this);
        statusView.setTextColor(MUTED);
        statusView.setTextSize(12);
        statusView.setLineSpacing(dp(3), 1f);
        statusView.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(16);
        slp.leftMargin = dp(2);
        statusView.setLayoutParams(slp);
        root.addView(statusView);

        root.addView(heading2("Voice"));
        LinearLayout voice = card(root);
        // NO WAKE WORD FIELD. On the channel that actually works there is
        // nothing to say: the crown press IS the trigger. Offering a "wake word"
        // to configure told people to say a word that does nothing, and the hint
        // under it went further and told them where in the sentence to say it.
        // The trigger and its known mis-hearings still live in Prefs - one said
        // out of habit is still stripped from the question, and the alwayson
        // gate still needs both - they are simply no longer presented as
        // something to set while that source is broken in RayNeo's firmware.
        final EditText source = field(voice, "Source (assistant | alwayson)",
                Prefs.source(this), false);
        voice.addView(hint("assistant = crown press, no wake word - pressing the crown "
                + "is the trigger (RayNeo answers too). alwayson = hands-free, currently "
                + "broken in RayNeo's firmware."));

        root.addView(heading2("Live voice"));
        LinearLayout live = card(root);
        final EditText talkBackend = field(live, "Backend (gemini | openai)",
                Prefs.str(this, Prefs.TALK_BACKEND, "gemini"), false);
        live.addView(hint("Which model the talk button opens. gemini = "
                + Live.Gemini.MODEL + ", using the Gemini key below; openai = "
                + "gpt-live-1, using the OpenAI key. Both delegate to the same "
                + "assistant, so answers and cards are identical - what differs "
                + "is the voice, the latency and the bill. Changing this takes "
                + "effect on the next session, not the one in progress."));
        final EditText talkModel = field(live, "Gemini Live model",
                Prefs.str(this, Prefs.TALK_MODEL, Live.Gemini.MODEL), false);
        live.addView(hint("Live models turn over fast and are not "
                + "interchangeable. " + Live.Gemini.MODEL + " is the stable "
                + "default for low-latency voice; gemini-3.1-flash-live-preview "
                + "is a preview Google's own model list marks legacy. Every Live "
                + "model is Live-API-only, so none of them can serve as the text "
                + "backend above - that stays a separate model."));

        final EditText talkVoice = field(live, "Voice",
                Prefs.str(this, Prefs.TALK_VOICE, ""), false);
        live.addView(hint("Speaking now as: " + Live.of(this).voiceInUse(this)
                + ". Each backend has its OWN voice names and rejects the other's, so "
                + "a name saved for one is ignored by the other rather than sent and "
                + "refused - which is why the box above and the voice in use can "
                + "differ. Gemini: Kore, Puck, Charon, Fenrir, Aoede, Zephyr, Leda, "
                + "Orus. OpenAI, verified against the API: marin, cedar, quartz, "
                + "ripple, vesper, willow, stone, gleam, meridian, bossa, tempo, "
                + "beacon, delta, cinder. Takes effect on the next session - a voice "
                + "cannot be changed once one is running."));

        root.addView(heading2("Services"));
        LinearLayout keys = card(root);
        final EditText backend = field(keys, "LLM backend (groq | openai | gemini)",
                Prefs.str(this, Prefs.BACKEND, "groq"), false);
        final EditText openaiKey = field(keys, "OpenAI API key",
                Prefs.str(this, Prefs.OPENAI_KEY, ""), true);
        final EditText openaiModel = field(keys, "OpenAI model",
                Prefs.str(this, Prefs.OPENAI_MODEL, "gpt-5.6-luna"), false);
        final EditText groqKey = field(keys, "Groq API key",
                Prefs.str(this, Prefs.GROQ_KEY, ""), true);
        final EditText groqModel = field(keys, "Groq model",
                Prefs.str(this, Prefs.GROQ_MODEL, "openai/gpt-oss-120b"), false);
        toggle(keys, "Let the model act (tools)", Prefs.TOOLS, true);
        keys.addView(hint("With tools on, a request the built-in phrases miss still "
                + "gets done: the model calls the same timer, list, calendar, "
                + "music, radio and navigation functions with proper arguments. "
                + "openai (gpt-5.6-luna) costs about a tenth of a cent a question; "
                + "groq is free and can call the same functions."));
        final EditText serpKey = field(keys, "SerpApi key",
                Prefs.str(this, Prefs.SERPAPI_KEY, ""), true);
        final EditText location = field(keys, "Location (fallback when GPS is unavailable)",
                Prefs.str(this, Prefs.SEARCH_LOCATION, ""), false);
        keys.addView(hint("GPS is used when the permission is granted; this is only the "
                + "fallback, since a typed city is wrong as soon as you walk away."));

        final EditText spotifyId = field(keys, "Spotify client ID",
                Prefs.str(this, Prefs.SPOTIFY_ID, ""), true);
        final EditText spotifySecret = field(keys, "Spotify client secret",
                Prefs.str(this, Prefs.SPOTIFY_SECRET, ""), true);
        final EditText youtubeKey = field(keys, "YouTube Data API key (optional)",
                Prefs.str(this, Prefs.YOUTUBE_KEY, ""), true);
        keys.addView(hint("Play, pause, skip and volume need NO keys - they are media "
                + "buttons and drive whatever is playing. An id + secret lets Spotify "
                + "resolve a spoken song name to a playable link (no login, no Premium). "
                + "Without a YouTube key, \"play X on YouTube\" opens a search rather "
                + "than starting the top result."));

        toggle(keys, "Use Spotify for podcasts (instead of Pocket Casts)",
                Prefs.PODCASTS_VIA_SPOTIFY, false);
        keys.addView(hint("Both carry podcasts. This decides where \"play X on "
                + "podcasts\" goes - set it to wherever your subscriptions are. "
                + "Pocket Casts searches only shows you subscribe to; Spotify "
                + "searches its whole catalogue but needs the keys above."));

        final EditText callSigns = field(keys, "Radio call signs (comma-separated)",
                Prefs.str(this, Prefs.RADIO_CALLSIGNS, ""), false);
        keys.addView(hint("Words here ALWAYS mean a radio station, so \"play kpfa\" "
                + "tunes in instead of finding a song that sounds like it. Unambiguous "
                + "call signs are recognised without this; list the ones that clash "
                + "with a band or a song, where guessing would be a coin flip."));

        // The notes the assistant holds. They live in its own store - not in
        // the phone's Notes app, which no third-party app may write to - so
        // until now the only way to see one was to ask for it out loud, and a
        // wearer who went looking in Notes found nothing and assumed it was
        // never saved. Read-only here; "forget the note about X" deletes.
        root.addView(heading2("Notes"));
        LinearLayout notesCard = card(root);
        notesView = hint("");
        notesCard.addView(notesView);
        notesCard.addView(hint("Kept by Jarvis, not the phone's Notes app. Dictate with "
                + "\"make a note that…\", read back with \"what are my notes\", delete "
                + "with \"forget the note about…\"."));

        root.addView(heading2("On the glasses"));
        LinearLayout lens = card(root);
        toggle(lens, "Quiet cards (no phone popups)", Prefs.QUIET_CARDS, true);
        lens.addView(hint("The notification is only how text reaches the lens. With "
                + "this on it goes out silently and is taken back out of the phone's "
                + "shade a few seconds later, so answers appear on the glasses and "
                + "nowhere else. The phone's own alerts - reconnecting, pairing - are "
                + "unaffected."));
        toggle(lens, "Live navigation on the glasses", Prefs.NAV_RELAY, true);
        toggle(lens, "Wake the screen for navigation when locked", Prefs.NAV_WAKE, true);
        lens.addView(hint("Google Maps will not start guidance behind the lock screen - the "
                + "route loads and simply never begins. This lights the screen so it is one "
                + "unlock from starting, and the card says so instead of claiming it is "
                + "navigating. The lock itself is untouched."));
        lens.addView(hint("Relays Google Maps' own turn-by-turn, with the distance and "
                + "ETA, and repeats the turn as you reach it. Needs Notification access."));
        toggle(lens, "Only navigation while driving", Prefs.NAV_FOCUS, false);
        lens.addView(hint("While a trip is running, holds back cards you did not ask "
                + "for - calendar heads-up, \"now playing\", captions, timer countdowns - "
                + "so nothing wipes the turn you are about to take. Answers to questions "
                + "you actually ask still come through."));
        toggle(lens, "Mirror YouTube captions", Prefs.CAPTIONS, false);
        lens.addView(hint("Shows the captions YouTube is ALREADY displaying on the phone. "
                + "It never switches captions on for you - if they are off, there is "
                + "nothing to mirror. Needs Accessibility access, below."));

        Button access = button(lens, "Grant Accessibility access (captions)");
        access.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Open Settings > Accessibility",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        lens.addView(hint("Find " + Cards.title(this) + " under Installed apps. On Android "
                + "13+ a sideloaded build is blocked until you allow \"Restricted "
                + "settings\" for it in App info - the toggle just won't stick until "
                + "then, with no error shown."));

        Button save = button(root, "Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.put(MainActivity.this, Prefs.WAKE_SOURCE, source.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.BACKEND, backend.getText().toString()
                        .trim().toLowerCase(java.util.Locale.ROOT));
                Prefs.put(MainActivity.this, Prefs.OPENAI_KEY, openaiKey.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.OPENAI_MODEL, openaiModel.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.GROQ_KEY, groqKey.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.GROQ_MODEL, groqModel.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.SERPAPI_KEY, serpKey.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.SEARCH_LOCATION, location.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.SPOTIFY_ID, spotifyId.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.SPOTIFY_SECRET, spotifySecret.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.YOUTUBE_KEY, youtubeKey.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.RADIO_CALLSIGNS,
                        callSigns.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.TALK_BACKEND,
                        talkBackend.getText().toString().trim().toLowerCase(
                                java.util.Locale.US));
                Prefs.put(MainActivity.this, Prefs.TALK_VOICE,
                        talkVoice.getText().toString().trim());
                Prefs.put(MainActivity.this, Prefs.TALK_MODEL,
                        talkModel.getText().toString().trim());
                Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });

        Button dev = button(root, "Open Developer options");
        dev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open settings",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ---- wireless debugging: the link that survives a reboot ------------
        root.addView(heading2("Wireless debugging"));
        root.addView(hint("The assistant reads the glasses' transcripts through the phone's own "
                + "adbd, so adbd has to trust this app's key. Pairing below is how that happens "
                + "without a computer. Turn on Developer options -> Wireless debugging first; "
                + "that toggle persists across reboots, unlike `adb tcpip 5555`, so once this "
                + "works the assistant comes back on its own after a restart."));

        Button begin = button(root, "Start pairing");
        begin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The code does not exist yet, and it will not survive coming
                // back here to type it - the pairing dialog is what keeps it
                // alive. So hand the user a notification to type into instead
                // and get out of their way. See Pairing.
                Pairing.prompt(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        "Turn ON Wireless debugging, tap \"Pair device with pairing code\", "
                        + "then pull down the shade and type the code into the Jarvis "
                        + "notification.", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception ignored) {
                    // no Developer options activity; the notification still works
                }
            }
        });
        root.addView(hint("Tap this, then in Settings: turn Wireless debugging ON, tap \"Pair "
                + "device with pairing code\", and LEAVE that dialog on screen. Pull the "
                + "notification shade down over it and type the six digits into the Jarvis "
                + "notification. Coming back to this app to type them can close the dialog, "
                + "which cancels the code - that is what the notification avoids."));

        final EditText code = field(root, "Pairing code (6 digits)", "", false);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(hint("Only if the dialog survived being left: type the code here instead."));

        Button pair = button(root, "Pair with the code above");
        pair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String pin = code.getText().toString().trim();
                if (pin.length() != 6) {
                    Toast.makeText(MainActivity.this, "Six digits, no spaces",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "Pairing - keep the dialog open...",
                        Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Wireless.pair(MainActivity.this, pin);
                            // Paired is not the claim; connecting as shell is.
                            com.cgutman.adblib.AdbConnection c = null;
                            try {
                                c = Wireless.connect(MainActivity.this, 12000);
                                com.cgutman.adblib.AdbStream st = c.open("shell:id -un");
                                String who = new String(st.read(), "UTF-8").trim();
                                st.close();
                                say("Paired, and connected as " + who
                                        + ". No computer needed from here.");
                            } finally {
                                if (c != null) {
                                    try {
                                        c.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            say("Pairing failed: " + ex);
                        }
                    }
                }).start();
            }
        });

        Button wireless = button(root, "Test wireless connect");
        wireless.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Looking for adbd over mDNS...",
                        Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        com.cgutman.adblib.AdbConnection c = null;
                        try {
                            Wireless.Endpoint e = Wireless.discover(MainActivity.this,
                                    AdbMdns.SERVICE_TYPE_TLS_CONNECT, 12000);
                            if (e == null) {
                                throw new IllegalStateException("no _adb-tls-connect service "
                                        + "- is Wireless debugging switched on?");
                            }
                            c = Wireless.connect(MainActivity.this, 12000);
                            // prove it is really the shell uid, not just a socket
                            com.cgutman.adblib.AdbStream st = c.open("shell:id -un");
                            String who = new String(st.read(), "UTF-8").trim();
                            st.close();
                            Prefs.put(MainActivity.this, Prefs.WIRELESS_ENABLED, "1");
                            say("Wireless connect OK on " + e + " as " + who
                                    + ". This survives a reboot.");
                        } catch (Exception ex) {
                            say("Wireless connect failed: " + ex);
                        } finally {
                            if (c != null) {
                                try {
                                    c.close();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }).start();
            }
        });

        Button test = button(root, "Test card on glasses");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Cards.post(MainActivity.this, Cards.title(MainActivity.this),
                        "☀ Test card - if you can read this, delivery works.");
            }
        });

        root.addView(hint("Two ways in, and only these two: pair with Wireless debugging "
                + "above (survives reboots, needs no computer), or `adb tcpip 5555` armed once "
                + "from a computer (does not survive one)."));
        setContentView(scroll);
        refresh();
        askForPermissions();
        maybeAutostart(getIntent());
    }

    /**
     * Ask for the runtime permissions the app has always needed but never
     * requested.
     *
     * It got away with that because every install so far came from
     * `adb install -g`, which grants them all silently. Someone sideloading the
     * APK by tapping it gets none - and a denied POST_NOTIFICATIONS is the worst
     * of them, because cards ARE notifications: nothing reaches the glasses and
     * every layer still reports success. That would have undone the point of
     * pairing, which is that no computer is needed.
     *
     * READ_LOGS is deliberately absent: it cannot be granted this way, and the
     * assistant does not need it - it reads logcat through adbd as shell,
     * because logd filters the buffer by UID and the app's own view is useless.
     */
    private void askForPermissions() {
        java.util.List<String> want = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        want.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        want.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        want.add(android.Manifest.permission.READ_CALENDAR);
        want.add(android.Manifest.permission.WRITE_CALENDAR);

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        maybeAutostart(intent);
    }

    /** Started via `am start ... --ez autostart true` (or the START broadcast,
     *  which routes here) - an activity is a foreground context, so it may
     *  legally start the foreground service. */
    private void maybeAutostart(android.content.Intent intent) {
        if (intent != null && intent.getBooleanExtra("autostart", false)) {
            AssistantService.start(this);
            refresh();
        }
        // The TALK broadcast lands here too: a microphone service may only be
        // started from the foreground, and this screen is the foreground.
        if (intent != null && intent.getBooleanExtra("talk", false)) {
            intent.removeExtra("talk");
            toggleTalk();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the assistant came back from a reboot it had to start without the
        // location foreground-service type - that one is refused to a
        // background app. Being on screen is the eligible state it needed, so
        // this is the natural moment to hand it back.
        AssistantService.promoteLocationType();
        refresh();
        resumed = true;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** Start or end the live voice session from the talk button. */
    private void toggleTalk() {
        if (TalkService.instance != null) {
            TalkService.stop(this);
            refreshTalk();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        TalkService.start(this);
        refreshTalk();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code != REQ_MIC) {
            return;
        }
        if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            TalkService.start(this);
        } else {
            Toast.makeText(this, "Talking needs the microphone", Toast.LENGTH_LONG).show();
        }
    }

    /** The talk button, the bars and the transcript line, from the service's state. */
    private void refreshTalk() {
        if (talkBtn == null) {
            return;
        }
        String st = TalkService.state;
        boolean live = "live".equals(st);
        String label;
        int fill;
        int stroke;
        int text;
        if (live) {
            label = "●  Live  —  tap to end";
            fill = Color.parseColor("#0F2A1E");
            stroke = Color.parseColor("#1E5C41");
            text = OK;
        } else if ("connecting".equals(st)) {
            label = "…  Connecting to " + Live.of(this).label();
            fill = CARD;
            stroke = LINE;
            text = MUTED;
        } else if (st.startsWith("error")) {
            String why = st.substring(7);
            label = "!  " + (why.length() > 42 ? why.substring(0, 42) + "…" : why) + "  —  tap to retry";
            fill = Color.parseColor("#2A0F14");
            stroke = Color.parseColor("#5C1E2A");
            text = Color.parseColor("#FF7A8A");
        } else {
            // Named from the SETTING, not hardcoded: the button said
            // "GPT-Live" while a Gemini session was running.
            label = "🎤  Talk to " + Live.of(this).label();
            fill = ACCENT;
            stroke = 0;
            text = Color.WHITE;
        }
        if (!label.equals(String.valueOf(talkBtn.getText()))) {
            talkBtn.setText(label);
            talkBtn.setTextColor(text);
            talkBtn.setBackground(surface(fill, stroke, 14));
        }
        long now = System.currentTimeMillis();
        boolean bot = live && now - TalkService.botLevelAt < 160;
        boolean user = live && now - TalkService.userLevelAt < 160;
        meter.set(bot ? TalkService.botLevels : user ? TalkService.userLevels : ZERO,
                bot ? OK : user ? ACCENT : LINE);
        String heard = TalkService.lastHeard;
        String said = TalkService.lastSaid;
        if (live && (!heard.isEmpty() || !said.isEmpty())) {
            String line = (heard.isEmpty() ? "" : "You:  " + heard)
                    + (heard.isEmpty() || said.isEmpty() ? "" : "\n")
                    + (said.isEmpty() ? "" : Live.of(this).label() + ":  " + said);
            if (!line.equals(String.valueOf(talkLine.getText()))) {
                talkLine.setText(line);
            }
            talkLine.setVisibility(View.VISIBLE);
        } else if (talkLine.getVisibility() != View.GONE) {
            talkLine.setVisibility(View.GONE);
        }
    }

    /**
     * Eight bars that follow the live audio's spectrum, centred so they
     * breathe outward. Attack fast, decay slow - what an ear expects.
     */
    private final class MeterView extends View {
        private final float[] target = new float[TalkService.BANDS];
        private final float[] shown = new float[TalkService.BANDS];
        private int color = LINE;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();

        MeterView(android.content.Context c) {
            super(c);
        }

        void set(float[] levels, int c) {
            System.arraycopy(levels, 0, target, 0, target.length);
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            int n = target.length;
            float w = getWidth();
            float h = getHeight();
            float gap = dp(3);
            float bw = (w - gap * (n - 1)) / n;
            paint.setColor(color);
            for (int i = 0; i < n; i++) {
                float t = Math.max(0.08f, target[i]);
                shown[i] += (t - shown[i]) * (t > shown[i] ? 0.5f : 0.18f);
                float bh = Math.max(dp(3), shown[i] * h);
                float x = i * (bw + gap);
                float y = (h - bh) / 2f;
                box.set(x, y, x + bw, y + bh);
                cv.drawRoundRect(box, bw / 2f, bw / 2f, paint);
            }
        }
    }

    /** Flip listening on or off from the top button. */
    private void toggleListening() {
        boolean running = Prefs.bool(this, Prefs.RUNNING, false);
        if (running) {
            AssistantService.stop(this);
            Toast.makeText(this, "Stopped listening", Toast.LENGTH_SHORT).show();
        } else {
            AssistantService.start(this);
            Toast.makeText(this, "Starting - accept 'Allow debugging?' if it appears",
                    Toast.LENGTH_LONG).show();
        }
        refresh();
        // The service flips RUNNING on its own schedule - a moment after
        // start, in onDestroy after stop - so look again once it has had the
        // chance, or the button would keep showing the state just left.
        Runnable again = new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        };
        listenButton.postDelayed(again, 1500);
        listenButton.postDelayed(again, 5000);
    }

    private TextView notesView;

    /** The saved notes, newest first, or a line saying there are none. */
    private String notesText() {
        java.util.List<org.json.JSONObject> all = Notes.all(this);
        if (all.isEmpty()) {
            return "No notes yet.";
        }
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("MMM d", java.util.Locale.US);
        for (org.json.JSONObject n : all) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            long at = (long) (n.optDouble("created", 0) * 1000);
            sb.append("▤ ").append(n.optString("text", ""))
                    .append(at > 0 ? "   (" + when.format(new java.util.Date(at)) + ")" : "");
        }
        return sb.toString();
    }

    private void refresh() {
        if (notesView != null) {
            notesView.setText(notesText());
        }
        boolean running = Prefs.bool(this, Prefs.RUNNING, false);
        if (listenButton != null) {
            listenButton.setText(running ? "●  Glasses listening  —  tap to stop"
                    : "○  Glasses  —  tap to start listening");
            listenButton.setTextColor(running ? OK : Color.WHITE);
            // Green and quiet while it is up; the accent while it is not,
            // because "start" is then the thing to do.
            listenButton.setBackground(surface(running ? Color.parseColor("#0F2A1E") : ACCENT,
                    running ? Color.parseColor("#1E5C41") : 0, 14));
        }
        statusView.setText((running ? "● listening" : "○ stopped")
                + "   ·   source: " + Prefs.source(this)
                + (Search.available(this) ? "   ·   search on" : "   ·   no search key")
                + (Wireless.paired(this) ? "   ·   wireless paired" : "   ·   usb-armed only"));
        statusView.setTextColor(running ? OK : MUTED);
        // A pill rather than a loose line: running-or-not is the one thing worth
        // reading at a glance on this screen, so it gets a shape of its own.
        statusView.setBackground(surface(running ? Color.parseColor("#0F2A1E") : CARD,
                running ? Color.parseColor("#1E5C41") : LINE, 999));
    }

    /** Toast from any thread, then re-read the status line. */
    private void say(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                refresh();
            }
        });
    }

    // ---- tiny view helpers -------------------------------------------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(28);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(8), 0, dp(8));
        return t;
    }

    /**
     * A rounded surface. One shape language for panels, inputs and buttons is
     * most of the difference between "a screen" and "a stack of widgets".
     */
    private android.graphics.drawable.GradientDrawable surface(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    /**
     * A grouped panel. Related settings sharing one surface is what stops this
     * reading as an undifferentiated wall of text fields.
     */
    private LinearLayout card(ViewGroup parent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(surface(CARD, LINE, 16));
        l.setPadding(dp(14), dp(4), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        l.setLayoutParams(lp);
        parent.addView(l);
        return l;
    }

    private TextView heading2(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(java.util.Locale.US));
        t.setTextColor(ACCENT);
        t.setTextSize(11);
        t.setLetterSpacing(0.16f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(12);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(2), dp(2), dp(2), dp(14));
        return t;
    }

    private EditText field(ViewGroup parent, String label, String value, boolean secret) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(MUTED);
        l.setTextSize(11);
        l.setLetterSpacing(0.06f);
        l.setPadding(dp(2), dp(12), 0, dp(6));
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(FG);
        e.setTextSize(14);
        e.setBackground(surface(INPUT, LINE, 10));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setSingleLine(true);
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(e);
        return e;
    }

    /** An on/off row backed directly by a pref - no Save needed. */
    private android.widget.Switch toggle(ViewGroup parent, String label, final String key,
                                         final boolean def) {
        android.widget.Switch s = new android.widget.Switch(this);
        s.setText(label);
        s.setTextColor(FG);
        s.setTextSize(15);
        s.setPadding(dp(2), dp(14), dp(2), dp(4));
        s.setChecked(Prefs.bool(this, key, def));
        s.setOnCheckedChangeListener(new android.widget.CompoundButton
                .OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                // The boolean overload: Prefs.bool reads with getBoolean, and a
                // String written under the same key throws ClassCastException.
                Prefs.put(MainActivity.this, key, on);
            }
        });
        parent.addView(s);
        return s;
    }

    private Button button(ViewGroup parent, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(surface(ACCENT, 0, 12));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        parent.addView(b);
        return b;
    }
}
