package com.iohelper.card;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wake-word gate. A faithful port of wake.py - every rule here was learned
 * the hard way against real transcripts from an always-recording device:
 *
 *  - STRICT word-level matching. A fuzzy window over long sentences answered
 *    ambient conversation.
 *  - The ASR mangles the wake word ("Jarvis" -> Travis/Jervis). A short,
 *    standalone near-miss SOFT-arms and fires only if a request-shaped
 *    utterance follows, and the arm is consumed by that one follow-up, so a
 *    stray word can never capture later speech.
 *  - The glasses' VAD truncates the HEAD of utterances, so a TRAILING wake word
 *    survives ("...to SF, Jarvis?") and must be stripped from the query.
 *  - RayNeo can publish the question 19-39 s after the wake word, so a bare
 *    wake word arms for a long window, shape-gated after the first few seconds.
 *
 * Pure logic: no I/O, so it stays replayable against captured logs.
 */
public final class Wake {

    private static final Set<String> REQUEST_OPENERS = new HashSet<>(Arrays.asList(
            "what", "whats", "where", "when", "who", "whose", "why", "how", "which",
            "can", "could", "would", "will", "do", "does", "did", "is", "are", "was",
            "were", "should", "tell", "show", "find", "search", "check", "give", "set",
            "start", "add", "remind", "create", "mark", "navigate", "calculate", "look",
            "play", "open", "close", "turn", "text", "call", "translate", "define"));

    private static final Pattern WORDS = Pattern.compile("[a-z0-9]+");
    private static final Pattern TOKENS = Pattern.compile("[A-Za-z0-9']+");
    private static final Pattern LEAD = Pattern.compile(
            "\\s*[\"']*([A-Za-z0-9']+)([\"',.:;!?-]*)\\s*([\\s\\S]*)$");

    private Wake() {
    }

    /** Mutable per-session state (the arm windows and the dedup memory). */
    public static final class State {
        public double armedAt;
        public double softArmedAt;
        public String lastRequest = "";
        public double lastRequestAt;
        public String lastText = "";
        public double lastTextAt;
    }

    /** What to do with one transcript. */
    public static final class Result {
        public final String action;   // fire-hard | fire-hard-followup | fire-soft-* | hard-arm | soft-arm | ignore*
        public final String query;    // non-null when action starts with "fire"

        Result(String action, String query) {
            this.action = action;
            this.query = query;
        }

        public boolean fires() {
            return action.startsWith("fire");
        }
    }

