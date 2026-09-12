package com.iohelper.card;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Groq and Gemini over plain HTTPS. Port of llm.py, minus the Claude backend -
 * that one shells out to the Claude Code CLI, which has no Android build.
 *
 * Two hard-won details are preserved:
 *  - reasoning models (gpt-oss, qwen3, deepseek) spend hidden reasoning tokens
 *    out of max_tokens; at 200 they returned EMPTY or truncated answers, so the
 *    budget is 1024 and reasoning_effort is requested low;
 *  - an empty reply is an error, never a blank card.
 */
public final class Llm {

    public static final String GROQ_URL = "https://api.groq.com/openai/v1";
    public static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String OPENAI_URL = "https://api.openai.com/v1";
    /** Tool rounds per question. Two is the norm (call, then answer); six
     *  leaves room for a multi-part request without letting a confused model
     *  loop forever on the wearer's money. */
    private static final int MAX_ROUNDS = 6;
    /**
     * Length is a budget, not a rule.
     *
     * This used to say "ONE short sentence, under 90 characters", and it was
     * doing its job too well: asked about an opera on tonight's calendar the
     * model gave the name and the venue and stopped, because it had been told
     * to. Nothing was truncated - there was simply nothing more generated. The
     * glasses can carry three paced cards comfortably, so the budget is now
     * stated as one, and the model is told when spending it is worthwhile.
     */
    public static final String BRIEF =
            "Plain text only - no lists, no markdown, no line breaks. Default to ONE "
            + "short sentence under 90 characters: for a fact, a number, a time or a "
            + "yes/no, that is the whole answer. Spend more only when the question is "
            + "about an event, place, person or topic where the extra detail is the "
            + "point - then up to three short sentences and 300 characters, most "
            + "useful first, because the reader may only see the beginning. "
            // The budget above immediately bought a fabrication: asked about an
            // opera the model filled the extra room with "bring a coat as it's
            // outdoors" about an indoor theatre. Room to say more is room to
            // make more up, and the reader is walking out of the door acting on
            // this, so the licence has to come with the limit attached.
            // A command the parser missed lands here, and the model answered
            // "Buy oat milk completed." for an utterance that completed
            // nothing. A false confirmation is worse than a refusal: the user
            // believes the thing is handled and stops thinking about it.
            + "You CANNOT perform actions - you only answer questions. Never say you "
            + "have done, added, set, completed, played, sent or changed anything. If "
            + "you are asked to do something, say plainly that you could not do it. "
            + "Use ONLY what the context gives you and what you actually know. Never "
            + "invent specifics - prices, addresses, times, weather, or advice about "
            + "a venue you were not told about. Saying less is always better than "
            + "filling the space.";

    private Llm() {
    }

    public static class LlmException extends Exception {
        LlmException(String m) {
            super(m);
        }
    }

