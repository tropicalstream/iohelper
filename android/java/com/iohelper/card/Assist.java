package com.iohelper.card;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Asking the PHONE's Gemini a question and putting its answer on the glasses.
 *
 * Gemini has no API to call here and no notification to read: it is an app with
 * a screen. So this drives that screen, and every step of the route was chosen
 * because the obvious one did not work:
 *
 *  - ACTION_ASSIST and ACTION_VOICE_COMMAND both resolve to an app CHOOSER on
 *    this device, so the question travels as an ACTION_SEND text/plain intent
 *    to the Gemini app instead, which opens it with the words already typed in.
 *  - That intent PRE-FILLS but does not submit, and the keyboard's Enter key
 *    inserts a newline rather than sending, so the Send control has to be
 *    pressed. It is a Compose node: it carries the description "Send" but
 *    neither the clickable flag nor a click action of its own, so the click
 *    goes to the nearest ANCESTOR that will take one.
 *  - The reply is not in any notification. It is drawn, in nodes whose id ends
 *    assistant_robin_text, and that is where it is read from.
 *
 * The answer STREAMS, so it is sampled until it stops growing rather than read
 * once - reading eagerly would put half a sentence on the lens.
 *
 * Nothing here runs unless a question is actually pending: no question, and
 * events from Gemini are dropped without a single node being read.
 */
public final class Assist {

    private static final String TAG = "iohelperAssist";
    static final String GEMINI_APP = "com.google.android.apps.bard";
    static final String GEMINI_UI = "com.google.android.googlequicksearchbox";
    /** The answer's own text nodes, as measured on this build. */
    private static final String ANSWER_ID = "assistant_robin_text";
    /** Gemini's echo of what was asked, which marks where our turn begins. */
    private static final String QUESTION_ID = "assistant_robin_user_message_text";
    /** The collapsed prompt box, which has to be tapped before it is editable. */
    private static final String INPUT_ID = "assistant_robin_input_collapsed_text_half_sheet";
    /** Long enough for the send control to exist before we hunt for it. */
    private static final long SEND_GRACE_MS = 1200;
    /** Answer considered complete once it stops growing for this long. */
    private static final long STABLE_MS = 1800;
    /**
     * How long to wait for PROSE after the question is posted before deciding
     * there is not going to be any. An action - "set a timer for 3 minutes" -
     * is answered with a Timer card and no text at all, so waiting for words
     * that will never come used to run to the full timeout and then report
     * "Gemini did not answer" about a timer that had been ticking for a
     * minute. Silence after a successful submit means it acted, not that it
     * failed.
     */
    private static final long NO_TEXT_MS = 11000;
    /**
     * Give up rather than leave the wearer waiting on a card forever. Generous,
     * because the send may be gated behind Android's "share it with Gemini?"
     * consent prompt, and that is a human tap - not something this should ever
     * click for you.
     */
    private static final long TIMEOUT_MS = 90000;

    private Assist() {
    }

    private static volatile String question;
    private static volatile long askedAt;
    private static volatile boolean sent;
    private static volatile boolean typed;
    private static volatile String lastSeen = "";
    private static volatile long lastGrewAt;

    /** Whether a question is in flight; the listener does nothing otherwise. */
    static boolean pending() {
        return question != null;
    }

