package com.iohelper.card;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The live-voice protocol, so the service need not know whose it is.
 *
 * TWO BACKENDS, ONE PIPELINE. GPT-Live and Gemini Live both carry a full-duplex
 * conversation over a WebSocket, and agree on almost nothing else: the URL,
 * where the key goes, the shape of every message, and even the microphone
 * sample rate all differ. Everything downstream - the microphone, the player,
 * the cards, the delegation to {@link AssistantService}, the hang-up once media
 * starts - is identical and worth KEEPING identical, so the differences are
 * gathered here rather than sprinkled through TalkService as "if (gemini)".
 *
 * THE INTERESTING PART IS DELEGATION. GPT-Live has a first-class notion of
 * handing a request back to the client in prose; Gemini does not - it calls
 * FUNCTIONS. So Gemini is given exactly ONE function, ask_phone(request), which
 * reproduces the same shape: the model asks in words, the phone answers in
 * words. That keeps one backend, one dispatcher and one set of cards behind
 * both mouths, rather than teaching Gemini the whole tool manifest and then
 * owning two different things that can go wrong.
 */
interface Live {

    /** Normalised events: the service implements this, the protocols call it. */
    interface Sink {
        void onStarted();

        void onAudio(byte[] pcm);

        /** A fragment of what the WEARER said. */
        void onHeard(String delta);

        /** A fragment of what the MODEL said. */
        void onSaid(String delta);

        /**
         * The model wants the phone to answer something.
         *
         * @param id      what to quote when replying, or null
         * @param request the words, when the protocol carries them. NULL means
         *                "take the question from the transcript", which is how
         *                GPT-Live delegates - it hands over the turn, not text.
         */
        void onDelegate(String id, String request);

        void onClosed(String reason);

        void onError(String message);
    }

    String name();

    /** Null when configured; otherwise why it cannot start. */
    String unconfigured(Context ctx);

    String url(Context ctx);

    Map<String, String> headers(Context ctx);

    /** Microphone capture rate. GPT-Live wants 24 kHz, Gemini 16 kHz. */
    int micRate();

    /** Playback rate. Both send 24 kHz back. */
    int playRate();

    /** The first message, which opens the session. */
    JSONObject setup(Context ctx) throws Exception;

    /** One microphone chunk, already base64. */
    String audioFrame(String b64) throws Exception;

    void parse(JSONObject ev, Sink sink);

    /** The reply to a delegation, or null when this one takes no reply. */
    JSONObject answer(String id, String text) throws Exception;

    /** A polite close, or null to just drop the socket. */
    JSONObject bye() throws Exception;

    /** Whether the close is acknowledged, so the hang-up knows to wait for it. */
    boolean acknowledgesClose();

    /** Gemini unless the wearer has chosen otherwise. */
    static Live of(Context ctx) {
        return "openai".equalsIgnoreCase(Prefs.str(ctx, Prefs.TALK_BACKEND, "gemini"))
                ? new OpenAi() : new Gemini();
    }

    // ---------------------------------------------------------------- OpenAI
    /** GPT-Live, measured live from a desktop before the first version. */
    final class OpenAi implements Live {

        @Override
        public String name() {
            return "gpt-live-1";
        }

        @Override
        public String unconfigured(Context ctx) {
            return Prefs.str(ctx, Prefs.OPENAI_KEY, "").isEmpty()
                    ? "OpenAI API key not set" : null;
        }

        @Override
        public String url(Context ctx) {
            return "wss://api.openai.com/v1/live/sessions";
        }

        @Override
        public Map<String, String> headers(Context ctx) {
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", "Bearer " + Prefs.str(ctx, Prefs.OPENAI_KEY, ""));
            h.put("User-Agent", "iohelper");
            return h;
        }

        @Override
        public int micRate() {
            return 24000;
        }

        @Override
        public int playRate() {
            return 24000;
        }

        @Override
        public JSONObject setup(Context ctx) throws Exception {
            JSONObject session = new JSONObject()
                    .put("model", "gpt-live-1")
                    .put("instructions", TalkService.VOICE_PROMPT)
                    .put("audio", new JSONObject().put("output", new JSONObject()
                            .put("voice", Prefs.str(ctx, Prefs.TALK_VOICE, "marin"))))
                    .put("delegation", new JSONObject().put("type", "client"));
            return new JSONObject().put("type", "session.start")
                    .put("event_id", "start").put("session", session);
        }