    private static String http(String url, String method, String body, String bearer, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "iohelper");
        if (bearer != null && !bearer.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (is != null) {
            byte[] chunk = new byte[8192];
            for (int n; (n = is.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
            }
            is.close();
        }
        String text = buf.toString("UTF-8");
        if (code >= 400) {
            throw new LlmException("HTTP " + code + ": " + text.substring(0, Math.min(200, text.length())));
        }
        return text;
    }

    /** Ask the configured backend. Returns the answer, or throws with a reason. */
    public static String ask(Context ctx, String prompt) throws Exception {
        String backend = Prefs.str(ctx, Prefs.BACKEND, "groq");
        return "gemini".equals(backend) ? gemini(ctx, prompt) : groq(ctx, prompt);
    }

    // ---- tool calling ----------------------------------------------------------

    /**
     * The system prompt for a model that can ACT. It replaces {@link #BRIEF},
     * whose central rule - "you cannot perform actions" - is exactly what is
     * no longer true. The rest of BRIEF's hard-won lessons carry over: plain
     * text, a length budget rather than a length rule, and no invented
     * specifics.
     */
    public static final String AGENT =
            "You are the voice assistant for a pair of smart glasses whose only display "
            + "is one short line of text; the user is usually walking or driving. You ACT "
            + "by calling tools, and only by calling tools: never say you have set, added, "
            + "played, started, cancelled or changed anything unless a tool result confirms "
            + "it. Use tools rather than guessing: the user's schedule -> get_calendar; "
            + "travel time, traffic or a commute -> get_directions; weather, scores, prices, "
            + "opening hours, news or any live fact -> search_web; timers, reminders, "
            + "to-dos, notes, music, radio, playback, navigation -> their tools; a phone "
            + "function not listed (flashlight, texting, calling, alarms) -> "
            + "ask_phone_assistant. Do not ask clarifying questions - make a sensible "
            + "choice and act. Never call the same tool twice with the same arguments. "
            + "Calendar lines or live search results already in the message answer the "
            + "question - use them rather than fetching again. "
            + "Reply in plain text: no lists, no markdown, no line breaks. Default to ONE "
            + "short sentence under 90 characters; use up to three short sentences and 300 "
            + "characters only when the detail is the point, most useful first. When an "
            + "action tool has run, its own result line is already on the glasses: if "
            + "nothing else was asked, reply with exactly the word OK and nothing more; "
            + "if something else was asked, answer only that and never restate what the "
            + "tool did. Use only what tools and the "
            + "context give you; never invent times, prices, addresses or advice. If a "
            + "tool reports a failure, say so plainly.";

    /** What a tool-assisted turn produced. */
    public static final class Turn {
        /** The model's final words; may be empty after an action. */
        public String text = "";
        /** Each action tool's own result line, in the order they ran. */
        public final List<String> actions = new ArrayList<>();
        public int calls;
        public int queries;
        /** An action drew its own cards (the assistant hand-off); show nothing. */
        public boolean silent;
        /** Card kind of the action that ran: "timer" keeps the countdown bar
         *  alive, exactly as the regex path posts it. */
        public String kind = "answer";

        /**
         * The model's words minus the agreed "nothing more" token. AGENT asks
         * for exactly "OK" after a pure action, so that reply is a signal, not
         * text - it was the only way to tell "I have nothing to add" from an
         * answer to the second half of a request without guessing from
         * length. A leading "OK." on a real answer is trimmed.
         */
        public String prose() {
            String t = text == null ? "" : text.trim();
            return t.replaceFirst("(?i)^ok(?:ay)?(?=$|[\\s.!,])[\\s.!,]*", "").trim();
        }

        /**
         * The line for the glasses. An action's line is ground truth and is
         * never replaced by the model's paraphrase of it; whatever the model
         * added beyond OK is appended, because it is the answer to the other
         * thing that was asked.
         */
        public String render() {
            String t = prose();
            if (actions.isEmpty()) {
                // A hand-off that draws its own cards is authoritative: the
                // model's sign-off must not land on top of them.
                return silent && queries == 0 ? "" : t;
            }
            StringBuilder sb = new StringBuilder();
            for (String a : actions) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(a);
            }
            return t.isEmpty() ? sb.toString() : sb + " " + t;
        }

        /** The card kind for what render() returns. */
        public String cardKind() {
            return "timer".equals(kind) && queries == 0 && prose().isEmpty() ? "timer" : "answer";
        }
    }