    static int lev(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static List<String> words(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Matcher m = WORDS.matcher(text.toLowerCase());
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** True when a real spoken token (or an ASR split of one) matches the wake word. */
    public static boolean triggerHit(String trigger, String text) {
        String nt = norm(trigger);
        if (nt.isEmpty()) {
            return true;
        }
        int tol = nt.length() >= 5 ? 1 : 0;
        List<String> ws = words(text);
        for (int i = 0; i < ws.size(); i++) {
            String w = ws.get(i);
            if (w.equals(nt) || (Math.abs(w.length() - nt.length()) <= 1 && lev(w, nt) <= tol)) {
                return true;
            }
            if (i + 1 < ws.size()) {
                String combo = w + ws.get(i + 1);
                if (Math.abs(combo.length() - nt.length()) <= 1 && lev(combo, nt) <= tol) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Remove a leading AND/OR trailing wake word, tolerating ASR splits. */
    public static String stripTrigger(String trigger, String text) {
        String nt = norm(trigger);
        String[] parts = text.trim().split("\\s+");
        java.util.ArrayList<String> ws = new java.util.ArrayList<>(Arrays.asList(parts));
        StringBuilder acc = new StringBuilder();
        int i = 0;
        while (i < ws.size()) {
            String cand = norm(acc + ws.get(i));
            if (!cand.isEmpty() && (nt.contains(cand) || nt.startsWith(cand) || lev(cand, nt) <= 2)) {
                acc.append(ws.get(i));
                i++;
                continue;
            }
            break;
        }
        ws = new java.util.ArrayList<>(ws.subList(i, ws.size()));
        if (!ws.isEmpty() && isWake(norm(ws.get(ws.size() - 1)), nt)) {
            ws.remove(ws.size() - 1);
        } else if (ws.size() >= 2
                && isWake(norm(ws.get(ws.size() - 2) + ws.get(ws.size() - 1)), nt)) {
            ws.remove(ws.size() - 1);
            ws.remove(ws.size() - 1);
        }
        return String.join(" ", ws).replaceAll("^[ ,.-]+|[ ,.-]+$", "");
    }

    private static boolean isWake(String cand, String nt) {
        return !cand.isEmpty() && Math.abs(cand.length() - nt.length()) <= 1 && lev(cand, nt) <= 1;
    }

    /**
     * If the text opens with a known/near mangle of the wake word, return what
     * follows ("" when the utterance was only the mangle); null otherwise.
     * A mangle that merely starts a longer sentence ("Travis is coming over
     * later") is conversation, not a call - it needs a separator after the word.
     */
    public static String softTriggerRest(String trigger, String text, List<String> softList) {
        String nt = norm(trigger);
        Matcher m = LEAD.matcher(text);
        if (nt.isEmpty() || !m.matches()) {
            return null;
        }
        String first = norm(m.group(1));
        String punct = m.group(2);
        String rest = m.group(3);
        int tokenCount = 0;
        Matcher t = TOKENS.matcher(text);
        while (t.find()) {
            tokenCount++;
        }
        boolean explicit = false;
        if (softList != null) {
            for (String s : softList) {
                if (norm(s).equals(first)) {
                    explicit = true;
                    break;
                }
            }
        }
        boolean near = Math.abs(first.length() - nt.length()) <= 2 && lev(first, nt) <= 2;
        if (!(explicit || (near && tokenCount <= 3))) {
            return null;
        }
        if (!rest.isEmpty() && punct.isEmpty() && tokenCount > 3) {
            return null;
        }
        return rest.replaceAll("^[\\s\"',.:;!?-]+|[\\s\"',.:;!?-]+$", "");
    }

    public static boolean looksLikeRequest(String text) {
        List<String> ws = words(text);
        if (ws.size() < 2) {
            return false;
        }
        return text.contains("?") || REQUEST_OPENERS.contains(ws.get(0));
    }

    /** True when this request already fired very recently (acoustic retries). */
    public static boolean duplicateRequest(State st, String query, double now, double window) {
        String key = norm(query);
        if (key.isEmpty()) {
            return false;
        }
        boolean dup = key.equals(st.lastRequest) && now - st.lastRequestAt <= window;
        if (!dup) {
            st.lastRequest = key;
            st.lastRequestAt = now;
        }
        return dup;
    }

    public static Result classify(String trigger, String text, State st, double now,
                                  List<String> softList, double armWindow,
                                  double fastWindow, double softArmWindow) {
        if (trigger == null || trigger.isEmpty()) {
            return new Result("fire", text);
        }
        double hardArmed = st.armedAt;
        if (triggerHit(trigger, text)) {
            st.softArmedAt = 0;
            String rest = stripTrigger(trigger, text);
            if (norm(rest).length() >= 4) {
                st.armedAt = 0;
                return new Result("fire-hard", rest);
            }
            st.armedAt = now;
            return new Result("hard-arm", null);
        }
        if (hardArmed > 0) {
            st.armedAt = 0;
            double age = now - hardArmed;
            if (age <= fastWindow || (age <= armWindow && looksLikeRequest(text))) {
                return new Result("fire-hard-followup", text);
            }
        }
        String softRest = softTriggerRest(trigger, text, softList);
        if (softRest != null) {
            if (!softRest.isEmpty() && looksLikeRequest(softRest)) {
                st.softArmedAt = 0;
                return new Result("fire-soft-inline", softRest);
            }
            if (softRest.isEmpty()) {
                st.softArmedAt = now;
                return new Result("soft-arm", null);
            }
            st.softArmedAt = 0;
            return new Result("ignore-soft-inline", null);
        }
        double softArmed = st.softArmedAt;
        if (softArmed > 0) {
            st.softArmedAt = 0;                 // consumed by exactly one follow-up
            if (now - softArmed <= softArmWindow && looksLikeRequest(text)) {
                return new Result("fire-soft-followup", text);
            }
            return new Result("ignore-soft-followup", null);
        }
        return new Result("ignore", null);
    }
}