        @Override
        public String audioFrame(String b64) {
            return "{\"type\":\"session.input_audio.append\",\"audio\":\"" + b64 + "\"}";
        }

        @Override
        public void parse(JSONObject ev, Sink sink) {
            switch (ev.optString("type", "")) {
                case "session.started":
                    sink.onStarted();
                    break;
                case "session.output_audio.delta":
                    sink.onAudio(Base64.decode(ev.optString("delta", ""), Base64.DEFAULT));
                    break;
                case "session.input_transcript.delta":
                    sink.onHeard(ev.optString("delta", ""));
                    break;
                case "session.output_transcript.delta":
                    sink.onSaid(ev.optString("delta", ""));
                    break;
                case "session.delegation.created": {
                    JSONObject d = ev.optJSONObject("delegation");
                    // No request text: GPT-Live delegates the TURN, so the
                    // question is whatever the wearer just said.
                    sink.onDelegate(d == null ? null : d.optString("id", null), null);
                    break;
                }
                case "session.closed": {
                    JSONObject s = ev.optJSONObject("session");
                    JSONObject usage = s == null ? null : s.optJSONObject("usage");
                    sink.onClosed(ev.optString("reason", "?")
                            + (usage == null ? ""
                               : " after " + usage.optDouble("seconds", 0) + "s"));
                    break;
                }
                case "error": {
                    JSONObject err = ev.optJSONObject("error");
                    sink.onError(err == null ? "error" : err.optString("message", "error"));
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public JSONObject answer(String id, String text) throws Exception {
            return new JSONObject().put("type", "session.commentary.append")
                    .put("delegation_id", id == null ? JSONObject.NULL : id)
                    .put("content", text);
        }

        @Override
        public JSONObject bye() throws Exception {
            return new JSONObject().put("type", "session.close").put("event_id", "close");
        }

        @Override
        public boolean acknowledgesClose() {
            return true;
        }
    }

    // ---------------------------------------------------------------- Gemini
    /**
     * Gemini Live, over BidiGenerateContent.
     *
     * Three things bite if you assume it is OpenAI with different spelling:
     *
     *  - The MICROPHONE must be 16 kHz while the reply comes back at 24 kHz, so
     *    the two ends of the audio path no longer share one rate. That is why
     *    micRate() and playRate() exist at all.
     *  - The key rides in the QUERY STRING; this handshake carries no
     *    Authorization header.
     *  - Transcription is OFF unless asked for. The cards are built from the
     *    transcripts rather than from the audio, so without the two
     *    *AudioTranscription blocks the glasses would stay blank through a
     *    perfectly good conversation.
     */
    final class Gemini implements Live {

        static final String MODEL = "gemini-3.1-flash-live-preview";
        private static final String HOST =
                "wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

        @Override
        public String name() {
            return MODEL;
        }

        @Override
        public String unconfigured(Context ctx) {
            return Prefs.str(ctx, Prefs.GEMINI_KEY, "").isEmpty()
                    ? "Gemini API key not set" : null;
        }

        @Override
        public String url(Context ctx) {
            return HOST + "?key=" + Prefs.str(ctx, Prefs.GEMINI_KEY, "");
        }

        @Override
        public Map<String, String> headers(Context ctx) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "iohelper");
            return h;
        }

        @Override
        public int micRate() {
            return 16000;
        }

        @Override
        public int playRate() {
            return 24000;
        }

        @Override
        public JSONObject setup(Context ctx) throws Exception {
            // ONE function, standing in for GPT-Live's client delegation, so
            // the whole existing backend - phrase patterns, tools, cards -
            // serves both mouths unchanged.
            JSONObject ask = new JSONObject()
                    .put("name", "ask_phone")
                    .put("description",
                            "Ask the phone to answer or do something you cannot yourself: "
                            + "the user's own data (calendar, to-do list, notes, timers), "
                            + "an ACTION (play or control music, podcasts or radio, "
                            + "navigation, timers, reminders, notes), or a LIVE changing "
                            + "fact (weather, traffic, scores, prices, opening hours, "
                            + "news). Pass the request in plain words, naming anything you "
                            + "have already worked out on the user's behalf.")
                    .put("parameters", new JSONObject()
                            .put("type", "OBJECT")
                            .put("properties", new JSONObject()
                                    .put("request", new JSONObject()
                                            .put("type", "STRING")
                                            .put("description",
                                                    "The request, in plain words")))
                            .put("required", new JSONArray().put("request")));

            JSONObject speech = new JSONObject().put("voiceConfig",
                    new JSONObject().put("prebuiltVoiceConfig", new JSONObject()
                            .put("voiceName", voice(ctx))));

            JSONObject setup = new JSONObject()
                    .put("model", "models/" + MODEL)
                    .put("generationConfig", new JSONObject()
                            .put("responseModalities", new JSONArray().put("AUDIO"))
                            .put("speechConfig", speech))
                    .put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject()
                                    .put("text", TalkService.VOICE_PROMPT))))
                    .put("tools", new JSONArray().put(new JSONObject()
                            .put("functionDeclarations", new JSONArray().put(ask))))
                    .put("inputAudioTranscription", new JSONObject())
                    .put("outputAudioTranscription", new JSONObject());
            return new JSONObject().put("setup", setup);
        }

        /**
         * TALK_VOICE defaults to an OpenAI voice name, which Gemini rejects
         * outright, so its own names are honoured only once one has actually
         * been chosen for it.
         */
        private String voice(Context ctx) {
            String v = Prefs.str(ctx, Prefs.TALK_VOICE, "").trim();
            return v.isEmpty() || v.equalsIgnoreCase("marin") || v.equalsIgnoreCase("cedar")
                    ? "Kore" : v;
        }

        @Override
        public String audioFrame(String b64) throws Exception {
            // The rate belongs IN the mime type. Without it the far end falls
            // back to its own assumption and the speech is heard at the wrong
            // pitch and speed, which reads as a broken microphone.
            return new JSONObject().put("realtimeInput", new JSONObject()
                    .put("audio", new JSONObject()
                            .put("mimeType", "audio/pcm;rate=" + micRate())
                            .put("data", b64))).toString();
        }

        @Override
        public void parse(JSONObject ev, Sink sink) {
            if (ev.has("setupComplete")) {
                sink.onStarted();
                return;
            }
            JSONObject sc = ev.optJSONObject("serverContent");
            if (sc != null) {
                JSONObject turn = sc.optJSONObject("modelTurn");
                JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    JSONObject inline = p == null ? null : p.optJSONObject("inlineData");
                    String data = inline == null ? "" : inline.optString("data", "");
                    if (!data.isEmpty()) {
                        sink.onAudio(Base64.decode(data, Base64.DEFAULT));
                    }
                }
                JSONObject in = sc.optJSONObject("inputTranscription");
                if (in != null && !in.optString("text", "").isEmpty()) {
                    sink.onHeard(in.optString("text", ""));
                }
                JSONObject out = sc.optJSONObject("outputTranscription");
                if (out != null && !out.optString("text", "").isEmpty()) {
                    sink.onSaid(out.optString("text", ""));
                }
                return;
            }
            JSONObject call = ev.optJSONObject("toolCall");
            if (call != null) {
                JSONArray fns = call.optJSONArray("functionCalls");
                for (int i = 0; fns != null && i < fns.length(); i++) {
                    JSONObject f = fns.optJSONObject(i);
                    if (f == null) {
                        continue;
                    }
                    JSONObject args = f.optJSONObject("args");
                    String request = args == null ? null : args.optString("request", null);
                    sink.onDelegate(f.optString("id", null), request);
                }
                return;
            }
            if (ev.has("goAway")) {
                // A session limit or maintenance, not a fault; the socket close
                // follows on its own.
                sink.onClosed("goAway");
            }
        }

        @Override
        public JSONObject answer(String id, String text) throws Exception {
            // A reply names the call it answers. Unlike GPT-Live there is no
            // free-floating commentary channel, so an id-less answer would be
            // dropped - better to send nothing than to believe it landed.
            if (id == null || id.isEmpty()) {
                return null;
            }
            return new JSONObject().put("toolResponse", new JSONObject()
                    .put("functionResponses", new JSONArray().put(new JSONObject()
                            .put("id", id)
                            .put("name", "ask_phone")
                            .put("response", new JSONObject().put("result", text)))));
        }

        @Override
        public JSONObject bye() {
            return null;                   // no close frame; closing the socket is it
        }

        @Override
        public boolean acknowledgesClose() {
            return false;
        }
    }
}