    /**
     * Ask, with the model free to call the assistant's functions (see
     * {@link Tools}). Groq speaks OpenAI's chat/completions shape; OpenAI's own
     * models take tools on /responses only - measured: chat/completions
     * refuses "function tools with reasoning_effort" for gpt-5.6 - so each
     * backend gets its native loop, over one shared manifest and dispatcher.
     * Gemini has no tool loop here and answers as before.
     */
    public static Turn askWithTools(Context ctx, String prompt) throws Exception {
        String backend = Prefs.str(ctx, Prefs.BACKEND, "groq");
        if ("gemini".equals(backend)) {
            Turn t = new Turn();
            t.text = gemini(ctx, prompt);
            return t;
        }
        // The model has no clock and no map. "Tomorrow" and "how far" are
        // unanswerable without both.
        String where = Loc.context(ctx);
        String system = AGENT + " Now: " + new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a",
                Locale.US).format(new Date()) + "." + (where == null ? "" : " " + where);
        Turn turn = "openai".equals(backend)
                ? responsesLoop(ctx, system, prompt)
                : chatLoop(ctx, system, prompt);
        if (turn.render().isEmpty() && !turn.silent) {
            // Rounds ran out, or the model went quiet after a failed tool. A
            // blank card would read as the assistant being dead.
            turn.text = "Couldn't complete that.";
        }
        return turn;
    }

    /** Text of a chat message, tolerating JSON null (a tool-call turn has none). */
    private static String contentOf(JSONObject msg) {
        return msg == null || msg.isNull("content") ? "" : msg.optString("content", "").trim();
    }

    /** Groq: OpenAI-compatible chat/completions with tools. */
    private static Turn chatLoop(Context ctx, String system, String prompt) throws Exception {
        String key = Prefs.str(ctx, Prefs.GROQ_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("Groq API key not set");
        }
        String model = Prefs.str(ctx, Prefs.GROQ_MODEL, "openai/gpt-oss-120b");
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        Turn turn = new Turn();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("temperature", 0.3);
            body.put("max_tokens", 1024);
            if (model.matches("(?i).*(gpt-oss|qwen3\\.8|deepseek|r1).*")) {
                body.put("reasoning_effort", "low");
            }
            body.put("tools", Tools.chatManifest());
            body.put("tool_choice", "auto");
            body.put("messages", msgs);
            String resp = http(GROQ_URL + "/chat/completions", "POST", body.toString(), key, 45000);
            JSONObject msg = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            JSONArray calls = msg.optJSONArray("tool_calls");
            String content = contentOf(msg);
            if (calls == null || calls.length() == 0) {
                turn.text = content;
                return turn;
            }
            // Echo a CLEAN assistant turn, not the server's message verbatim:
            // vendor extras (reasoning text and the like) are not part of the
            // request schema and some servers reject them.
            JSONObject echo = new JSONObject().put("role", "assistant").put("tool_calls", calls);
            echo.put("content", content.isEmpty() ? JSONObject.NULL : content);
            msgs.put(echo);
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                JSONObject fn = c.getJSONObject("function");
                String result = Tools.call(ctx, fn.optString("name", ""),
                        fn.optString("arguments", ""), turn);
                msgs.put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", c.optString("id", "")).put("content", result));
            }
        }
        return turn;
    }

    /**
     * OpenAI: /responses with tools, no server-side storage. Everything the
     * model emits - reasoning (carried as encrypted content), the function
     * calls, any message - is echoed back with the tool outputs appended, so
     * each round is self-contained and nothing about the wearer's day is
     * retained between requests.
     */
    private static Turn responsesLoop(Context ctx, String system, String prompt) throws Exception {
        String key = Prefs.str(ctx, Prefs.OPENAI_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("OpenAI API key not set");
        }
        String base = Prefs.str(ctx, Prefs.OPENAI_BASE, OPENAI_URL).replaceAll("/+$", "");
        String model = Prefs.str(ctx, Prefs.OPENAI_MODEL, "gpt-5.6-luna");
        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "user").put("content", prompt));
        Turn turn = new Turn();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("instructions", system);
            body.put("input", input);
            body.put("tools", Tools.responsesManifest());
            body.put("tool_choice", "auto");
            body.put("max_output_tokens", 1024);
            body.put("store", false);
            body.put("reasoning", new JSONObject().put("effort", "low"));
            body.put("include", new JSONArray().put("reasoning.encrypted_content"));
            String resp = http(base + "/responses", "POST", body.toString(), key, 45000);
            JSONArray output = new JSONObject(resp).getJSONArray("output");
            List<JSONObject> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.getJSONObject(i);
                String type = item.optString("type", "");
                if ("function_call".equals(type)) {
                    calls.add(item);
                } else if ("message".equals(type)) {
                    JSONArray parts = item.optJSONArray("content");
                    for (int p = 0; parts != null && p < parts.length(); p++) {
                        JSONObject part = parts.optJSONObject(p);
                        if (part != null && "output_text".equals(part.optString("type", ""))) {
                            text.append(part.optString("text", ""));
                        }
                    }
                }
            }
            if (calls.isEmpty()) {
                turn.text = text.toString().trim();
                return turn;
            }
            for (int i = 0; i < output.length(); i++) {
                input.put(output.getJSONObject(i));
            }
            for (JSONObject c : calls) {
                String result = Tools.call(ctx, c.optString("name", ""),
                        c.optString("arguments", ""), turn);
                input.put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", c.optString("call_id", "")).put("output", result));
            }
        }
        return turn;
    }

    private static String groq(Context ctx, String prompt) throws Exception {
        String key = Prefs.str(ctx, Prefs.GROQ_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("Groq API key not set");
        }
        String model = Prefs.str(ctx, Prefs.GROQ_MODEL, "openai/gpt-oss-120b");
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 1024);
        if (model.matches("(?i).*(gpt-oss|qwen3\\.8|deepseek|r1).*")) {
            body.put("reasoning_effort", "low");   // other qwen3 ids accept only none/default
        }
        JSONArray msgs = new JSONArray();
        // Give the model the phone's position: "how far is that", "is it open",
        // "which way" are all unanswerable without it, and it otherwise guesses.
        String where = Loc.context(ctx);
        msgs.put(new JSONObject().put("role", "system")
                .put("content", where == null ? BRIEF : BRIEF + " " + where));
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", msgs);

        String resp = http(GROQ_URL + "/chat/completions", "POST", body.toString(), key, 45000);
        JSONObject o = new JSONObject(resp);
        String text = o.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim();
        if (text.isEmpty()) {
            throw new LlmException(model + " returned no visible text");
        }
        return text;
    }

    private static String gemini(Context ctx, String prompt) throws Exception {
        String key = Prefs.str(ctx, Prefs.GEMINI_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("Gemini API key not set");
        }
        String model = Prefs.str(ctx, Prefs.GEMINI_MODEL, "gemini-2.5-flash");
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        contents.put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
        body.put("contents", contents);
        body.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", BRIEF))));
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0.3).put("maxOutputTokens", 400));

        String resp = http(GEMINI_URL + "/models/" + model + ":generateContent?key=" + key,
                "POST", body.toString(), null, 45000);
        JSONObject o = new JSONObject(resp);
        JSONArray parts = o.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (!p.optBoolean("thought", false)) {
                sb.append(p.optString("text", ""));
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new LlmException("Gemini returned no text");
        }
        return text;
    }

    /**
     * A machine-only system prompt for turning a spoken music DESCRIPTION into a
     * concrete artist + title. Deliberately NOT BRIEF: BRIEF forbids naming
     * specifics and demands one plain sentence, the opposite of what a resolver
     * must do. The output is JSON the app parses and never shows, so BRIEF's
     * "never claim you did anything" has nothing to guard here - the
     * anti-hallucination gate is elsewhere: the app VERIFIES the named title
     * against Spotify /search before anything plays (see Media.findAlbum).
     */
    public static final String RESOLVER =
            "You turn a loose spoken description of music into the exact name of ONE real "
            + "release, for a voice assistant that will then look it up on Spotify itself. You "
            + "have no Spotify access and you never play anything - you only name what is "
            + "described. Reply with ONE JSON object and nothing else: "
            + "{\"artist\":\"<performer>\",\"title\":\"<exact album or song title>\","
            + "\"kind\":\"album\" or \"track\",\"confident\":true or false}. "
            + "Rules: title is the real released title only - no year, no the word 'album', no "
            + "descriptive words. debut/first = the artist's first studio album; "
            + "latest/newest/most recent = the most recent studio album you are sure exists; an "
            + "ordinal (second, third, ...) counts studio albums in release order; 'greatest "
            + "hits'/'best of' = the real compilation title if you know it, else title "
            + "\"Greatest Hits\" with kind album. 'the album with X' or 'the one where ...' = "
            + "the album or song that actually contains X. 'song from <movie/show/game>' = the "
            + "performing artist's recording with kind track, not the composer, unless it is an "
            + "instrumental score. If the description already names a plain title, echo it back. "
            + "Set confident=false whenever you are guessing, the artist or release is "
            + "ambiguous, or it may be newer than your knowledge. Never invent a title to be "
            + "helpful.";

    /**
     * Resolve a descriptive music request to {artist, title, kind, confident},
     * or null when the backend is unreachable or returns nothing usable. NEVER
     * throws: the caller degrades to the plain literal search on null, so a
     * missing key or a network drop can never surface as a command error.
     *
     * @param kindHint "album" when the user said album/playlist, else null.
     */
    public static org.json.JSONObject resolveMusic(Context ctx, String description, String kindHint) {
        String user = "Request: \"" + description + "\"."
                + (kindHint == null ? "" : " The user wants an " + kindHint + ".")
                + " Identify it and return only the JSON object.";
        try {
            String backend = Prefs.str(ctx, Prefs.BACKEND, "groq");
            String raw = "gemini".equals(backend)
                    ? geminiJson(ctx, RESOLVER, user, 512)
                    : groqJson(ctx, RESOLVER, user, 512);
            return firstJsonObject(raw);
        } catch (Exception e) {
            android.util.Log.i("iohelperLlm", "resolveMusic failed: " + e);
            return null;
        }
    }

    /**
     * A machine-only system prompt for a CURATED request - a genre, era, mood,
     * activity, or "top N" / "best of" set - rather than one named release. The
     * model names real, well-known songs; each is then verified on Spotify
     * (Media.verifyQueue -> findTrack) before it is queued, so the model can
     * name anything but only correctly-credited real recordings ever play.
     */
    public static final String CURATOR =
            "You are the music curator for a voice assistant that plays songs on a speaker. "
            + "Given a request for a KIND of music - a genre, era, mood, activity, or a 'top N' "
            + "/ 'best of' list - reply with a JSON object naming specific, well-known real "
            + "songs that fit, most iconic first: "
            + "{\"label\":\"<a short name for the set, e.g. New Wave - Top 10>\","
            + "\"tracks\":[{\"artist\":\"<performer>\",\"title\":\"<song title>\"}]}. "
            + "Give the requested number of tracks (default 10) by DIFFERENT well-known artists "
            + "where possible, each a real, popular recording a listener would recognise as "
            + "belonging to that request. Use exact artist and song names as they appear on "
            + "streaming services. No karaoke, tribute, or cover versions. If the request "
            + "instead names a SPECIFIC artist, album, or single song rather than a category, "
            + "return an empty tracks array. Reply with ONE JSON object and nothing else.";

    /**
     * Resolve a curated/genre/mood/"top N" request to a plan of named tracks, or
     * null when the backend is unreachable or returns nothing usable. Never
     * throws; the caller verifies every named track before playing and falls
     * back to a genre playlist on null.
     */
    public static org.json.JSONObject resolveList(Context ctx, String description, int count) {
        String user = "Request: \"" + description + "\". Give " + count
                + " tracks. Return only the JSON object.";
        try {
            String backend = Prefs.str(ctx, Prefs.BACKEND, "groq");
            String raw = "gemini".equals(backend)
                    ? geminiJson(ctx, CURATOR, user, 2048)
                    : groqJson(ctx, CURATOR, user, 2048);
            return firstJsonObject(raw);
        } catch (Exception e) {
            android.util.Log.i("iohelperLlm", "resolveList failed: " + e);
            return null;
        }
    }

    /** Groq chat with a custom system prompt and JSON response mode. */
    private static String groqJson(Context ctx, String system, String user, int maxTokens) throws Exception {
        String key = Prefs.str(ctx, Prefs.GROQ_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("Groq API key not set");
        }
        String model = Prefs.str(ctx, Prefs.GROQ_MODEL, "openai/gpt-oss-120b");
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);  // gpt-oss spends hidden reasoning tokens; keep room
        body.put("response_format", new JSONObject().put("type", "json_object"));
        if (model.matches("(?i).*(gpt-oss|qwen3\\.8|deepseek|r1).*")) {
            body.put("reasoning_effort", "low");   // other qwen3 ids accept only none/default
        }
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);
        // 20s, not the 45s of the Q&A path: a play command must not hang.
        String resp = http(GROQ_URL + "/chat/completions", "POST", body.toString(), key, 20000);
        return new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "");
    }

    /** Gemini with a custom system instruction and JSON response mode. */
    private static String geminiJson(Context ctx, String system, String user, int maxTokens) throws Exception {
        String key = Prefs.str(ctx, Prefs.GEMINI_KEY, "");
        if (key.isEmpty()) {
            throw new LlmException("Gemini API key not set");
        }
        String model = Prefs.str(ctx, Prefs.GEMINI_MODEL, "gemini-2.5-flash");
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", user)))));
        body.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0).put("maxOutputTokens", maxTokens)
                .put("responseMimeType", "application/json"));
        String resp = http(GEMINI_URL + "/models/" + model + ":generateContent?key=" + key,
                "POST", body.toString(), null, 20000);
        JSONArray parts = new JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject pt = parts.getJSONObject(i);
            if (!pt.optBoolean("thought", false)) {
                sb.append(pt.optString("text", ""));
            }
        }
        return sb.toString();
    }

    /** Salvage the first {...} object from a reply, tolerating prose or ``` fences. */
    private static org.json.JSONObject firstJsonObject(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf('{');
        int j = s.lastIndexOf('}');
        if (i < 0 || j <= i) {
            return null;
        }
        try {
            return new JSONObject(s.substring(i, j + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** Model ids the key can use (for the settings screen). Empty on failure. */
    public static java.util.List<String> models(Context ctx, String backend) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if ("gemini".equals(backend)) {
                String key = Prefs.str(ctx, Prefs.GEMINI_KEY, "");
                if (key.isEmpty()) {
                    return out;
                }
                JSONObject o = new JSONObject(http(GEMINI_URL + "/models?pageSize=200&key=" + key,
                        "GET", null, null, 15000));
                JSONArray a = o.getJSONArray("models");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject m = a.getJSONObject(i);
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    if (methods != null && methods.toString().contains("generateContent")) {
                        out.add(m.getString("name").replaceFirst("^models/", ""));
                    }
                }
            } else {
                boolean openai = "openai".equals(backend);
                String key = Prefs.str(ctx, openai ? Prefs.OPENAI_KEY : Prefs.GROQ_KEY, "");
                if (key.isEmpty()) {
                    return out;
                }
                String base = openai
                        ? Prefs.str(ctx, Prefs.OPENAI_BASE, OPENAI_URL).replaceAll("/+$", "")
                        : GROQ_URL;
                JSONObject o = new JSONObject(http(base + "/models", "GET", null, key, 15000));
                JSONArray a = o.getJSONArray("data");
                for (int i = 0; i < a.length(); i++) {
                    out.add(a.getJSONObject(i).getString("id"));
                }
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out);
        return out;
    }
}