    /**
     * Send a question to Gemini. Returns the line for the glasses immediately -
     * the answer arrives later, as its own card, because Gemini takes seconds
     * to think and the assistant must not block waiting for it.
     */
    public static String ask(Context ctx, String q) {
        if (q == null || q.trim().isEmpty()) {
            return Media.assistant(ctx, null);       // no question: just open it
        }
        question = q.trim();
        askedAt = System.currentTimeMillis();
        sent = false;
        typed = false;
        lastSeen = "";
        lastGrewAt = 0;
        try {
            // Opened EMPTY, and the words are typed in afterwards. Handing them
            // over as an ACTION_SEND extra also works, but Android then asks
            // "this content was automatically added from another app - share
            // it with Gemini?" on every single request, and that consent
            // prompt is not something this should ever answer for you. Text
            // set into the app's own field is not cross-app content, so no
            // prompt appears and the whole thing stays hands-free.
            LocalAdb.shell(ctx, "am start -n " + GEMINI_APP
                    + "/.shellapp.BardEntryPointActivity", 12000);
        } catch (Exception e) {
            question = null;
            return "Could not reach Gemini (" + e + ")";
        }
        POLL.removeCallbacks(TICK);
        POLL.postDelayed(TICK, 1500);
        // Posted HERE, not returned. A returned line goes through the
        // assistant's deferred path, which waits for RayNeo's own voice to go
        // quiet - up to 30s - so "Asking Gemini…" was landing on the lens long
        // after the command had already run. A progress card that arrives
        // after the work is worse than none. Returning null leaves the outcome
        // card below as the only one.
        Cards.post(ctx, Cards.title(ctx), "◇ Asking Gemini…", "answer");
        return null;
    }

    /**
     * Press the keyboard's own "go" on a field, when there is no Send control
     * to find by name. Best-effort: the result is deliberately ignored, since
     * it is true whether or not anything was submitted.
     */
    private static void imeEnter(AccessibilityNodeInfo edit) {
        if (edit == null || android.os.Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            edit.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        } catch (Exception ignored) {
            // A stale node; the next pass finds a fresh one.
        }
    }

    /** Single-quote for the phone's shell, closing around any embedded quote. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Driven by the accessibility service on every Gemini screen change while a
     * question is pending: press Send, then watch the reply until it settles.
     */
    static void onEvent(AccessibilityService svc) {
        step(svc);
    }

