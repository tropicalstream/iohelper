package com.iohelper.card;

import android.content.Context;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken timer / to-do commands, handled locally with no LLM round-trip.
 * Port of the parsing half of commands.py, including the fixes it accumulated:
 * word-numbers ("five minutes"), plural units, a timer anywhere in the sentence
 * ("start a 45 second timer"), and sub-minute spans reading "30 sec" rather
 * than the "0 min" an hours/minutes-only formatter produced.
 */
public final class Commands {

    private static final Pattern DUR = Pattern.compile(
            "(\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DONE = Pattern.compile(
            "\\b(?:mark|check|cross|tick|scratch)\\s+off\\s+(.+)"
            + "|\\bcomplete[d]?\\s+(?:the\\s+)?(?:to ?-? ?do|task)\\s+(.+)"
            // "complete buy oat milk" - the plainest way to say it, and it
            // matched NOTHING: the alternative above insists on the word
            // "todo" or "task" in between. The utterance then fell through to
            // the LLM, which answered "Buy oat milk completed." and nothing
            // was completed at all. Anchored at the start so that a passing
            // "complete" in a question cannot fire it.
            + "|^\\s*(?:please\\s+)?(?:complete|finish|finished)\\s+(.+)"
            // Same failure, different word: bare "done X" ("done ride the
            // cable car") matched NOTHING here - only "done WITH X" did - so it
            // fell to the LLM too. The model correctly refused to claim it had
            // completed anything ("I could not do that"), which is the right
            // fallback, but the to-do stayed open on a phrasing anyone would
            // reach for first. Anchored at the start for the same reason as
            // complete/finish/finished above: a passing "done" mid-sentence
            // must not fire this.
            + "|^\\s*(?:please\\s+)?done[,:]?\\s+(.+)"
            + "|\\b(?:i'?m\\s+|i\\s+am\\s+|i'?ve\\s+|i\\s+have\\s+)?done\\s+with\\s+(.+)"
            + "|\\b(?:to ?-? ?do|task)\\s+(.+?)\\s+(?:is\\s+)?done\\b"
            + "|^\\s*(.+?)\\s+(?:is|are)\\s+done\\s*$", Pattern.CASE_INSENSITIVE);
    /**
     * Dictating a note. Distinct from a to-do: a to-do is something to DO and
     * gets nagged about, a note is something to REMEMBER and only answers when
     * asked. "note to self" stays a to-do - it has always meant one here, and
     * people say it when they mean an action.
     */
    private static final Pattern NOTE_ADD = Pattern.compile(
            "\\b(?:make|take|write|jot|start|add|create)\\s+(?:me\\s+)?(?:a|an|another)?\\s*"
            + "note(?:\\s+(?:that|about|saying|of))?\\b[:,]?\\s*(.+)"
            + "|\\bnote\\s+(?:that|down)\\b[:,]?\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    /** Deleting a note. Checked BEFORE the add and read patterns, which both
     *  also contain the word "note". */
    /**
     * Taking something OFF the card by plain "remove/delete/cancel X".
     *
     * Routed to the same handler as "done" (complete a matching to-do, or
     * dismiss a matching calendar entry). "remove go shopping" did nothing
     * before - none of mark/check/complete cover the word people reach for.
     * Checked after the note patterns, so "delete the note about X" still goes
     * to notes.
     */
    private static final Pattern REMOVE_CARD = Pattern.compile(
            "^\\s*(?:remove|delete|cancel|get rid of|take off)\\s+(?:the\\s+|my\\s+|that\\s+)?(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOTE_DELETE = Pattern.compile(
            "\\b(?:delete|remove|forget|drop|clear|scratch)\\s+(?:the\\s+|that\\s+|my\\s+|a\\s+)?"
            + "notes?\\b(?:\\s+(?:about|on|for|regarding|that says?|saying)\\s*)?[:,]?\\s*(.*)",
            Pattern.CASE_INSENSITIVE);

    /** Asking for notes back, optionally about something. */
    private static final Pattern NOTE_READ = Pattern.compile(
            "(?:^|\\b)(?:what|show|read|list|tell me|check|any|do i have)\\b[^.?!]{0,24}?"
            + "\\bnotes?\\b(?:\\s+(?:about|on|for|regarding)\\s+(.+))?"
            + "|^(?:my )?notes\\b(?:\\s+(?:about|on|for)\\s+(.+))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Asking for the list back, as opposed to adding to it.
     *
     * This was missing entirely: to-dos could be added and could be nudged
     * about later by {@link Proactive}, but "what's on my to-do list" fell
     * through to the LLM, which has never seen the store and duly answered that
     * it had not been provided with one. Anything the user added simply looked
     * lost.
     *
     * Deliberately requires a reading verb. Without that, "add bagels to my
     * to-do list" reads the list instead of adding to it - the failure mode is
     * worse than the bug, because the item is then silently never stored.
     */
    private static final Pattern TODO_LIST = Pattern.compile(
            "(?:^|\\b)(?:what(?:'s|s| is| are)?|show|read|list|tell me|check|"
            + "how many|anything)\\b[^.?!]{0,24}?\\b(?:to ?-? ?do|todo|task)s?"
            + "\\b(?: list)?|^(?:my )?to ?-? ?do list\\b",
            Pattern.CASE_INSENSITIVE);

    /** An adding verb anywhere means it is not a read, whatever else matched. */
    private static final Pattern TODO_ADD_VERB = Pattern.compile(
            "\\b(?:add|put|new|create|remind|append|note down|jot)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TODO = Pattern.compile(
            "\\b(?:add|dd|create)\\s+a?\\s*(?:to ?-? ?do|todo|task)\\b[:,]?\\s*(.+)"
            + "|\\b(?:re)?mind me to\\b\\s*(.+)|\\bnote to self\\b[:,]?\\s*(.+)"
            + "|\\b(?:set|add|create|make) (?:a |an )?(?:reminder|alarm) to\\b\\s*(.+)"
            // The list named at the END - "add bagels to my to-do list" -
            // which is how people actually say it. Only the list-first form
            // was accepted, so the commonest phrasing matched nothing at all
            // and the item was dropped in silence: no card, no error, no entry.
            // "dd" is there because the glasses' VAD clips the leading vowel.
            + "|\\b(?:add|dd|put|jot down|jot|note)\\s+(.+?)\\s+(?:to|on|in)\\s+"
            + "(?:my|the)\\s+(?:to ?-? ?do|task|shopping|grocery)?\\s*lists?\\b"
            + "|\\b(?:add|dd|put|jot down|jot|note)\\s+(.+?)\\s+(?:to|on)\\s+"
            + "(?:my|the)\\s+(?:to ?-? ?dos|todos|tasks)\\b"
            // Last resort: "remind me the wifi password is guest 2024". By the
            // time TODO is reached every timer and clock reading has already
            // failed, so there is no time in it to find. Keeping it as a to-do
            // beats the old behaviour, which was to match nothing and drop the
            // whole utterance without a word.
            // ...but not a QUESTION. "remind me why we switched phone plans" is
            // asking, not filing, and storing it as a to-do would both lose the
            // answer and clutter the list.
            + "|\\b(?:re)?mind me (?:that )?"
            + "(?!why|what|when|where|who|whom|which|how|whether|if\\b)(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMIND_IN = Pattern.compile(
            "\\bremind me (?:in|after)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * A spoken wall-clock time: "at 11:20 AM", "at 3pm", "at 15:30", "at noon".
     *
     * "for" is accepted too ("set a reminder for 15:30"), but only for a STRONG
     * time - one with minutes, am/pm, or o'clock. Allowing a bare hour after
     * "for" would turn "set a timer for 4" into a 4 o'clock reminder.
     */
    private static final Pattern AT_CLOCK = Pattern.compile(
            "\\b(?:at|by|around|for)\\s+(?:a\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d:])"
            + "|\\b(noon|midday|midnight)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * Words that follow a number and prove it was never a time.
     *
     * Without this, "call the pharmacy at 3rd and main" books 3:00 PM and
     * "charge the glasses at 20 percent" books 8:00 PM - a phantom reminder at
     * an hour the user never said, which is worse than not scheduling at all.
     */
    private static final Pattern NOT_A_TIME = Pattern.compile(
            "^\\s*(?:st|nd|rd|th|percent|%|degrees?|deg|dollars?|bucks|cents?|miles?|km|"
            + "kilometers?|feet|ft|pounds?|lbs?|kg|people|guests?|players?|street|st\\.|"
            + "avenue|ave|road|rd\\.|boulevard|blvd|west|east|north|south)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * "Play X", optionally naming a service. Anchored at the START of the
     * utterance on purpose: an unanchored "play" turns "what time does the play
     * start" into a music request.
     */
    private static final Pattern PLAY = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey )*"
            + "(?:play|put on|throw on|start playing|shuffle)\\s+(.+?)"
            + "(?:\\s+(?:on|from|in)\\s+(?:my\\s+|the\\s+)?"
            + "(spotify|you ?tube|pocket ?casts|podcasts?))?\\s*[.?!]*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * START turn-by-turn navigation ("navigate to X", "take me to X", "walk me
     * to X") - an action that launches Google Maps, as opposed to the
     * informational "how long / how far to X" that Search answers with a summary
     * (those open with how/what, so they never match this). NavListener relays
     * the live steps to the glasses once Maps starts.
     */
    private static final Pattern NAV_START = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey )*"
            // group 1 = the trigger; the leading verb also picks the mode, so
            // the bare forms Google Maps accepts work here too ("walk to X",
            // "bicycle to X", "drive to X", "public transportation to X").
            + "(navigate|start\\s+navigat(?:e|ing|ion)|take me"
            + "|guide me|route me|lead me|let'?s go"
            + "|give me directions|get directions|directions"
            + "|walk me|drive me|bike me|cycle me"
            + "|take\\s+(?:the\\s+)?(?:bus|train|bart|subway|transit)"
            + "|public\\s+transp(?:ortation)?|public\\s+transit"
            + "|walk|drive|bicycle|bike|cycle"
            + "|transit|bus|train|bart|subway)"
            + "\\s+to\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    /**
     * Hand the next question to the PHONE's assistant ("ask android", "ask
     * gemini", "open the assistant").
     *
     * Deliberately does NOT match a bare "ask google": on this app that reads
     * as an ordinary search, which is answered here rather than by handing the
     * phone over. The target has to be named as the device or the assistant
     * itself.
     */
    private static final Pattern ASSIST_OPEN = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey )*"
            + "(?:ask|open|start|launch|wake|talk to|switch to|hand (?:this|it|that) (?:to|over to))"
            + "\\s+(?:the\\s+)?"
            + "(?:android|gemini|bixby|siri|assistant|phone assistant|google assistant"
            + "|androids? assistant|phone)"
            + "(?:\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Internet RADIO ("play NPR radio", "put on jazz radio", "listen to BBC
     * World Service"). Matched before the music patterns, because a station is
     * not a song: "play K\\ED on the Sonos" resolved through Spotify search
     * would find some unrelated track, or nothing at all.
     *
     * The word "radio" or "station" is RE\\UIRED somewhere in the request, or a
     * bare "play jazz" - which almost always means music - would be routed to a
     * stream instead.
     */
    private static final Pattern RADIO = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey )*"
            + "(?:play|put on|throw on|tune (?:in )?to|tune|listen to|turn on)\\s+"
            + "(.+?)"
            + "\\s*[.?!]*$",
            Pattern.CASE_INSENSITIVE);
    /** The noun that makes a request a RADIO request rather than a music one. */
    private static final Pattern RADIO_NOUN = Pattern.compile(
            "\\b(?:radio|station|airwaves|am|fm)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * END turn-by-turn navigation ("end navigation", "stop navigating", "cancel
     * the route"). The counterpart to {@link #NAV_START}: without it, "end
     * navigation" fell through to the model, which correctly refused to claim it
     * had done something it has no way to do.
     *
     * Deliberately narrow. A bare "stop" is the media command and must stay
     * that way - so the navigation noun is required, and this is matched BEFORE
     * the media patterns so "stop navigation" is never heard as "stop".
     */
    private static final Pattern NAV_STOP = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey )*"
            + "(?:end|stop|cancel|exit|quit|close|kill|abort|turn off)"
            + "\\s+(?:the\\s+|my\\s+|this\\s+)?"
            + "(?:navigation|navigating|nav|route|routing|directions|guidance"
            + "|turn.?by.?turn|the\\s+trip|maps)"
            + "\\s*[.?!]*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * The service used AS the verb - "spotify play my discover weekly",
     * "youtube the docking scene". Clipping eats the real verb often enough that
     * the app name is all that survives to say what was meant.
     */
    private static final Pattern SERVICE_FIRST = Pattern.compile(
            "^\\W*(spotify|you ?tube|pocket ?casts)\\s+(?:play\\s+|search\\s+)?(.+?)\\s*[.?!]*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * Transport control: anchored AND kept short. "next" and "stop" are ordinary
     * words, and "what's next on my calendar" must not skip a track.
     */
    private static final Pattern TRANSPORT = Pattern.compile(
            "^\\W*(?:can you |could you |please |hey |just |go )*"
            + "(pause|paws|resume|unpause|continue|keep playing|play"
            + "|stop|shut it off|shut off|kill it"
            + "|skip|kip|next(?: song| track| one)?|ext|previous(?: song| track)?|prev"
            + "|back|go back|rewind|replay|one more time|again"
            + "|louder|quieter|mute|unmute|crank it up"
            + "|turn it up|turn it down|turn the volume up|turn the volume down"
            + "|volume up|volume down)"
            + "\\b.{0,26}$", Pattern.CASE_INSENSITIVE);
    /**
     * A query left hanging by the glasses clipping the end of speech:
     * "play the latest video by." The words that must be followed by something.
     */
    private static final Pattern DANGLING = Pattern.compile(
            "\\b(by|from|with|featuring|feat|ft|of|the|a|an|some)\\s*[.,!?]*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * "the latest video (from/of) X", for YouTube only.
     *
     * Measured: "play the latest YouTube video KPFA Flashpoints" searched for
     * the literal text "the latest video KPFA Flashpoints" - "latest" and
     * "video" are not search terms, they are an instruction to SORT BY DATE,
     * and left in as noise they actively hurt the relevance ranking. This is
     * exactly the youtube_sort/youtube_channel steering the play_music TOOL
     * already has (see Tools.java); this gives the phrase pattern the same
     * capability, since a request this literal never reaches the model at all.
     * The preposition is optional - "video KPFA Flashpoints" with nothing
     * between them is how people actually say it.
     */
    private static final Pattern YT_LATEST = Pattern.compile(
            "^(?:the\\s+)?(latest|newest|most\\s+recent|most\\s+popular|most\\s+viewed|top)\\s+"
            + "(?:video|upload|episode|clip)?\\s*(?:(?:from|of|by)\\s+)?(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Which service was named anywhere in the utterance, or null. */
    private static String serviceMentioned(String low) {
        if (low.matches(".*\\byou ?tube\\b.*")) {
            return "youtube";
        }
        if (low.matches(".*\\bspotify\\b.*")) {
            return "spotify";
        }
        // The APP name only. A bare "podcast" stays out of this, or "play the
        // X podcast" would be taken off Spotify on the strength of one common
        // noun; the explicit trailing "on podcasts" form in PLAY covers that.
        if (low.matches(".*\\bpocket ?casts\\b.*")) {
            return "pocketcasts";
        }
        return null;
    }

    /**
     * The spellings that all mean Pocket Casts.
     *
     * PLAY's trailing group keeps "podcast"/"podcasts" as the wearer said them,
     * while SERVICE_FIRST and serviceMentioned both normalise to "pocketcasts",
     * so the runner has to accept every one of them.
     */
    private static boolean podcastApp(String service) {
        return "pocketcasts".equals(service) || "podcast".equals(service)
                || "podcasts".equals(service);
    }

    /**
     * A transport command aimed at a Sonos rather than the phone.
     *
     * Room words are listed rather than discovered because parse() has no
     * Context; the actual speaker is resolved later against what is really on
     * the network, so a room named here that does not exist simply finds nothing.
     */
    private static final Pattern SONOS_TARGET = Pattern.compile(
            "\\bsonos\\b|\\bspeakers?\\b"
            + "|\\b(?:on|in|to)\\s+the\\s+(?:bedroom|bathroom|kitchen|living room|office"
            + "|dining room|patio|garage|den|studio|hallway)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A transport word aimed at something that is not the player. */
    private static final Pattern NOT_THE_PLAYER = Pattern.compile(
            "\\b(timer|countdown|alarm|reminder|stopwatch)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * "What's playing" - anchored to the END of the utterance. Unanchored it
     * also swallows "what's playing at the Landmark this weekend", which is a
     * cinema question, not a question about the speaker's own audio.
     */
    private static final Pattern NOW_PLAYING = Pattern.compile(
            "\\bwhat(?:'s| is)\\s+playing(?:\\s+(?:right\\s+)?now)?\\s*[?.!]*$"
            + "|\\bwhat (?:song|track) is (?:this|playing)\\b"
            + "|\\bwho sings this\\b|\\bname of this song\\b", Pattern.CASE_INSENSITIVE);
    /**
     * Anything meaning "tell me later", as opposed to "note this down".
     *
     * "(?:re)?mind me" on purpose: the glasses' voice detection clips the start
     * of utterances, so "remind me to ..." reaches us as "mind me to ..." often
     * enough to matter. "me to" alone is allowed only because a clock time must
     * ALSO be present for any of this to fire.
     */
    private static final Pattern REMIND_ANY = Pattern.compile(
            // "member to ..." and a leading bare "to ..." are what the VAD
            // leaves of "remember to" and "remind me to"; "me in" is the clipped
            // "remind me in". All three used to fall through to the to-do list.
            "\\b(?:(?:re)?mind me|(?:re)?member to|reminder|wake me|alarm|alert me"
            + "|heads? up|me to|me in)\\b|^to\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String[][] WORD_NUMS = {
            {"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"},
            {"six", "6"}, {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"},
            {"fifteen", "15"}, {"twenty", "20"}, {"thirty", "30"}, {"forty", "40"},
            {"forty-five", "45"}, {"sixty", "60"}, {"ninety", "90"}};

    private Commands() {
    }

    /** The parsed command, or null when the utterance belongs to the LLM. */
    public static final class Cmd {
        public final String kind;      // timer | todo | done
        public final int seconds;
        public final String text;
        /** "11:20 AM" when the user named a clock time, else null. */
        public final String due;
        /** A speaker was named, but playback is still going to the phone. */
        public final boolean onSpeaker;
        /** "shuffle X" / "play X shuffled". Only meaningful for a container. */
        public boolean shuffle;
        /** "album" | "playlist" | null to auto-resolve as artist or track. */
        public String contentType;

        /**
         * The play phrase with the service and speaker stripped but the
         * DESCRIPTIVE wording kept ("the first album by the cure"), for the
         * knowledge resolver. cmd.text is too mangled to resolve from (the
         * content-type strip turns it into "first by the cure"); null for every
         * non-play command.
         */
        public String descr;

        /**
         * What the live voice model has already told the user about this
         * request, if anything - it named the release out loud and the user
         * heard it, so that is what must play.
         *
         * Deliberately NOT folded into {@link #descr}: descr is what
         * isCurated / isDescriptive / curatedQuery classify on, and free prose
         * in it re-classifies the request - "one of the greatest albums of all
         * time" reads as a request for a greatest-hits MIX, and a single-album
         * request would come back as ten tracks. This reaches the resolver's
         * prompt and nothing else.
         */
        public String said;
        /**
         * YouTube navigation, from the play_music tool: how to rank the search
         * (relevance | newest | popular), a channel to search within, and how
         * far back to look (week | month | year). All optional; null means the
         * plain top hit, which is what a spoken "play X on YouTube" gets.
         */
        public String ytSort;
        public String ytChannel;
        public String ytSince;

        Cmd(String kind, int seconds, String text) {
            this(kind, seconds, text, null, false);
        }

        Cmd(String kind, int seconds, String text, String due) {
            this(kind, seconds, text, due, false);
        }

        Cmd(String kind, int seconds, String text, String due, boolean onSpeaker) {
            this.kind = kind;
            this.seconds = seconds;
            this.text = text;
            this.due = due;
            this.onSpeaker = onSpeaker;
        }
    }

    /** How long until a spoken clock time, and what to call it back. */
    static final class Clock {
        final int seconds;
        final String label;            // "11:20 AM"

        Clock(int seconds, String label) {
            this.seconds = seconds;
            this.label = label;
        }
    }

    /**
     * Seconds from now until the next occurrence of a spoken clock time.
     *
     * This is what was missing. "Remind me to pet the dog at 11:20 AM" matched
     * the TO-DO pattern, so the time was captured as part of the label and
     * nothing was ever scheduled - the reminder simply never arrived. Only
     * "remind me IN five minutes" produced a timer.
     */
    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty"};
    private static final Pattern QUARTER_TO = Pattern.compile(
            "\\bquarter\\s+(?:to|of|till|til)\\s+(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /** Spoken numbers to digits, so the clock rules below can stay numeric. */
    private static String spokenNumbers(String text) {
        String s = text;
        for (int t = 2; t <= 5; t++) {                       // "twenty five" -> 25
            for (int o = 1; o <= 9; o++) {
                s = s.replaceAll("(?i)\\b" + TENS[t] + "[ -]" + ONES[o] + "\\b",
                        String.valueOf(t * 10 + o));
            }
            s = s.replaceAll("(?i)\\b" + TENS[t] + "\\b", String.valueOf(t * 10));
        }
        for (int i = ONES.length - 1; i >= 0; i--) {
            s = s.replaceAll("(?i)\\b" + ONES[i] + "\\b", String.valueOf(i));
        }
        return s;
    }

    /**
     * Rewrite spoken clock forms into digits.
     *
     * People say times as words far more than as digits - "at eleven twenty",
     * "at five o'clock", "at quarter to twelve" - and every one of those was
     * falling through to the to-do path, which is the silent never-fires bug.
     * Only the copy used for DETECTION is rewritten; the label shown back to the
     * user is still built from what was actually said.
     */
    private static String normalizeSpokenTime(String text) {
        String s = spokenNumbers(text);
        s = s.replaceAll("(?i)\\b(\\d{1,2})\\s*o'?\\s?clock\\b", "$1:00");
        s = s.replaceAll("(?i)\\bquarter\\s+(?:past|after)\\s+(\\d{1,2})\\b", "$1:15");
        s = s.replaceAll("(?i)\\bhalf\\s+(?:past|after)\\s+(\\d{1,2})\\b", "$1:30");
        Matcher q = QUARTER_TO.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (q.find()) {
            int prev = (Integer.parseInt(q.group(1)) + 11) % 12;
            q.appendReplacement(sb, (prev == 0 ? 12 : prev) + ":45");
        }
        q.appendTail(sb);
        s = sb.toString();
        // "at eleven twenty" -> "at 11:20"
        s = s.replaceAll("(?i)\\b(at|by|around|for)\\s+(\\d{1,2})\\s+(\\d{1,2})\\b"
                + "(?!\\s*(?:am|pm|a\\.m|p\\.m))", "$1 $2:$3");
        // "at 1120" -> "at 11:20". FOUR digits only: "at 350" is an oven
        // temperature, and reading it as 3:50 books a reminder nobody asked for.
        // A three-digit form loses "at 930", which still works as 9:30 or
        // "nine thirty" - a fair trade against inventing appointments.
        s = s.replaceAll("(?i)\\b(at|by|around|for)\\s+(\\d{2})(\\d{2})\\b", "$1 $2:$3");
        return s;
    }

    static Clock parseClock(String rawText) {
        String text = normalizeSpokenTime(rawText);
        Matcher m = AT_CLOCK.matcher(text);
        // Keep looking: a rejected first match ("at 3rd and main") must not hide
        // a real time later in the sentence.
        while (m.find()) {
            Clock c = clockFrom(text, m);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static Clock clockFrom(String text, Matcher m) {
        int hour;
        int minute = 0;
        String meridiem = null;
        boolean strong;
        if (m.group(4) != null) {
            hour = "midnight".equalsIgnoreCase(m.group(4)) ? 0 : 12;
            strong = true;
        } else {
            if (NOT_A_TIME.matcher(text.substring(m.end())).find()) {
                return null;             // "at 3rd and main", "at 20 percent"
            }
            hour = Integer.parseInt(m.group(1));
            if (m.group(2) != null) {
                minute = Integer.parseInt(m.group(2));
            }
            if (hour > 24 || minute > 59) {
                return null;
            }
            if (m.group(3) != null) {
                meridiem = m.group(3).toLowerCase(Locale.US).replace(".", "");
                if (meridiem.startsWith("p") && hour < 12) {
                    hour += 12;
                } else if (meridiem.startsWith("a") && hour == 12) {
                    hour = 0;
                }
            }
            strong = m.group(2) != null || meridiem != null || hour > 12;
            // A bare hour after "for" is a duration, not a time of day:
            // "set a timer for 4" must not become 4 o'clock.
            if (!strong && m.group().trim().toLowerCase(Locale.US).startsWith("for")) {
                return null;
            }
        }

        Calendar target = Calendar.getInstance();
        long now = target.getTimeInMillis();
        target.set(Calendar.HOUR_OF_DAY, hour % 24);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (text.toLowerCase(Locale.US).contains("tomorrow")) {
            target.add(Calendar.DATE, 1);
        }
        // No am/pm and the hour has already gone: "at 8" said in the evening
        // means 8pm tonight, not 8am tomorrow. Try the 12-hour partner first.
        if (target.getTimeInMillis() <= now && meridiem == null && hour < 12) {
            target.add(Calendar.HOUR_OF_DAY, 12);
        }
        while (target.getTimeInMillis() <= now) {
            target.add(Calendar.DATE, 1);
        }
        long delta = (target.getTimeInMillis() - now) / 1000L;
        if (delta < 1 || delta > 7L * 24 * 3600) {
            return null;
        }
        int h12 = target.get(Calendar.HOUR) == 0 ? 12 : target.get(Calendar.HOUR);
        String label = String.format(Locale.US, "%d:%02d %s", h12,
                target.get(Calendar.MINUTE),
                target.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        return new Clock((int) delta, label);
    }

    /** Strip the "remind me to" opener and the time phrase, leaving the task. */
    private static String reminderLabel(String original) {
        String label = original.replaceAll(
                // The clipped openers matter here too, or the label keeps them:
                // "member to pick up the record" instead of "pick up the record".
                "(?i)^.*?\\b(?:remind me(?: to)?|(?:re)?member to"
                + "|(?:set|add|create|make) (?:a |an )?(?:reminder|alarm)(?: to| for)?"
                + "|reminder(?: to| for)?|wake me(?: up)?|alert me(?: to)?)\\b", "");
        label = AT_CLOCK.matcher(label).replaceAll(" ");
        // AT_CLOCK only knows digits, so a spoken hour survived it and the card
        // read "pick up the record at four at 4:00 PM". Matched as a whole
        // phrase rather than by running spokenNumbers over the label, which
        // would also rewrite numbers belonging to the task ("call one of the
        // vendors" -> "call 1 of the vendors").
        label = label.replaceAll("(?i)\\b(?:at|by|around)\\s+(?:a\\s+)?"
                + "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
                + "|noon|midday|midnight)"
                + "(?:\\s*(?:o'?\\s?clock|a\\.?m\\.?|p\\.?m\\.?))?\\b", " ");
        label = label.replaceAll("(?i)\\btomorrow\\b", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s.,:;]+|[\\s.,:;?]+$", "");
        // A leading "to" is what is left of an opener the VAD ate.
        label = label.replaceAll("(?i)^to\\s+", "");
        return label.isEmpty() ? "Reminder" : label;
    }

    /** "five minutes" -> "5 minutes", so the duration regex sees a number. */
    static String digits(String text) {
        String out = text;
        for (String[] wn : WORD_NUMS) {
            out = out.replaceAll("(?i)\\b" + wn[0] + "\\b(?=\\s*(?:hour|hr|min|sec|h\\b|m\\b|s\\b))",
                    wn[1]);
        }
        return out;
    }

    /**
     * Durations said without a digit: "an hour", "half an hour", "an hour and a
     * half", "a quarter hour". DUR needs "<number> <unit>", so every one of
     * these parsed as NO duration and the reminder was silently never set.
     * Longest forms first - "an hour and a half" must not be eaten by "an hour".
     *
     * "a second" is deliberately absent: "in a second" means soon, not a timer.
     */
    static String words(String text) {
        return text
                .replaceAll("(?i)\\ban? hour and a half\\b", "90 minutes")
                .replaceAll("(?i)\\bhalf an hour\\b", "30 minutes")
                .replaceAll("(?i)\\b(?:a|one) quarter hour\\b", "15 minutes")
                .replaceAll("(?i)\\bquarter of an hour\\b", "15 minutes")
                .replaceAll("(?i)\\bhalf a minute\\b", "30 seconds")
                .replaceAll("(?i)\\ban? (hour|minute)\\b", "1 $1");
    }

    /**
     * A delay with the unit dropped - "remind me in five", "set a timer for 10".
     * People speak this way constantly and the ASR returns it verbatim, and
     * until now it parsed as nothing at all: the utterance fell through to the
     * TO-DO list, so a reminder quietly became a note that never fired. That is
     * the bug behind reminders "not surfacing".
     *
     * Minutes is the only sensible reading. Nobody sets a five-SECOND reminder
     * by voice, and five hours would be said as "five hours".
     *
     * Only consulted once something already looks like a timer or a reminder
     * (see parse), so "a table for 2" cannot become a two-minute countdown.
     */
    /** Spoken self-correction: whatever follows replaces what came before. */
    private static final Pattern CORRECTION = Pattern.compile(
            "\\b(?:actually|no wait|nope|scratch that|make it|instead,? make it|"
            + "i mean|sorry,? make it)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_AFTER = Pattern.compile(
            // "after" is absent on purpose: "quarter after 3" is a clock time,
            // and reading its "after 3" as a bare delay turned a 3:15 reminder
            // into a three-minute one. Spelled-out delays after "after" still
            // work, because those carry a unit and parseDuration takes them.
            // The number must END the utterance, or be followed by the task
            // ("in five TO check the laundry"). Anything else after it means it
            // was a quantity, not a delay: "grab a table for six at Antonio's"
            // is a to-do, and reading its "for six" as a delay booked a
            // six-minute timer instead.
            "\\b(?:in|within|for)\\s+(\\d{1,3})\\b"
            + "(?=\\s*(?:[.,!?]\\s*)*$|\\s+(?:to|please|ok|okay)\\b)",
            Pattern.CASE_INSENSITIVE);

    static int bareMinutes(String text) {
        Matcher m = BARE_AFTER.matcher(spokenNumbers(digits(words(text))));
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            // Above four hours it is far more likely a quantity than a delay.
            if (n > 0 && n <= 240) {
                return n * 60;
            }
        }
        return -1;
    }

    /**
     * The whole utterance is nothing but a duration - "45 seconds", "in 20
     * minutes". That cannot mean anything except a timer, and the glasses' VAD
     * clips the verb off the front of short commands constantly, so this is how
     * a great many timers actually arrive.
     */
    static boolean bareDurationOnly(String low) {
        String s = spokenNumbers(digits(words(low)))
                .replaceAll("(?i)^\\s*(?:in|after|within|for)\\s+", "")
                .replaceAll("[.?!,]", "").trim();
        return s.matches("(?i)(?:\\d+\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)"
                + "\\s*(?:and\\s+)?)+");
    }

    /** Sum every "N unit" span -> seconds, or -1 when there is none. */
    public static int parseDuration(String text) {
        // spokenNumbers handles compounds ("twenty five minutes") that the older
        // word list could not, and subsumes it. words() covers the ones with no
        // digit at all ("half an hour").
        Matcher m = DUR.matcher(spokenNumbers(digits(words(text))));
        int total = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            char u = Character.toLowerCase(m.group(2).charAt(0));
            int mult = u == 'h' ? 3600 : u == 's' ? 1 : 60;
            total += Integer.parseInt(m.group(1)) * mult;
        }
        return found ? total : -1;
    }

    /** A duration that is actually a DELAY: the part after in / after / within. */
    static int parseDelay(String text) {
        Matcher m = Pattern.compile("\\b(?:in|after|within)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        while (m.find()) {
            int d = parseDuration(text.substring(m.end()));
            if (d > 0) {
                return d;
            }
        }
        return -1;
    }

    public static String span(int secs) {
        if (secs >= 3600) {
            return (secs / 3600) + "h " + (secs % 3600 / 60) + "m";
        }
        return secs >= 60 ? (secs / 60) + " min" : secs + " sec";
    }

    /**
     * A SECOND request in the same breath: "set a timer for two minutes and
     * also tell me the weather", "cancel the pasta timer, is it going to
     * rain?". Each pattern in {@link #parse} understands one request, and
     * handed a compound one it takes the first half and swallows the rest into
     * its label ("Timer set: kick off egg and also tell weather in 2 min").
     * A model with tools does both halves, so when one is available a compound
     * utterance skips the patterns entirely.
     *
     * The test is a conjunction FOLLOWED BY A REQUEST-SHAPED WORD - a question
     * word or a verb - because a bare "and" joins nouns far more often than
     * clauses: "simon and garfunkel", "bread and butter", "fifth and mission",
     * "an hour and a half" all stay single.
     */
    private static final Pattern COMPOUND = Pattern.compile(
            "(?i)(?:\\b(?:and|then|plus)\\s+(?:also\\s+|then\\s+)?|,\\s*(?:and\\s+|also\\s+)?|\\balso\\s+)"
            + "(?:what|what's|whats|how|how's|when|when's|where|where's|who|who's|which|why"
            + "|is|are|am|do|does|did|can|could|will|would|should"
            + "|tell|show|give|let|remind|set|start|play|put|add|cancel|stop|skip|pause|resume"
            + "|turn|find|check|read|list|navigate|take|text|send|open|search|look|call|make"
            + "|note|jot|mark|tick|cross|delete|remove|end|get|book|order)\\b");

    public static boolean compound(String text) {
        return text != null && COMPOUND.matcher(text).find();
    }

    public static Cmd parse(String text) {
        String t = text.trim();
        String low = t.toLowerCase();

        // Handing the phone's assistant a command comes FIRST - before every
        // pattern below. "Ask gemini to set a timer for 3 minutes" is a request
        // to hand that whole sentence over, but our own timer pattern matches
        // it perfectly well and, placed later, this lost: the utterance became
        // a local timer labelled "ask gemini". Whatever follows the handoff
        // phrase belongs to the assistant, however much it looks like
        // something we could do ourselves.
        Matcher am = ASSIST_OPEN.matcher(t);
        if (am.find()) {
            String rest = am.group(1) == null ? "" : am.group(1).trim();
            // "ask gemini TO set a timer" - the connective is ours, not part
            // of the command being handed over.
            rest = rest.replaceAll("^(?i)(?:to|for|if|whether|about)\s+", "").trim();
            return new Cmd("assist.open", 0, rest, null);
        }

        Matcher d = DONE.matcher(low);
        if (d.find()) {
            for (int g = 1; g <= d.groupCount(); g++) {
                if (d.group(g) != null) {
                    // Trailing punctuation matters: the ASR ends utterances
                    // with a full stop, and "buy oat milk." matches no stored
                    // to-do called "buy oat milk".
                    return new Cmd("done", 0,
                            d.group(g).replaceAll("^[\\s.,:;!?]+|[\\s.,:;!?]+$", ""));
                }
            }
        }

        // "remind me in half an hour - actually, make it 5 o'clock". People
        // correct themselves mid-sentence and the ASR hands over both times.
        // The LAST one is the one they meant, so a clock after a correction
        // marker beats any delay said before it.
        int correctedDur = -1;
        Matcher fix = CORRECTION.matcher(low);
        if (fix.find()) {
            String tail = low.substring(fix.end())
                    .replaceAll("^[\\s,]*(?:make it|let'?s say|say)?[\\s,]*", "");
            // A correction can replace either kind of time, and the two look
            // almost identical once the filler is gone: "make it 5 o'clock" is
            // a clock, "make it 15" is fifteen more minutes. Only commit to a
            // clock when the tail actually says so - otherwise "make it 15"
            // books a reminder for three in the afternoon.
            boolean looksClock = tail.matches(
                    "(?i).*(?:\\bo'?\\s?clock\\b|\\ba\\.?m\\.?\\b|\\bp\\.?m\\.?\\b"
                    + "|\\bnoon\\b|\\bmidday\\b|\\bmidnight\\b|\\d{1,2}:\\d{2}).*");
            if (looksClock) {
                // AT_CLOCK wants a leading at/by/around, which a correction
                // almost never has, so supply one.
                Clock later = parseClock(tail);
                if (later == null) {
                    later = parseClock("at " + tail);
                }
                if (later != null) {
                    return new Cmd("timer", later.seconds, reminderLabel(t), later.label);
                }
            } else {
                correctedDur = parseDuration(tail);
                if (correctedDur <= 0) {
                    String n = spokenNumbers(digits(tail)).trim();
                    if (n.matches("\\d{1,3}")) {
                        int v = Integer.parseInt(n);
                        if (v > 0 && v <= 240) {
                            correctedDur = v * 60;      // bare number: minutes
                        }
                    }
                }
            }
        }

        int dur = parseDuration(low);
        if (correctedDur > 0) {
            dur = correctedDur;                          // the later time wins
        }
        boolean remindIn = REMIND_IN.matcher(low).find();
        // "remind me TO pet the dog IN two minutes" - the task sits between the
        // two words, so REMIND_IN's adjacent "remind me in" never matched and the
        // whole thing became a to-do that never fired. Any reminder wording plus
        // a duration is a timer.
        boolean remindAny = REMIND_ANY.matcher(low).find();
        // For a reminder the duration must be introduced by in/after/within.
        // "Remind me to grab a 30 minute parking pass" measures the PASS, not a
        // delay, and scheduling it for half an hour later helps nobody.
        int delay = parseDelay(low);
        boolean timerWord = low.matches(".*\\b(timer|countdown)\\b.*");
        // Unit-less forms, only once this already looks like a timer or a
        // reminder: "remind me in five", "set a timer for 10".
        if (delay <= 0 && (remindIn || remindAny)) {
            delay = bareMinutes(low);
        }
        if (dur <= 0 && timerWord) {
            dur = bareMinutes(low);
        }
        // A lone duration with no verb at all is still a timer - there is
        // nothing else it could be.
        boolean loneDuration = dur > 0 && !timerWord && !remindIn && !remindAny
                && bareDurationOnly(low);
        // A question ABOUT a timer is not a timer. "What time should I set the
        // timer for if it needs 3 hours" booked a three-hour countdown - the
        // same phantom-reminder failure as reading "at 20 percent" as 8 PM,
        // arriving by a different route.
        boolean askingAbout =
                low.matches("(?i)^\\s*(?:what|when|how|why|where|who|which)\\b.*")
                && low.matches("(?i).*\\b(?:should|would|could|do|does|did|is|are|was|were)\\b.*");
        if (!timerWord && delay > 0 && (remindIn || remindAny)) {
            dur = delay;
        }
        if (dur > 0 && !askingAbout && (timerWord || loneDuration
                || ((remindIn || remindAny) && delay > 0))) {
            String label;
            if (remindIn || remindAny) {
                label = reminderLabel(t);
                // Order matters. Normalise the spoken forms first, take out the
                // "<number> <unit>" spans, and only THEN remove a bare
                // "in 45" - as a phrase, so that a number belonging to the task
                // ("pick up 2 pizzas") is not stripped along with it. That is
                // why the old code left "stretch 45" as the label.
                label = spokenNumbers(digits(words(label)));
                label = DUR.matcher(label).replaceAll(" ");
                label = label.replaceAll("(?i)\\b(?:in|after|within|for)\\s+\\d{1,3}\\b", " ");
                label = label.replaceAll("(?i)\\b(?:in|after|within)\\b", " ")
                        .replaceAll("\\s+", " ")
                        .replaceAll("^[\\s.,:;]+|[\\s.,:;?]+$", "");
                label = label.replaceAll("(?i)^to\\s+", "");
                if (label.isEmpty()) {
                    label = "Reminder";
                }
            } else {
                label = spokenNumbers(digits(words(t)));
                label = DUR.matcher(label).replaceAll(" ");
                // "set a timer for 10" - the bare number is the duration, and
                // without this the card read "Timer set: 10 in 10 min."
                label = label.replaceAll("(?i)\\b(?:for|in)\\s+\\d{1,3}\\b", " ");
                label = label.replaceAll(
                        "(?i)\\b(?:set|start|a|an|the|for|to|me|please)\\b|\\b(timer|countdown)\\b", " ");
                label = label.replaceAll("\\s+", " ")
                        .replaceAll("^[\\s.,]+|[\\s.,]+$", "");
            }
            return new Cmd("timer", dur, label);
        }

        // Starting NAVIGATION ("navigate to X", "take me to X") - an action, and
        // before the to-do pattern so it is never noted down as a task. The
        // informational "how long/how far to X" opens with how/what and does not
        // match here; it falls through to Search's directions summary.
        // Radio, before the music patterns: a station is not a song, and
        // resolving "K\\ED" through a music catalogue finds nothing useful.
        Matcher rm = RADIO.matcher(t);
        if (rm.find() && RADIO_NOUN.matcher(low).find()) {
            String station = rm.group(1)
                    // The SAME stripping the music path uses. My first version
                    // only matched "on the sonos", so "play NPR radio on the
                    // BEDROOM sonos" searched the directory for the whole
                    // phrase and found nothing.
                    .replaceAll("(?i)\\s*\\b(?:on|in|to)\\s+(?:the\\s+)?[a-z ]{0,16}\\bsonos\\b.*$", "")
                    .replaceAll("(?i)\\s*\\b(?:on|in|to)\\s+(?:the\\s+)?(?:bedroom|bathroom"
                            + "|kitchen|living room|office|dining room|patio|garage|den"
                            + "|studio|hallway)\\b.*$", "")
                    .replaceAll("(?i)\\s*\\bsonos\\b", " ")
                    .replaceAll("(?i)\\s+on (?:the )?(?:speakers?|phone)\\b.*$", "")
                    .replaceAll("(?i)\\b(?:on|from|in) the radio\\b", " ")
                    .replaceAll("\\s+", " ")
                    .replaceAll("^[\\s?.,]+|[\\s?.,]+$", "").trim();
            if (!station.isEmpty()) {
                // The WHOLE utterance rides along as the target, the way the
                // music path does it: Sonos.pick matches the room name against
                // what is actually on the network, so it needs the words.
                return new Cmd("radio.play", 0, station,
                        SONOS_TARGET.matcher(low).find() ? t : null);
            }
        }

        // Ending navigation, BEFORE the media patterns: "stop navigation" must
        // not be heard as the bare media "stop".
        if (NAV_STOP.matcher(t).find()) {
            return new Cmd("nav.stop", 0, "", null);
        }

        Matcher navm = NAV_START.matcher(t);
        if (navm.find()) {
            String dest = navm.group(2)
                    // strip only a TRAILING mode modifier ("... walking", "... by
                    // transit"); never a mode word inside the place name, so
                    // "drive to Train Station" keeps "Train Station".
                    .replaceAll("(?i)\\s+(?:on\\s+foot|walking|driving|biking|bicycling|cycling"
                            + "|by\\s+(?:car|bus|train|bart|subway|transit|bike|bicycle)"
                            + "|public\\s+trans\\w*|via\\s+transit)\\s*$", "")
                    .replaceAll("(?i)[,\\s]*\\bjarvis\\b[.?!]*$", "")
                    .replaceAll("(?i)\\bplease\\b", " ")
                    .replaceAll("\\s+", " ")
                    .replaceAll("^[\\s?.,]+|[\\s?.,]+$", "").trim();
            if (!dest.isEmpty()) {
                return new Cmd("nav.start", 0, dest, navMode(navm.group(1), navm.group(2)));
            }
        }

        // Media, before the to-do pattern: "play X" is never a task to note down.
        // NOW_PLAYING is anchored to the end of the utterance so that "what's
        // playing at the Landmark this weekend" stays a question for the model.
        // That anchor also rejects "what's playing ON THE SONOS", so allow a
        // trailing target when the target is genuinely a speaker.
        boolean onSpeaker = SONOS_TARGET.matcher(low).find();
        if (NOW_PLAYING.matcher(low).find()
                || (onSpeaker && low.matches(".*\\bwhat(?:'s| is)\\s+playing\\b.*"))) {
            return onSpeaker
                    ? new Cmd("sonos.now", 0, "", t)
                    : new Cmd("media.now", 0, "");
        }
        // PLAY before TRANSPORT: "play" alone means resume, but "play <thing>"
        // means start that thing, and transport must not swallow the object.
        Matcher pl = PLAY.matcher(t);
        if (pl.find()) {
            String q = pl.group(1)
                    .replaceAll("(?i)[,\\s]*\\bjarvis\\b[.?!]*$", "").trim();
            // The service can be named ANYWHERE, not only as a trailing
            // "on youtube": "play the latest YouTube video by ..." puts it in
            // the middle, and matching only the tail sent that to Spotify.
            String service = pl.group(2) != null
                    ? pl.group(2).toLowerCase(Locale.US).replace(" ", "")
                    : serviceMentioned(low);
            q = q.replaceAll("(?i)\\b(?:on|from|in)?\\s*\\b(you ?tube|spotify|pocket ?casts)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            // "the latest/newest/most popular video (from/of) X", YouTube only:
            // recognise it as a sort + channel, not literal search text - see
            // YT_LATEST. What is left after stripping it is the channel name;
            // empty means no channel was named, and the request stays a plain
            // (now word-cleaned) search rather than one polluted with "latest".
            String ytSort = null;
            String ytChannel = null;
            if ("youtube".equals(service)) {
                Matcher yl = YT_LATEST.matcher(q);
                if (yl.matches()) {
                    String sup = yl.group(1).toLowerCase(Locale.US);
                    ytSort = sup.contains("popular") || sup.contains("viewed") || sup.equals("top")
                            ? "popular" : "newest";
                    String rest = yl.group(2).trim();
                    ytChannel = rest.isEmpty() ? null : rest;
                    q = "";
                }
            }
            // Strip the SPEAKER too. Without this, "play Dead Can Dance on
            // bathroom Sonos" searched for the whole phrase including the
            // speaker name, which is why the results were unrelated songs.
            boolean namedSpeaker = SONOS_TARGET.matcher(low).find();
            if (namedSpeaker) {
                q = q.replaceAll("(?i)\\s*\\b(?:on|in|to)\\s+(?:the\\s+)?[a-z ]{0,16}\\bsonos\\b.*$", "")
                        .replaceAll("(?i)\\s*\\b(?:on|in|to)\\s+(?:the\\s+)?(?:bedroom|bathroom"
                                + "|kitchen|living room|office|dining room|patio|garage|den"
                                + "|studio|hallway)\\b.*$", "")
                        .replaceAll("(?i)\\s*\\bsonos\\b", " ")
                        .replaceAll("\\s+", " ").trim();
            }
            // "put on some music in ten minutes" is a request for LATER, not now.
            if (parseDelay(low) > 0) {
                q = "";
            }
            // Snapshot the phrase for the knowledge resolver BEFORE the
            // content-type/shuffle strip mangles it: "the first album by the
            // cure" must survive intact, not become "first by the cure".
            String descr = q;
            // "album" and "playlist" pick a different Spotify search and a
            // different Sonos URI, so the word is both a signal AND noise: it
            // must be recognised, then removed before the name is searched for.
            String contentType = null;
            if (low.matches(".*\\b(album|record|lp)\\b.*")) {
                contentType = "album";
            } else if (low.matches(".*\\b(playlist|mix)\\b.*")) {
                contentType = "playlist";
            }
            boolean shuffled = low.matches("^\\W*shuffle\\b.*")
                    || low.matches(".*\\b(shuffled?|on shuffle|randomi[sz]e)\\b.*");
            if (contentType != null || shuffled) {
                q = q.replaceAll("(?i)\\b(?:the\\s+)?(?:whole\\s+)?"
                                + "(album|record|lp|playlist|mix)\\b", " ")
                        .replaceAll("(?i)\\b(?:on\\s+)?shuffled?\\b|\\brandomi[sz]e\\b", " ")
                        .replaceAll("(?i)^\\s*(?:my|the)\\b\\s*", " ")
                        .replaceAll("\\s+", " ").trim();
            }
            // ytChannel means YT_LATEST already resolved this to "this channel's
            // own newest/most popular upload" and deliberately emptied q - that
            // is a complete request, not a missing one, so it must not fall into
            // the empty-q "didn't catch what to play" branch below.
            if ((!q.isEmpty() || ytChannel != null) && !DANGLING.matcher(q).find()) {
                // A named speaker means the speaker, not the phone. The whole
                // utterance rides along so the room can be matched against what
                // is actually on the network.
                Cmd c = namedSpeaker
                        ? new Cmd("sonos.play", 0, q, t)
                        : new Cmd("media.play", 0, q, service);
                c.shuffle = shuffled;
                c.contentType = contentType;
                c.descr = descr;
                c.ytSort = ytSort;
                c.ytChannel = ytChannel;
                return c;
            }
            if (DANGLING.matcher(q).find() || (q.isEmpty() && parseDelay(low) <= 0)) {
                // The glasses clip utterances, so "play the latest video by."
                // arrives with the name missing. Searching the fragment plays
                // something arbitrary; saying so is far better than guessing.
                return new Cmd("media.incomplete", 0, q);
            }
        }
        Matcher sf = SERVICE_FIRST.matcher(t);
        if (sf.find() && sf.group(2) != null && !sf.group(2).trim().isEmpty()) {
            return new Cmd("media.play", 0, sf.group(2).trim(),
                    sf.group(1).toLowerCase(Locale.US).replace(" ", ""));
        }
        Matcher tr = TRANSPORT.matcher(t);
        // Two ways a transport word is not a transport command:
        //   "pause the timer"  - aimed at something that is not the player
        //   "back to black BY amy winehouse" - it is the start of a title, and
        //     "by <artist>" is the tell. Skipping a track here would be the
        //     opposite of what was asked.
        if (tr.find() && !NOT_THE_PLAYER.matcher(low).find()
                && !low.matches(".*\\bby\\b.*")) {
            String action = tr.group(1).toLowerCase(Locale.US);
            // Carry the whole utterance so the room can be matched against the
            // speakers actually on the network, not a guessed list.
            return SONOS_TARGET.matcher(low).find()
                    ? new Cmd("sonos.control", 0, action, t)
                    : new Cmd("media.control", 0, action);
        }

        // Checked BEFORE the to-do pattern on purpose: "remind me to X at 11:20"
        // matches both, and falling through to TODO is exactly the bug where the
        // clock time became part of the label and nothing was ever scheduled.
        Clock clock = parseClock(low);
        if (clock != null && REMIND_ANY.matcher(low).find()) {
            return new Cmd("timer", clock.seconds, reminderLabel(t), clock.label);
        }

        Matcher nd = NOTE_DELETE.matcher(t);
        if (nd.find()) {
            return new Cmd("note.delete", 0,
                    nd.group(1) == null ? "" : nd.group(1).replaceAll("[\\s.,?!]+$", ""));
        }
        Matcher na = NOTE_ADD.matcher(t);
        if (na.find()) {
            for (int g = 1; g <= na.groupCount(); g++) {
                if (na.group(g) != null && !na.group(g).trim().isEmpty()) {
                    return new Cmd("note.add", 0,
                            na.group(g).replaceAll("^[\\s.,:;]+|[\\s.,?!]+$", ""));
                }
            }
        }
        Matcher nr = NOTE_READ.matcher(low);
        if (nr.find()) {
            String about = null;
            for (int g = 1; g <= nr.groupCount(); g++) {
                if (nr.group(g) != null && !nr.group(g).trim().isEmpty()) {
                    about = nr.group(g).replaceAll("[\\s.,?!]+$", "");
                }
            }
            return new Cmd("note.read", 0, about == null ? "" : about);
        }

        Matcher rc = REMOVE_CARD.matcher(t);
        if (rc.find()) {
            return new Cmd("done", 0,
                    rc.group(1).replaceAll("^[\\s.,:;!?]+|[\\s.,:;!?]+$", ""));
        }

        if (!TODO_ADD_VERB.matcher(low).find() && TODO_LIST.matcher(low).find()) {
            return new Cmd("todo.list", 0, "");
        }

        Matcher td = TODO.matcher(low);
        if (td.find()) {
            for (int g = 1; g <= td.groupCount(); g++) {
                if (td.group(g) != null) {
                    String txt = td.group(g).trim();
                    // recover original case from the tail of the utterance
                    int idx = low.lastIndexOf(txt.length() >= 12 ? txt.substring(0, 12) : txt);
                    // Bound by the GROUP's length. Taking the rest of the
                    // utterance only worked while every pattern put the item
                    // last; "add bagels to my to-do list" puts it in the middle,
                    // and the unbounded form stored "bagels to my to-do list".
                    String orig = idx >= 0
                            ? t.substring(idx, Math.min(t.length(), idx + txt.length()))
                            : txt;
                    return new Cmd("todo", 0, orig.replaceAll("^[\\s.,?]+|[\\s.,?]+$", ""));
                }
            }
        }
        return null;
    }

    /** The spoken transport word, mapped to what the media layer understands. */
    private static String transportAction(String spoken) {
        String s = spoken.toLowerCase(Locale.US);
        if (s.startsWith("pause")) {
            return "pause";
        }
        if (s.startsWith("resume") || s.startsWith("unpause")) {
            return "play";
        }
        if (s.startsWith("stop")) {
            return "stop";
        }
        if (s.startsWith("skip") || s.startsWith("next")) {
            return "next";
        }
        if (s.startsWith("previous") || s.startsWith("go back")) {
            return "previous";
        }
        if (s.startsWith("louder") || s.equals("volume up") || s.equals("turn it up")) {
            return "louder";
        }
        if (s.startsWith("quieter") || s.equals("volume down") || s.equals("turn it down")) {
            return "quieter";
        }
        return "toggle";
    }

    /** Run a parsed command and return the line to show on the glasses. */
    // ---- Descriptive "play X" resolution (knowledge -> verified Spotify) ----

    /**
     * Phrasings that are ALWAYS a description, never a literal title: they only
     * ever mean "work out which release I mean".
     */
    private static final Pattern DESC_STRONG = Pattern.compile(
            "\\b(?:debut|latest|newest|most\\s+recent|brand[-\\s]?new"
            // "their most popular album" was NOT here, so it was taken as a
            // literal title and searched for word-for-word - and Spotify
            // returned some other release by the artist.
            + "|most\\s+(?:popular|famous|successful|played|streamed)|best[-\\s]?known"
            + "|best[-\\s]?selling|top\\s+(?:album|song|track)"
            + "|greatest\\s+hits|best\\s+of|anthology|discography"
            + "|(?:song|track|theme|tune|music|score)\\s+from"
            + "|from\\s+(?:the\\s+)?(?:movie|film|show|series|tv|soundtrack|musical|game"
            + "|anime|advert|advertisement|ad|commercial)"
            + "|soundtrack|theme\\s+(?:song|tune|to|from)"
            + "|the\\s+(?:album|record|song|track|one)\\s+(?:with|where|that|about)"
            + "|album\\s+with|the\\s+one\\s+(?:where|that|with|about)"
            + "|cover\\s+of)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ordinals only count as descriptive next to a music noun (or an explicit
     * album/playlist request) - "first"/"last"/"final" live inside plenty of
     * real titles ("The Final Countdown", "Last Christmas").
     */
    private static final Pattern DESC_ORDINAL = Pattern.compile(
            "\\b(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth"
            + "|1st|2nd|3rd|\\d{1,2}th|last|final|earliest|penultimate"
            // These sit inside real titles too ("Biggest Part of Me",
            // "Breakthrough"), so they only DESCRIBE when a music noun says a
            // release is being picked out.
            + "|biggest|signature|breakthrough)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MUSIC_NOUN = Pattern.compile(
            "\\b(?:album|record|lp|ep|song|track|single|tune|release)\\b",
            Pattern.CASE_INSENSITIVE);

    // ---- Curated / genre / mood / "top N" requests -> a verified track list ----

    /** "top 10 ...", "5 best ...", "greatest new wave songs". */
    private static final Pattern SET_SUPERLATIVE = Pattern.compile(
            "\\btop\\s+\\d{1,2}\\b"
            + "|\\b\\d{1,2}\\s+(?:best|top|greatest|essential|classic|iconic|favou?rite)\\b"
            + "|\\b(?:best|greatest|essential|classic|iconic|top|favou?rite)\\b.{0,24}"
            + "\\b(?:songs?|tracks?|tunes?|hits?|anthems?|of\\s+all\\s+time)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GENRE = Pattern.compile(
            "\\b(?:new\\s?wave|synth[-\\s]?pop|post[-\\s]?punk|hip[-\\s]?hop|rap|trap|jazz|blues"
            + "|techno|house|disco|punk|grunge|metal|reggae|ska|funk|soul|r&b|rnb|motown|country"
            + "|classical|ambient|lo[-\\s]?fi|indie|shoegaze|dream\\s?pop|trip[-\\s]?hop"
            + "|drum\\s?and\\s?bass|dnb|edm|dubstep|gospel|folk|bluegrass|opera|k[-\\s]?pop"
            + "|afrobeats?|bossa\\s?nova|new\\s?age|emo|hardcore|britpop)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DECADE = Pattern.compile(
            "\\b(?:[5-9]0s|[012]0s|fifties|sixties|seventies|eighties|nineties|noughties)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SET_NOUN = Pattern.compile(
            "\\b(?:songs?|tracks?|tunes?|hits?|music|mix|playlist|anthems?|bangers?|jams?|vibes?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MOOD_SET = Pattern.compile(
            "\\b(?:workout|chill|party|study|focus|sleep|relax(?:ing)?|driving|road\\s?trip"
            + "|dinner|feel[-\\s]?good|pump[-\\s]?up|upbeat|romantic|happy|sad|morning)\\b"
            + ".{0,16}\\b(?:songs?|tracks?|music|mix|playlist|tunes?|hits?|vibes?|jams?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A request for a KIND of music (genre / era / mood / "top N" / "best of"),
     * as opposed to a specific artist, album, or song. Genre and decade count
     * only when asked for as a SET (with a set-noun or a leading "some/any/more"),
     * so "play daft punk" and "play house of pain" are NOT mistaken for the
     * genre inside the name and keep the fast artist path.
     */
    static boolean isCurated(String descr) {
        if (descr == null || descr.trim().isEmpty()) {
            return false;
        }
        if (SET_SUPERLATIVE.matcher(descr).find() || MOOD_SET.matcher(descr).find()) {
            return true;
        }
        if (!GENRE.matcher(descr).find() && !DECADE.matcher(descr).find()) {
            return false;
        }
        return SET_NOUN.matcher(descr).find()
                || descr.toLowerCase(Locale.US).matches("^(?:some|any|more|a\\s+bit\\s+of)\\b.*");
    }


    /**
     * A play phrase that only POINTS at something - "that album", "it", "the
     * one you mentioned" - names nothing at all, and searching for its words
     * finds whatever happens to contain them: "that album" found "That's The
     * Spirit". It has to be resolved against the conversation, so it counts
     * as descriptive. Anchored at both ends on purpose: "that's the spirit"
     * is a title and must stay one.
     */
    private static final Pattern ANAPHORA = Pattern.compile(
            "(?i)^\\s*(?:(?:the\\s+)?(?:same|that|this|those|it)"
            + "(?:\\s+(?:one|album|record|song|track|artist|band|playlist|thing))?"
            + "|the\\s+one\\s+(?:you|we|i|that)\\b.*"
            + "|the\\s+(?:album|song|track|record)\\s+(?:you|we)\\b.*"
            + "|(?:the\\s+)?(?:first|second|third|last)\\s+one)\\s*$");

    /** Does this play phrase describe a release rather than name it outright? */
    static boolean isDescriptive(String descr, String contentType) {
        if (descr == null || descr.trim().isEmpty()) {
            return false;
        }
        if (ANAPHORA.matcher(descr).find() || DESC_STRONG.matcher(descr).find()) {
            return true;
        }
        return DESC_ORDINAL.matcher(descr).find()
                && (contentType != null || MUSIC_NOUN.matcher(descr).find());
    }

    /**
     * Words that mark what FOLLOWS a possessive as a description of a release
     * rather than part of its title. "acdc's 2nd most popular album" describes
     * one; "Sgt. Pepper's Lonely Hearts Club Band" is one. Getting that
     * backwards would refuse to play half the Beatles.
     */
    private static final Pattern POSSESSIVE_DESC = Pattern.compile(
            "(?i)['’]s?\\s+(?:\\d{1,2}(?:st|nd|rd|th)\\s+)?"
            + "(?:most|best|greatest|biggest|top|first|second|third|fourth|fifth|last|final"
            + "|latest|newest|debut|earliest|signature|breakthrough|only|entire|whole"
            + "|album|record|lp|ep|single|song|track|discography|anthology)\\b");

    /**
     * The possessive itself: "acdc's ...", "the art of noise's ...", "the
     * doors' ...". A leading play verb is tolerated so this gives the same
     * answer for a whole utterance as for the query already stripped out of
     * it - callers pass both.
     */
    private static final Pattern POSSESSIVE_ARTIST = Pattern.compile(
            "(?i)^\\s*(?:(?:please\\s+)?(?:play|put\\s+on|throw\\s+on|start\\s+playing"
            + "|shuffle|listen\\s+to)\\s+)?(?:the\\s+)?"
            + "([a-z0-9][a-z0-9 &.'’/+-]*?)['’]s?\\s+");

    /**
     * "... album by the cure" - the artist named outright at the end, but only
     * with a release word standing in front of "by". Titles carry a "by" of
     * their own - "Stand By Me", "Fly By Night", "One By One" - and a hint
     * taken out of one of those ("me", "night") would reject the very record
     * that was asked for.
     */
    private static final Pattern ARTIST_BY = Pattern.compile(
            "(?i)\\b(?:albums?|records?|lps?|eps?|songs?|tracks?|singles?|hits"
            + "|anthology|discography|music|stuff|anything|something)\\s+by\\s+"
            + "([a-z0-9][a-z0-9 &.'’/+-]{1,40}?)\\s*$");

    /**
     * The artist these words NAME, when they name one as the owner of a
     * described release - "acdc's most popular album" -> "acdc", "the best
     * album by the cure" -> "the cure". Null when the words are (or contain) a
     * title rather than a description, because then the title is what should
     * be matched and an artist check would reject the real record.
     *
     * This is the guard that was missing when "acdc's 2nd most popular album"
     * played an album called "Most Popular Nursery Rhymes": the relevance
     * check compares the words against the TITLE only, and two of them
     * happened to be in it. Nothing ever asked whose record it was.
     */
    static String artistHint(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return null;
        }
        Matcher by = ARTIST_BY.matcher(phrase);
        if (by.find()) {
            return by.group(1).trim();
        }
        if (!POSSESSIVE_DESC.matcher(phrase).find()) {
            return null;                            // a title, not a description
        }
        Matcher m = POSSESSIVE_ARTIST.matcher(phrase);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Whether a Spotify credit is the artist the words asked for. */
    static boolean artistIs(String hint, String actual) {
        if (hint == null || hint.isEmpty()) {
            return true;                            // nothing was claimed
        }
        String a = squash(hint);
        String b = squash(actual);
        return !a.isEmpty() && !b.isEmpty() && (b.contains(a) || a.contains(b));
    }

    /** Comparable form: lowercase, no "the", letters and digits only. */
    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US)
                .replaceAll("^the\\s+", "").replaceAll("[^a-z0-9]", "");
    }

    /** Outcome of a resolution attempt: exactly one field is non-null. */
    private static final Pattern LIST_COUNT = Pattern.compile(
            "\\btop\\s+(\\d{1,2})\\b|\\b(\\d{1,2})\\s+(?:best\\s+|top\\s+|greatest\\s+)?"
            + "(?:songs?|tracks?|tunes?|hits?|anthems?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** How many tracks a curated request asked for (default 10, clamped 1-20). */
    static int listCount(String descr) {
        if (descr != null) {
            Matcher m = LIST_COUNT.matcher(descr);
            if (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                try {
                    int n = Integer.parseInt(g);
                    if (n >= 1 && n <= 20) {
                        return n;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 10;
    }

    /** Outcome of a resolution attempt: exactly one of hit / list / say is set. */
    static final class Resolved {
        final String[] hit;         // single release [id, uri, "Name - Artist", kind]
        final String[] listIds;     // a curated set: track ids for Sonos.playTracks
        final String[] listUris;    // the same set: track uris for phone playback
        final String label;         // label for the set
        final String say;           // a line to speak instead (uncertain / not found)

        private Resolved(String[] hit, String[] listIds, String[] listUris,
                         String label, String say) {
            this.hit = hit;
            this.listIds = listIds;
            this.listUris = listUris;
            this.label = label;
            this.say = say;
        }

        static Resolved say(String s) {
            return new Resolved(null, null, null, null, s);
        }

        static Resolved single(String[] hit) {
            return new Resolved(hit, null, null, null, null);
        }

        static Resolved list(String[] ids, String[] uris, String label) {
            return new Resolved(null, ids, uris, label, null);
        }
    }

    /**
     * Resolve a request the literal search cannot: a CURATED set (genre / mood /
     * era / "top N") becomes a verified track list; a DESCRIPTIVE single release
     * ("the first album by the cure") becomes one verified album or track.
     * Returns null for a plain literal request (the fast path handles it) or when
     * the model is unreachable / returns nothing usable (degrade to the literal
     * path's honest "not found"). Nothing is ever played that was not verified on
     * Spotify.
     */
    static Resolved resolveDescriptive(Context ctx, String descr, String contentType) {
        return resolveDescriptive(ctx, descr, contentType, null);
    }

    /**
     * @param said what the live voice model already told the user about this
     *             request, or null. Reaches the resolver's prompt only - never
     *             the classifiers, which would mis-read free prose.
     */
    static Resolved resolveDescriptive(Context ctx, String descr, String contentType, String said) {
        if (!Media.spotifyConfigured(ctx)) {
            return null;
        }
        if (isCurated(descr)) {
            return resolveCurated(ctx, descr);      // null falls through to the literal path
        }
        if (!isDescriptive(descr, contentType)) {
            return null;                            // literal fast path, zero LLM calls
        }
        // The spoken sentence joins the request HERE - after the classifiers
        // above have decided what kind of request this is, and only for the
        // resolver that has to name a release.
        String ask = said == null || said.trim().isEmpty() ? descr
                : descr + "\n\nContext:\n" + said.trim();
        org.json.JSONObject r = Llm.resolveMusic(ctx, ask,
                ("album".equals(contentType) || "playlist".equals(contentType)) ? "album" : null);
        if (r == null) {
            // Knowledge unavailable. This used to fall through to the literal
            // path, which searched Spotify for the DESCRIPTION's words and
            // played whatever came back - the wrong album, confidently. A
            // description with no resolver behind it has no honest answer.
            return Resolved.say("♪ I can't work out which release that is right now"
                    + " - name it and I'll play it.");
        }
        String artist = r.optString("artist", "").trim();
        String title = r.optString("title", "").trim();
        String kind = r.optString("kind", "album").trim().toLowerCase(Locale.US);
        boolean confident = r.optBoolean("confident", false);
        if (title.isEmpty()) {
            return Resolved.say("\u266a I'm not sure which one you mean \u2014 name the album or song.");
        }
        String guess = title + (artist.isEmpty() ? "" : " by " + artist);
        if (!confident) {
            return Resolved.say("\u266a Did you mean " + guess + "? Say it by name to play it.");
        }
        String[] hit = "track".equals(kind)
                ? Media.findTrack(ctx, artist, title)
                : Media.findAlbum(ctx, artist, title);
        if (hit == null) {
            return Resolved.say("\u266a I think that's " + guess
                    + ", but I couldn't find it on Spotify to play.");
        }
        return Resolved.single(hit);                // verified: hit[2] is the REAL name
    }

    /**
     * A curated/genre/mood/"top N" request, resolved in two tiers.
     *
     * FIRST, the exact set the user asked for: the model names real songs, each
     * is VERIFIED on Spotify (Media.verifyQueue -> findTrack), and they are
     * queued as individual tracks (Sonos.playTracks / the phone's Web-API queue).
     * This is what "top 10 new wave songs" means, and it plays a curated set of
     * real, correctly-credited songs.
     *
     * FALLBACK, when the model is unavailable or returns nothing usable (it
     * returns an empty list when the phrase actually named a specific artist):
     * a matching genre PLAYLIST container, then a compilation album. Editorial
     * playlists are nulled for this token, so this lands on a user playlist -
     * fine for a genre, unlike for a specific artist. Never a wrong track.
     */
    static Resolved resolveCurated(Context ctx, String descr) {
        int count = listCount(descr);
        org.json.JSONObject plan = Llm.resolveList(ctx, descr, Math.min(count + 3, 18));
        String[][] q = Media.verifyQueue(ctx, plan, count);
        if (q != null && q[0].length >= 2) {
            // Strip a trailing count the model tends to echo ("New Wave - Top
            // 13"); the real number queued is appended by Sonos.playTracks.
            String label = plan == null ? "" : plan.optString("label", "").trim()
                    .replaceAll("(?i)\\s*[-–—(]?\\s*top\\s+\\d+\\s*\\)?\\s*$", "").trim();
            if (label.isEmpty()) {
                String theme = curatedQuery(descr);
                label = theme.isEmpty() ? "Your mix" : theme;
            }
            return Resolved.list(q[0], q[1], label);
        }
        String query = curatedQuery(descr);
        if (query.isEmpty()) {
            query = descr == null ? "" : descr.trim();
        }
        String[] hit = Media.searchContainer(ctx, query, "playlist");
        if (hit == null) {
            hit = Media.searchContainer(ctx, query, "album");
        }
        if (hit == null) {
            return Resolved.say("\u266a I couldn't find music for " + query + ".");
        }
        return Resolved.single(hit);
    }

    /**
     * Reduce a curated phrase to a searchable genre/theme:
     * "top 10 new wave songs" -> "new wave"; "80s workout music" stays as is.
     */
    static String curatedQuery(String descr) {
        if (descr == null) {
            return "";
        }
        return descr.replaceAll("(?i)\\btop\\s+\\d{1,2}\\b", " ")
                .replaceAll("(?i)\\b\\d{1,2}\\b", " ")
                .replaceAll("(?i)\\b(?:best|top|greatest|essential|classic|iconic|favou?rite)\\b", " ")
                .replaceAll("(?i)\\b(?:songs?|tracks?|tunes?|hits?|anthems?|bangers?|jams?)\\b", " ")
                .replaceAll("(?i)\\bof all time\\b", " ")
                .replaceAll("(?i)\\bsome\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * The travel mode for a "navigate to X" request. The LEADING verb decides it
     * ("walk to X" -> walk, "bicycle to X" -> bike, "public transportation to X"
     * -> transit), so a mode word inside the place name never confuses it. A
     * generic trigger (navigate / take me / directions) has no implied mode, so
     * a trailing modifier is honored ("navigate to X walking"), else driving -
     * Google Maps' own default.
     */
    static String navMode(String trigger, String rest) {
        String tl = trigger == null ? "" : trigger.toLowerCase(Locale.US);
        if (tl.startsWith("walk")) {
            return "walk";
        }
        if (tl.startsWith("drive")) {
            return "drive";
        }
        if (tl.startsWith("bicycle") || tl.startsWith("bike") || tl.startsWith("cycle")) {
            return "bike";
        }
        if (tl.contains("transit") || tl.contains("transp") || tl.contains("bus")
                || tl.contains("train") || tl.contains("bart") || tl.contains("subway")) {
            return "transit";
        }
        String r = rest == null ? "" : rest.toLowerCase(Locale.US);
        if (r.matches(".*\\b(?:on foot|walking)\\s*$")) {
            return "walk";
        }
        if (r.matches(".*\\b(?:driving|by car)\\s*$")) {
            return "drive";
        }
        if (r.matches(".*\\b(?:biking|bicycling|cycling)\\s*$")) {
            return "bike";
        }
        if (r.matches(".*(?:\\bby\\s+(?:bus|train|bart|subway|transit)|public\\s+trans\\w*"
                + "|via\\s+transit)\\s*$")) {
            return "transit";
        }
        return "drive";
    }

    /**
     * Run a command. This is the one choke point every play goes through -
     * a crown press, a voice-session delegation, or a tool call all end here -
     * so it is where the "what is playing" poll is told the assistant itself
     * just started something and should not announce the switch as news.
     */
    public static String run(Context ctx, Cmd cmd) {
        String out = run0(ctx, cmd);
        if (out != null && (Media.playing(out)
                || (out.startsWith("◉") && !out.contains("?")))) {
            Proactive.playbackStarted();
        }
        return out;
    }

    private static String run0(Context ctx, Cmd cmd) {
        try {
            switch (cmd.kind) {
                case "timer": {
                    if (cmd.seconds <= 0) {
                        return "Timer needs a duration - try '10 minutes'.";
                    }
                    JSONObject t = Store.addTimer(ctx, cmd.seconds, cmd.text);
                    String label = t.optString("label");
                    // Mirror anything appointment-shaped into the Jarvis
                    // calendar, so it also lands on the glasses' dashboard and
                    // gets a second notification from the phone's calendar at
                    // the due time. Best effort: see Agenda for why a failure
                    // here is deliberately invisible.
                    if (Agenda.worthKeeping(cmd.seconds, cmd.due != null)) {
                        Agenda.add(ctx, label,
                                System.currentTimeMillis() + cmd.seconds * 1000L);
                    }
                    if (cmd.due != null) {
                        // Say the clock time back, so a misheard hour is obvious
                        // immediately rather than at the moment it fails to fire.
                        return "⏰ " + label + " at " + cmd.due
                                + " (in " + span(cmd.seconds) + ").";
                    }
                    return "⏰ Timer set: " + label + " in " + span(cmd.seconds) + ".";
                }
                case "note.add": {
                    Notes.add(ctx, cmd.text);
                    return "▤ Noted: " + cmd.text;
                }
                case "note.delete": {
                    if (cmd.text == null || cmd.text.trim().isEmpty()) {
                        // Refusing is the right answer: "delete my notes" with
                        // nothing to match would take the lot.
                        return "▤ Which note? Name something in it.";
                    }
                    int gone = Notes.remove(ctx, cmd.text);
                    return gone == 0 ? "▤ No note matching that."
                            : "▤ Deleted " + gone + " note" + (gone == 1 ? "" : "s") + ".";
                }
                case "note.read":
                    return Notes.line(ctx, cmd.text);
                case "todo.list": {
                    java.util.List<String> open = Store.openTodos(ctx);
                    if (open.isEmpty()) {
                        return "✓ Nothing on the list.";
                    }
                    // The lens gets one line, so join rather than enumerate;
                    // Cards clips it and now spills the opening into the title.
                    StringBuilder sb = new StringBuilder("✓ ");
                    for (int i = 0; i < open.size(); i++) {
                        sb.append(i == 0 ? "" : " · ").append(open.get(i));
                    }
                    return sb.toString();
                }
                case "todo": {
                    JSONObject t = Store.addTodo(ctx, cmd.text);
                    Mirror.onDataChanged();          // reach the card in seconds
                    return "✓ Added: " + t.optString("text");
                }
                case "media.now":
                    return Media.nowPlaying(ctx);
                case "media.control": {
                    String act = transportAction(cmd.text);
                    // Radio on the PHONE is our own MediaPlayer, and a media
                    // key never reaches it - the keyevent goes to whichever app
                    // holds the media session, so without this "stop" would
                    // report success while the stream kept playing.
                    if (Radio.current() != null
                            && ("stop".equals(act) || "pause".equals(act))) {
                        String was = Radio.current();
                        Radio.stopPhone();
                        return "⏹ " + was + " stopped.";
                    }
                    return Media.control(ctx, act);
                }
                case "media.play": {
                    // Descriptive resolution on the phone too (Spotify only).
                    if (!"youtube".equals(cmd.due) && !podcastApp(cmd.due)) {
                        Resolved rd = resolveDescriptive(ctx, cmd.descr, cmd.contentType, cmd.said);
                        if (rd != null) {
                            String out;
                            if (rd.say != null) {
                                out = rd.say;
                            } else if (rd.listUris != null) {
                                out = Media.playTracks(ctx, rd.listUris, rd.label, cmd.shuffle);
                            } else {
                                out = Media.playUri(ctx, rd.hit[1], !"track".equals(rd.hit[3]),
                                        rd.hit[2], cmd.shuffle);
                            }
                            return cmd.onSpeaker && out.startsWith("▶")
                                    ? out + " (phone)" : out;
                        }
                    }
                    String played;
                    if ("youtube".equals(cmd.due)) {
                        played = Media.youtube(ctx, cmd.text, cmd.ytSort, cmd.ytChannel,
                                cmd.ytSince);
                    } else if (podcastApp(cmd.due)) {
                        played = Media.pocketcasts(ctx, cmd.text);
                    } else {
                        played = Media.spotify(ctx, cmd.text, cmd.contentType, cmd.shuffle);
                    }
                    // A speaker was named but playback only reaches the phone:
                    // say where it actually went rather than let it look as
                    // though the speaker was used.
                    return cmd.onSpeaker && Media.playing(played)
                            ? played + " (phone)"
                            : played;
                }
                case "media.incomplete":
                    return "♪ Didn't catch what to play - say it again?";
                case "nav.start":
                    // Launch Google Maps turn-by-turn; NavListener relays the
                    // live steps to the glasses. cmd.due carries the travel mode.
                    return Media.navigate(ctx, cmd.text, cmd.due);
                case "nav.stop":
                    return NavListener.stopNavigation(ctx);
                case "assist.open":
                    // With a question, hand it to Gemini and relay the reply;
                    // with none, just wake the assistant and say so.
                    return cmd.text == null || cmd.text.trim().isEmpty()
                            ? Media.assistant(ctx, null)
                            : Assist.ask(ctx, cmd.text);
                case "radio.play": {
                    if (Radio.vague(cmd.text)) {
                        // "play the radio" names nothing. Ask, rather than pick
                        // a station at random and present it as the answer.
                        return "◉ Which station? Say a name or a genre.";
                    }
                    String[] st = Radio.find(ctx, cmd.text);
                    if (st == null) {
                        // Say what was actually searched for. "No station" with
                        // the words back is a usable answer; a bare failure is
                        // indistinguishable from the network being down.
                        return "No station found for \"" + cmd.text + "\".";
                    }
                    if (cmd.due != null) {
                        return Sonos.playRadio(ctx, Sonos.pick(ctx, cmd.due), st[1], st[0]);
                    }
                    return Radio.playOnPhone(ctx, st[1], st[0]);
                }
                case "sonos.play": {
                    Sonos.Zone zone = Sonos.pick(ctx, cmd.due);
                    // A DESCRIPTIVE request ("the first album by the cure") is
                    // resolved by the model to a concrete name, verified on
                    // Spotify, then played by URI on the Sonos. Literal requests
                    // return null here and fall straight through, unchanged.
                    Resolved rd = resolveDescriptive(ctx, cmd.descr, cmd.contentType, cmd.said);
                    if (rd != null) {
                        if (rd.say != null) {
                            return rd.say;
                        }
                        if (rd.listIds != null) {
                            return Sonos.playTracks(ctx, zone, rd.listIds, rd.label, cmd.shuffle);
                        }
                        return "track".equals(rd.hit[3])
                                ? Sonos.playSpotify(ctx, zone, rd.hit[0], rd.hit[2])
                                : Sonos.playContainer(ctx, zone, rd.hit[3],
                                        rd.hit[0], rd.hit[2], cmd.shuffle);
                    }
                    // An artist name beats any container reading of it: "the
                    // playlist Men Without Hats" means the band, and a stranger's
                    // playlist with that title is not their music.
                    if (!"album".equals(cmd.contentType)) {
                        String[] artist = Media.artistMatch(ctx, cmd.text);
                        if (artist != null) {
                            // A full-catalogue playlist for the artist -
                            // Spotify's "This Is <artist>" if reachable, else the
                            // artist's OWN official playlist. Sonos plays it by
                            // URI on its OWN Spotify account, so our token only
                            // has to FIND it, never read its contents (403 here).
                            String[] pl = Media.artistPlaylist(ctx, artist[1]);
                            if (pl != null) {
                                return Sonos.playContainer(ctx, zone, "playlist",
                                        pl[0], pl[2], cmd.shuffle);
                            }
                            // No "This Is" playlist for them: one of their ALBUMS
                            // is the next-best real set of songs (a track search
                            // of a hit-heavy catalogue collapses to almost nothing
                            // once variants are removed - "Men Without Hats" -> two).
                            String[] alb = Media.searchContainer(ctx, artist[1], "album");
                            if (alb != null) {
                                return Sonos.playContainer(ctx, zone, "album",
                                        alb[0], alb[2], cmd.shuffle);
                            }
                            String[] ids = Media.artistTopTracks(ctx, artist[0], artist[1], 10);
                            return Sonos.playTracks(ctx, zone, ids, artist[1], cmd.shuffle);
                        }
                    }
                    if (cmd.contentType != null) {
                        String[] c = Media.searchContainer(ctx, cmd.text, cmd.contentType);
                        if (c == null) {
                            return "♪ No " + cmd.contentType + " found for " + cmd.text + ".";
                        }
                        return Sonos.playContainer(ctx, zone, cmd.contentType, c[0], c[2],
                                cmd.shuffle);
                    }
                    String[] track = Media.searchTrack(ctx, cmd.text);
                    if (track == null) {
                        return "♪ Couldn't find " + cmd.text + " on Spotify.";
                    }
                    // [0]=id for the Sonos URI, [1]=spotify: uri, [2]=display label
                    return Sonos.playSpotify(ctx, zone, track[0], track[2]);
                }
                case "sonos.control":
                    return Sonos.control(ctx, Sonos.pick(ctx, cmd.due), cmd.text);
                case "sonos.now":
                    return Sonos.nowPlaying(ctx, Sonos.pick(ctx, cmd.due));
                case "done": {
                    JSONObject t = Store.completeTodo(ctx, cmd.text);
                    if (t != null) {
                        Mirror.onDataChanged();      // take it off the glasses now
                        return "✓ Done: " + t.optString("text");
                    }
                    // Not a to-do, so try whatever else is on the card - an
                    // appointment you have been to, a reminder you have dealt
                    // with. Only the glasses' copy goes; a real calendar event
                    // is left exactly where it is.
                    String off = Mirror.dismiss(ctx, cmd.text);
                    return off == null ? "Nothing matching that to tick off."
                            : "✓ Cleared: " + off;
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return "Could not save that (" + e + ")";
        }
    }
}