    private static final android.os.Handler POLL =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * Gemini stops emitting accessibility events once it has finished
     * streaming - which is exactly the moment we are waiting for. Driving the
     * state machine off events alone therefore stalls forever on the last
     * chunk: measured live, the answer was on screen and readable while the
     * listener sat waiting for one more event that was never coming. So it is
     * also polled.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!pending()) {
                return;
            }
            AccessibilityService svc = CaptionListener.service();
            if (svc != null) {
                step(svc);
            }
            if (pending()) {
                POLL.postDelayed(this, 700);
            }
        }
    };

    private static void step(AccessibilityService svc) {
        String q = question;
        if (q == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - askedAt > TIMEOUT_MS) {
            finish(svc, null);
            return;
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            return;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !(GEMINI_UI.contentEquals(pkg) || GEMINI_APP.contentEquals(pkg))) {
            return;                                  // Gemini's own screens only
        }
        if (!typed) {
            // Set the text on the node directly rather than simulating
            // keystrokes: `input text` dropped the last character in testing
            // ("5 minute" from "5 minutes"), which for a command is not a typo
            // but a different instruction.
            AccessibilityNodeInfo edit = findEditable(root, 0);
            if (edit == null) {
                // The field is collapsed until it is tapped.
                AccessibilityNodeInfo box = findById(root, INPUT_ID, 0);
                if (box != null) {
                    clickThrough(box);
                }
                return;
            }
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, q);
            if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                typed = true;
                Log.i(TAG, "typed");
                // SUBMIT IN THE SAME PASS, while the field is certainly in
                // front of us and certainly holds the words.
                //
                // Typing and pressing Send used to be two passes about a
                // second apart, and anything that took the foreground in
                // between stranded the question typed-but-unsent: step() bails
                // on its package check the moment Gemini is not the active
                // window, so there was nothing left to press Send. That is
                // reachable in normal use - a live voice session ending brings
                // this app's own activity forward - and it looks exactly like
                // the request being ignored, because the text is sitting there
                // in the box.
                //
                // ACTION_IME_ENTER is the keyboard's own "go", so it needs no
                // button to find by name; the Send hunt below stays as the
                // fallback for anything that does not take it.
                imeEnter(edit);
            }
            return;
        }
        if (!sent) {
            // Already submitted? Tapping "Share" on Android's consent prompt
            // sends the question outright, so on that path there is no Send
            // control left to press - and waiting for one would hang here
            // forever while the answer streamed in unread. The question
            // appearing as a posted message IS the signal.
            if (questionSubmitted(root)) {
                sent = true;
                lastGrewAt = now;
                Log.i(TAG, "submitted");
                return;
            }
            // Give the prefilled screen a moment to lay out; hunting for Send
            // the instant the activity appears finds nothing.
            if (now - askedAt < SEND_GRACE_MS) {
                return;
            }
            // KEEP TRYING, AND NEVER CLAIM SUCCESS FROM A RETURN VALUE. Both
            // of these report true while doing nothing: ACTION_IME_ENTER was
            // measured returning true on Gemini's field without submitting,
            // and the run then read the PREVIOUS answer still on screen and
            // reported it as this question's - the same 355-of-757 characters
            // twice, for two different questions. Only the question appearing
            // as a posted message means it went, which is what the branch
            // above waits for.
            AccessibilityNodeInfo send = findByDesc(root, "send", 0);
            if (send != null) {
                clickThrough(send);
                return;
            }
            imeEnter(findEditable(root, 0));
            return;
        }
        String answer = answerOf(root);
        if (answer.isEmpty()) {
            if (now - lastGrewAt >= NO_TEXT_MS) {
                finish(svc, "");                     // acted, wordlessly
            }
            return;
        }
        if (answer.length() > lastSeen.length()) {
            lastSeen = answer;                       // still streaming
            lastGrewAt = now;
            return;
        }
        if (now - lastGrewAt >= STABLE_MS) {
            finish(svc, lastSeen);
        }
    }

    /** Put the answer on the glasses (or say plainly that none came). */
    private static void finish(AccessibilityService svc, String answer) {
        question = null;
        sent = false;
        typed = false;
        POLL.removeCallbacks(TICK);
        String text = answer == null ? "" : answer.trim();
        if (text.isEmpty()) {
            // Only claim failure when the command never actually landed.
            // answer == null is the timeout path; "" means Gemini took it and
            // simply had nothing to say, which is what acting looks like.
            boolean landed = answer != null;
            Cards.post(svc, Cards.title(svc),
                    landed ? "◇ Sent to Gemini." : "◇ Gemini did not answer.", "answer");
            Log.i(TAG, landed ? "acted (no text)" : "no answer");
            return;
        }
        // The OPENING, not the essay. Gemini answers at paragraph length and
        // the lens holds ~115 characters a card; the first two sentences are
        // reliably the actual answer, with the elaboration after them.
        String head = firstSentences(text, 2);
        Cards.postSequence(svc, Cards.title(svc), "◇ " + head, "answer");
        Log.i(TAG, "answered (" + head.length() + " of " + text.length() + " chars)");
    }

    /**
     * The first {@code n} sentences of a reply.
     *
     * A sentence ends at . ! or ? FOLLOWED BY WHITESPACE - never on the
     * punctuation alone. Gemini answers are full of decimals ("3,776.24
     * meters", "$1.5 billion"), and splitting on a bare full stop cuts them in
     * half, which turns a correct answer into a wrong one. Common
     * abbreviations are held back for the same reason: "Mt. Fuji" is not two
     * sentences.
     */
    static String firstSentences(String text, int n) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int found = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?') {
                continue;
            }
            if (!Character.isWhitespace(text.charAt(i + 1))) {
                continue;                            // inside a number: 3,776.24
            }
            if (endsWithAbbreviation(text, i)) {
                continue;                            // "Mt. Fuji", "U.S. law"
            }
            if (++found == n) {
                return text.substring(0, i + 1).trim();
            }
        }
        return text.trim();                          // fewer sentences than asked for
    }

    /** Titles and initialisms that end in a full stop without ending a sentence. */
    private static final String[] ABBREV = {
        "mt", "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "approx",
        "e.g", "i.e", "vs", "etc", "no", "fig", "est", "inc", "ft", "in",
    };

    private static boolean endsWithAbbreviation(String text, int dot) {
        int start = dot;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        String word = text.substring(start, dot).toLowerCase(Locale.US);
        // A single letter before the stop is an initial ("J. Smith"), and a
        // capitalised initialism ("U.S.") leaves only its last letter here.
        if (word.length() == 1) {
            return true;
        }
        for (String a : ABBREV) {
            if (word.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reply to OUR question - not everything on screen.
     *
     * Gemini keeps the whole conversation in one scrolling view, so an earlier
     * answer's text nodes are still present and a naive sweep concatenates
     * them: measured live, a question about Mount Fuji came back carrying the
     * previous answer about the Eiffel Tower. So the walk ignores everything
     * until it passes the user-message node holding the question we just sent,
     * and collects only what is drawn after it.
     */
    private static String answerOf(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        gather(root, sb, 0, new boolean[]{false});
        return Cards.sanitize(sb.toString());
    }

    private static void gather(AccessibilityNodeInfo n, StringBuilder out, int depth,
                               boolean[] past) {
        if (n == null || depth > 28 || out.length() > 1200) {
            return;
        }
        String id = n.getViewIdResourceName();
        CharSequence t = n.getText();
        if (id != null && t != null && t.length() > 0) {
            if (id.endsWith(QUESTION_ID) && sameQuestion(t.toString())) {
                past[0] = true;                      // ours starts here
                out.setLength(0);                    // drop any earlier turn
            } else if (past[0] && id.endsWith(ANSWER_ID)) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(t.toString().trim());
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            gather(n.getChild(i), out, depth + 1, past);
        }
    }

    /** The first editable node - Gemini's prompt box once it has expanded. */
    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 28) {
            return null;
        }
        if (n.isEditable()) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findEditable(n.getChild(i), depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findById(AccessibilityNodeInfo n, String idSuffix,
                                                  int depth) {
        if (n == null || depth > 28) {
            return null;
        }
        String id = n.getViewIdResourceName();
        if (id != null && id.endsWith(idSuffix)) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findById(n.getChild(i), idSuffix, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Whether our question is already posted as a message in the thread. */
    private static boolean questionSubmitted(AccessibilityNodeInfo root) {
        return findQuestion(root, 0);
    }

    private static boolean findQuestion(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 28) {
            return false;
        }
        String id = n.getViewIdResourceName();
        CharSequence t = n.getText();
        if (id != null && t != null && id.endsWith(QUESTION_ID) && sameQuestion(t.toString())) {
            return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (findQuestion(n.getChild(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Gemini echoes the question with its own capitalisation and punctuation. */
    private static boolean sameQuestion(String shown) {
        String q = question;
        if (q == null) {
            return false;
        }
        return norm(shown).equals(norm(q));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\s+", " ").trim();
    }

    /** The node whose content description matches, searched breadth-agnostic. */
    private static AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo n, String want,
                                                    int depth) {
        if (n == null || depth > 28) {
            return null;
        }
        CharSequence cd = n.getContentDescription();
        if (cd != null && cd.toString().toLowerCase(Locale.US).equals(want)) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByDesc(n.getChild(i), want, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Click the node, or the nearest ancestor that will accept one.
     *
     * Gemini's Send is a Compose node: it carries the description but reports
     * neither isClickable() nor a click action, because the handler sits on a
     * parent. Clicking the described node alone silently does nothing.
     */
    private static boolean clickThrough(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int up = 0; n != null && up < 6; up++) {
            if (n.isClickable() || supportsClick(n)) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            n = n.getParent();
        }
        return false;
    }

    private static boolean supportsClick(AccessibilityNodeInfo n) {
        List<AccessibilityNodeInfo.AccessibilityAction> acts = n.getActionList();
        if (acts == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction a : acts) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                return true;
            }
        }
        return false;
    }
}
