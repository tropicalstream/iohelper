# iohelper — a standalone voice assistant for RayNeo iO glasses

Press the **crown** on the glasses, ask a question, and the answer appears on
the lens a few seconds later — no PC, no wake word, nothing to say first.
Answers come from **Groq**, the **latest free Gemini**, or **Claude via OAuth**
(your Claude Code login — no API key), with **SerpApi** live data for
weather / commute / events / news / prices. Timers, to-dos, notes and calendar
heads-ups are handled locally, and live Google Maps turn-by-turn, media
control and Sonos playback are relayed straight to the lens.

It runs as one Android app on the phone — no computer required — and is
designed **not to interfere with RayNeo's own voice integration** — see
*Independence* below.

```
your voice → glasses mic → RayNeo ASR → transcript (read-only from the phone's logcat)
    → crown press is the trigger → local command | LLM (+ SerpApi + calendar)
        → notification on the phone → RNLink/SPP → glasses display  (render receipt confirms)
```

## Quick start

1. Get the APK — grab the prebuilt `iohelper.apk` from this repo, or build it
   yourself with `python android/build.py` (no Gradle).
2. Sideload it, open it once, and grant the permissions it asks for
   (notifications, location, calendar) — notifications are not optional,
   cards *are* notifications.
3. Turn on **Developer options → Wireless debugging** and pair the app to the
   phone from inside the app itself. This is what lets it read the glasses'
   transcripts with no PC in the loop — see *Installing the phone app* below
   for the full walkthrough.
4. Paste a **Groq** API key (free at https://console.groq.com/keys) into the
   app's settings. SerpApi, Spotify and the rest are optional, for live data
   and music.
5. In RayNeo's own app, allow this app to post notifications to the glasses,
   so cards can actually reach the lens.
6. Wear the glasses, press the **crown**, and ask — *"what's the weather in
   Oakland?"* The crown press is the trigger; there is no wake word to say.

## Independence from RayNeo's voice system

- It **only reads** the phone's logcat. It never sends input to the glasses,
  never restarts or touches the companion app, and never writes to RayNeo's
  `VOICE_ASSISTANT` channel.
- Two transcript sources. **`assistant`** is the crown-press channel — this is
  the working default. Pressing the crown *is* the trigger, so nothing needs
  to be said to invoke it; RayNeo answers on this channel too, so you get two
  replies. **`alwayson`** (Life Log) is a hands-free alternative with no
  button to press, so it instead arms on a configurable spoken trigger word to
  tell a question from ordinary conversation — RayNeo *transcribes* that
  stream but does not answer it, so the trigger word never sets off their
  assistant and theirs never sets off ours.
- **Measured 2026-09-04 on Strix OS 1.0.3.11: the always-on stream is broken.**
  Audio reaches the phone (`[AO_TRACE][A3_RECV]`, hundreds of frames) and
  `alwaysOnRunning=true`, but the voice-assistant runtime re-initialises every
  ~5 s and emits **zero** `onAlwaysOnResponse`; the A3 delivery queue pegs at its
  200-frame cap. The crown channel on the same device works perfectly in the same
  session (`phone_asr_text` / `VOICE_ASSISTANT` flowing). It survived a phone
  reboot, re-pair, and app reinstall — a vendor-side fault, not a config one.
  **So `wake.source = assistant` is the working default until RayNeo fixes it.**
- Cards use **our own notification tag and title**, so they update in place and
  never collide with RayNeo's or any other app's notifications.
- Own process, own config (`config.json`), own store, own log, no shared files.

## Graphics

The relay forwards only a notification's **title and body** — icons, styles
and images are silently dropped (measured; the payload is byte-identical). What
renders is any Basic-Multilingual-Plane character, so `glyphs.py` composes
"graphics" from glyphs: `☀ ☁ ☂ ⚡ ⏰ ✓ ⚠ ★ →`, level bars (`▓▓▓▓░░`), trends.
Weather answers get a weather glyph automatically; timers show `⏰`, to-dos `✓`.
Non-BMP emoji are stripped because they render inconsistently.

Display limits (measured): body **≤115** chars renders fully; 116–130 is cut;
**≥131 never renders at all** — the app clips to 115. The limit is **characters,
not bytes**: a 107-char / 152-byte glyph frame renders in full, so glyphs cost
nothing beyond their character count.

**The card is three lines, and the app used to waste one.** The glasses draw
app label / title / body, and this app put its own name on the first two, so
every card repeated "Jarvis" and threw the second line away. Measured on the
lens with a ruler: the title carries **~59 characters**, on top of the body's
115. `Cards.post` now spills the opening of an over-long answer into that line
at a word boundary, so a long answer arrives as ~173 characters instead of 115
— verified on the lens at 172. Short answers are untouched: a one-line reply
reads better under a plain header than split across two.

**Where that limit lives (measured 2026-09-06, RayNeo app 1.0.2 / Strix OS
1.0.3.15):** the phone-side relay is *not* the bottleneck. It logs the exact
frame it sends (`logcat | grep rayneonet_message_send_success`), and a 200-char
body and a 150-char title both crossed the link intact — the cut is the glasses'
renderer. The frame is `{notificationUID, appId, appName, title, subtitle,
content, timestamp, category, reply, type}` and the relay reads **only
`contentTitle` and `contentText`**: BigText, InboxStyle lines, subText,
summaryText and contentInfo are all ignored; `subtitle` is always null on
Android; `category` and `reply` come from RayNeo's server-side app list, not
from anything the posting app sets. MessagingStyle *is* parsed (title becomes
`Conversation: Sender`, body the last message). Ongoing notifications and
`CATEGORY_CALL` are dropped silently; removals are never forwarded; every
re-post is a new card. Shell-posted (`cmd notification post`) notifications are
not on the relay's allowlist at all. Probe any of this with the app's `SHOWX`
broadcast (`sub`, `big`, `lines`, `conv`/`msgs`, `cat`, `reply`, `ongoing`,
`len`, `summary`, `info`).

**Delivery confirmation changed with the firmware.** Older builds had the glasses
reply with an `analytics_log` carrying `display_result: success` for every drawn
card. On Strix OS 1.0.3.11 (as of 2026-09) they no longer send `analytics_log`
at all — only `brightness_change` — so a card can draw perfectly while that
receipt never arrives. Treating its absence as failure produces a false negative
on *every* send. `confirm_since()` therefore uses the phone's RNLink transmit ack
(`rayneonet_message_send_success`) as the primary signal and still prefers a real
render receipt when the firmware provides one.

## Portal (Termux/legacy build only — `web/index.html`, stdlib server, no dependencies)

The Android app configures from its own settings screen (`MainActivity`), no
browser needed. The Termux build (see *Files* below) instead exposes a web
portal: a config editor for every setting, live status (adb link, listening,
backend and model, SerpApi quota, capture/fire/delivery counters), a timeline
of what was heard and answered, the log, model discovery (**Load** buttons
list what your key can use; empty = auto-pick newest free Flash / 70B), quick
timer/to-do, raw send, and reconnect. Binds to `127.0.0.1`; to expose it on
the LAN set `portal.host` **and** `portal.token` (requests then need
`X-Token`).

## Files

**The Android app** (`android/java/com/iohelper/card/`) — the primary build,
runs entirely on the phone:

| | |
| --- | --- |
| `AssistantService.java` | the loop: transcript → command / LLM → card |
| `Wake.java` | the spoken-trigger gate, used only by the `alwayson` source |
| `Llm.java` | Groq / Gemini / Claude-OAuth / OpenAI-compatible + model discovery |
| `Search.java` | SerpApi router (weather, directions, events, news, local) |
| `Cards.java` | notification transport, render receipts, screen-wake re-post |
| `Commands.java` | timers, to-dos, calendar commands, Spotify / Sonos / Maps control |
| `Mirror.java` | mirrors upcoming real-calendar events onto the glasses' dashboard |
| `Agenda.java` | writes reminders into a private local calendar so they survive being missed |
| `NavListener.java` | relays Google Maps' live turn-by-turn to the lens |
| `MainActivity.java` | settings screen, pairing, start/stop |
| `android/build.py` | build: `aapt2 → javac → d8 → zipalign → apksigner`, no Gradle |
| `android/verify/` | offline test suites for the command parser and the crypto |

**The Termux build** (`termux/iohelper/`) — the original Python service,
still usable inside Termux instead of the native app (`termux/README-TERMUX.md`):

| | |
| --- | --- |
| `assistant.py` | the loop: transcript → wake gate → command / LLM → card |
| `wake.py` | wake-word gate (strict match, trailing wake, soft arm, dedup) |
| `llm.py` | Groq / Gemini / Claude-OAuth / OpenAI-compatible + model discovery |
| `search.py` | SerpApi router (weather, directions, events, news, local) |
| `display.py` | adb → notification transport, render receipts, screen-wake re-post |
| `glyphs.py` | BMP "graphics" |
| `commands.py` | timers, to-dos, phone calendar |
| `proactive.py` | unprompted calendar / timer / to-do pushes |
| `portal.py` + `web/` | the web portal |
| `service.py` | one process, three supervised threads; CLI |

`tests/test_core.py` holds the deterministic tests for the Termux build
(`python tests/test_core.py`); `android/verify/` holds the Android app's.

## Facts this is built on (all measured on iO-F761 / Strix OS 1.0.3.11)

adb reaches only the **phone** — the glasses have no USB/Wi-Fi/PANU path; the
companion app is a mandatory relay. Speech recognition is cloud-based. There is
no exported Android component that can re-arm the glasses' always-on capture:
in standby nothing is captured at all, so if there's no answer, **wake the
display (crown/tap) and re-ask** — waking re-arms the mic. Cards render (and
ACK) even to a dark screen, so the last card is re-posted once when the display
wakes within two minutes.

## The card header (`android/`)

The glasses render **`<app label>` / `<title>` / `<body>`**, and the app label is
whichever package posted the notification. `adb shell cmd notification post`
posts as `com.android.shell`, so every card used to read "Shell". There is no
flag to change it (`cmd notification post --help` offers only
`--title/--icon/--style`, and RayNeo ignores custom icons).

`android/` is a ~13 KB app whose **label is the header**. It is built without
Gradle — `aapt2 → javac → d8 → zipalign → apksigner`, all discovered from the
Android SDK:

```bash
python android/build.py --install --test      # build, install, post a test card
python android/build.py --label "Athena"      # rename the header
```

`d8` is invoked through an `@argfile`: passing every class on one command line
overflowed Windows' 8191-character limit once the app passed about a hundred
classes, and the failure reads "The input line is too long" with no mention of
d8. Note that d8 does not unquote argfile entries - one bare path per line, or
it dies on `Illegal char <">`.

`display.send()` broadcasts to it when it is installed and falls back to the
shell path when it is not — though as of RayNeo app 1.0.2 the shell path is no
longer forwarded at all, so the app is effectively required. One thing becomes
possible only through the app: **cancelling** a card — `cmd notification` has
no cancel subcommand. The per-card header extra (`android.substName`) is
accepted but **not honoured** by the current relay: the frame's `appName` is
always the package label (measured; `header=Athena` still went out as
"Jarvis"), so renaming the header means rebuilding with `--label`.

## Other surfaces on the glasses

Besides the notification card the glasses have a dashboard (schedule, to-do,
weather, stock, world clock, Gmail unread), an incoming-call card, a
teleprompter, live captions/translation and the AI answer view. Pulling the
companion app apart (2026-09-06) and probing over adb, **exactly one of those
is reachable from another app: the schedule card, through the system
calendar.** Inserting a `CalendarContract` event made the relay push a
`businessId=SCHEDULE_TODO` batch within seconds, carrying `{title, startTime,
endTime, location, isTomorrowSchedule, status}` per event — `location` crosses,
`description` does not. Everything else is written from inside RayNeo's own
process (its servers, Gmail OAuth, telephony state, or its editor's file
import); the app exports no component that accepts text, and `rayneo.venus.app`
deep links land on the home tab.

**The schedule card, measured on the lens.** Three lines — title, a time range
the glasses draw themselves, and location — and roughly **30 characters per
line**, so it holds less text than the notification body's 115. Its value is
not capacity but **persistence**: the card stays on the active list until the
event passes, where a notification card is gone the moment it is missed.
Several events list together. Events written to a calendar this app creates
are picked up like any other, so no real calendar has to be touched — see
`Agenda.java`.

## To-dos

Two bugs made the to-do list look like it was losing things, and both are
fixed:

- **"todo" / "to-do" / "to do" are one word by voice.** Spoken aloud they are a single sound and the ASR spells it however it likes, so every to-do path (add, list, complete) accepts all three via `to ?-? ?do`. They did not used to: "add to do buy milk" and "complete the to do X" matched nothing and fell through to the LLM's honest "I could not do that". The spelling distinction is now invisible to the speaker, which is the only correct behaviour for a voice interface.
- **Adding only worked list-first.** The pattern accepted `add a todo <thing>`
  but not `add <thing> to my to-do list` — the commonest way to say it — so the
  item was dropped in silence: no card, no error, no entry. The trailing-list
  form is accepted now (`add|put|jot|note … to/on my to-do list|todos|tasks`),
  including the VAD-clipped `dd` that the glasses' mic produces.
- **Nothing could read the list back.** `openTodos` existed but only
  `Proactive` used it, for unprompted nudges; "what's on my to-do list" fell
  through to the LLM, which has never seen the store and answered that it had
  not been provided with one. `todo.list` handles it locally now — it requires
  a reading verb, so `add bagels to my list` still adds rather than reads.

## What the assistant knows about you

The point of holding this here rather than letting RayNeo sync it is that
RayNeo can *display* your data but cannot be asked anything about it. Every one
of these is pasted into the prompt, so a question can cross all of them at once
("is there anything on my list I could do before my next event").

| | Where it comes from | Needs setup |
| --- | --- | --- |
| **Calendar** | `CalendarContract` — every Google calendar already syncing to the phone | no |
| **To-dos** | `store.json`, the app's own | no |
| **Notes** | `store.json`, the app's own | no |
| **Location** | GPS | permission |

**Google Calendar needed no work** — it has been read straight from the phone's
provider all along, which covers every account already synced there and needs
no OAuth. What it did need was three fixes:

- **Hidden calendars were being read.** The query passed no selection, so
  calendars the user had switched off — holidays in another country, two
  Classroom feeds, a dead college account — went into the prompt and crowded
  out the events that mattered. `VISIBLE=1` now.
- **Location was dropped.** Events carry `EVENT_LOCATION`; it is appended as
  `title @ where`.
- **A day-specific question got a rolling window, and answered dangerously.**
  "Am I free on Thursday" was answered from a list starting *now*, which filled
  its cap with the next four days of events long before Thursday appeared — so
  a Thursday with eight events on it was reported as **free**. A question that
  names a day now gets that day's own window, and a truncated list says so in
  the text, because the model cannot otherwise tell a short list from an empty
  calendar and guesses "free" for both.

**Notes** are new (`Notes.java`) and the app owns them. Neither Google Keep nor
Samsung Notes exposes a content provider a third-party app may read, so a note
filed into one of those could never be read back — and read-back is the whole
point. Dictate with "make a note that…", "note that…"; ask with "what are my
notes", "any notes about the landlord"; delete with "forget the note about the
boiler". "note to self" stays a *to-do*: people say it when they mean an action.

Delete matters more than it looks: a note that turns out to be wrong is worse
than no note, because the assistant keeps stating it as fact in every answer.
A delete with nothing to match on ("delete my notes") is refused rather than
taking the lot.

**To-dos stay local**, by choice — Google Tasks has no content provider, so the
only route would be OAuth and a REST client, and the local list already answers
questions and reaches the glasses through the Jarvis calendar. Nothing stops
that changing later; the store is one JSON file.

**One trade-off worth knowing.** Reminders reach the glasses' dashboard through
RayNeo's schedule sync (see `Agenda.java`). Turning RayNeo's calendar
forwarding off stops assistant reminders appearing there too — they still fire
their own notification, and the assistant can still answer questions about
them, but the persistent card goes away.

## Reminder phrasings that used to vanish

Reminders were not just failing to *surface* — a whole family of them was never
being set at all. The utterance matched no timer pattern, fell through to the
to-do list, and became a silent note with no time attached. Fixed, with the
offline suite (`android/verify/commands.py`) going 196/240 → 235/253 and no new
false positives:

- **The unit dropped.** "remind me to stretch in 45", "set a timer for 10" —
  `DUR` required `<number> <unit>`, so these parsed as no duration. A bare
  number after in/within/for now reads as minutes, but only where a delay can
  actually sit: it must end the utterance or be followed by the task ("in five
  **to** check the laundry"). Otherwise "grab a table for six at Antonio's"
  books a six-minute timer.
- **Spoken durations.** "an hour", "half an hour", "an hour and a half", "a
  quarter hour" — no digit, so no match. `words()` normalises them first.
  "a second" is deliberately absent: "in a second" means soon.
- **Openers the VAD ate.** The glasses clip the start of short commands, so
  "remember to…" arrives as "member to…" and "remind me in…" as "me in…".
  Both are recognised now, as is a leading bare "to …".
- **A lone duration.** "45 seconds", "in 20 minutes" on their own can only be a
  timer; there is nothing else they could be.
- **Self-correction.** "remind me in half an hour — actually, make it 5
  o'clock". The later time wins, and it tells a clock from a duration:
  "make it 15" is fifteen minutes, "make it 5 o'clock" is an hour.
- **Untimeable reminders no longer vanish.** "remind me the wifi password is
  guest 2024" has no time in it, so it is kept as a to-do rather than dropped.
  Questions are excluded — "remind me why we switched plans" is asking, not
  filing.
- **A question about a timer is not a timer.** "What time should I set the
  timer for if it needs 3 hours" was booking a three-hour countdown.

Labels are cleaned to match: the delay is stripped as a *phrase*, so
"pick up 2 pizzas in 20 minutes" keeps its 2, and a spoken hour is removed so a
card reads "pick up the record at 4:00 PM" rather than "…at four at 4:00 PM".

**Still not handled, on purpose.** A bare imperative with a clock and no
reminder word — "feed the cats at 6" — stays unmatched. That is the same class
that produced phantom reminders from "at 3rd and main" and "at 20 percent", and
guessing there is worse than not guessing.

## Answers longer than one card

An answer used to stop at the first card — not because it was clipped, but
because the model had been told to stop. The system prompt read "Reply in ONE
short sentence, under 90 characters", so a question about an opera on tonight's
calendar returned the name and the venue and nothing else.

Length is now a budget rather than a rule: one short sentence for a fact, a
number, a time or a yes/no, and up to three sentences when the question is
about an event, place, person or topic where the detail *is* the answer.
`Cards.postSequence` deals that out over consecutive cards.

- **Paced, not batched.** The lens shows one card and a new one replaces it, so
  pages go out nine seconds apart. Fired together they would flash past and
  only the last would be read.
- **Balanced and broken where a reader pauses.** Filling page one greedily
  split "…tickets are" / "required and it's a full-length performance" and left
  a nearly empty second card. Pages are now evened out and broken at a sentence
  end, then a semicolon or comma, then a space.
- **Numbered** `(1/2)`, because a continuation otherwise reads as a fresh and
  oddly abrupt answer.
- **Every page uses both lines.** A short continuation would not trigger the
  title spill and reverted to a bare "Jarvis" header, throwing away the line the
  spill exists to reclaim.
- **A new question abandons a sequence in flight** — answering the previous
  question over the top of the current one is worse than dropping the tail.

**The licence came with a limit attached.** The extra room was immediately
filled with a fabrication: "bring a coat as it's outdoors", about an indoor
theatre. The instruction now forbids inventing specifics — prices, addresses,
times, weather, advice about a venue it was not told about — and says plainly
that saying less beats filling the space. Worth remembering that this is a
standing risk of the change, not a bug that was fixed once.

Related: the "$" glyph fired on the *word* "price", so an answer about opera
tickets was decorated as though the markets had moved. Money glyphs now need an
actual amount alongside.

## "What's playing" should mean a change, not a poll

A card naming what is playing is only welcome when something actually
*switched*. Watching one YouTube video it was arriving every minute or so.
Three separate causes, all of which look identical from the lens:

- **A failed read was treated as a stop.** Media state is read over the adb
  socket with `dumpsys media_session`; when that read failed or came back
  empty, the code cleared its memory of the current track, so the very same
  video looked new on the next poll. A read that fails now changes nothing.
- **A pause was treated as a stop.** Pausing cleared the memory too, so
  resuming the same video announced it again - and an ad break or a buffering
  stall reads exactly like a pause. Only a *sustained* stop (five minutes)
  retires the track, so picking something up hours later still counts as news.
- **A title that flaps away and back announced twice.** A YouTube ad swaps the
  session metadata to the advert and back, which is two changes; the same
  happens when a paused video and a paused podcast trade places in the dump.
  A track announced in the last ten minutes is not announced again.

Media is also polled at most every 15 seconds, decoupled from the five-second
heartbeat the calendar mirror wants: `dumpsys media_session` over the adb
socket is far too expensive to run that often, and every extra read is another
chance to catch a blip.

Unrelated but found while reading that code: the track key's separator was a
literal NUL byte embedded in the Java source, which made `Media.java` read as a
binary file to grep and every other text tool. It is a unit separator now.

## To-dos that stay on the glasses until they are done

Open to-dos sit on the glasses' schedule card, ticked (`✓ buy oat milk`), and
come off when you say they are done - "mark off the library books" clears the
card within seconds.

**All-day entries, and the surface forces that.** Measured: an *ongoing* event
and an *all-day* event both reach the glasses; a *finished* one is never
forwarded at all. A to-do given a due time would therefore vanish the instant
it went overdue - the exact disappearance this exists to prevent. An all-day
entry is carried, and is re-dated each day it stays open.

- **Three at a time, oldest first.** The card holds ten and the day has to fit
  beside them. Oldest-first matters: taking the newest would push a to-do
  already on the glasses off when a newer one arrived, reintroducing the same
  disappearance. A slot is freed by completing something, not by time passing.
- **No alarm.** A standing presence is not a nag; that is what the timed
  reminders in `Agenda` are for.
- **They never age out.** Deliberate, and it has a cost: three items read fifty
  times become wallpaper and stop registering. The trade was made knowingly -
  nothing you asked to be reminded of should ever quietly disappear.
- Adding or completing one nudges the mirror immediately, so neither waits for
  the five-minute pass.

## The assistant never claims to have acted

A command the parser misses does not fail quietly - it falls through to the
LLM, which answers it as though it were a request. "Complete buy oat milk"
matched no pattern, so the model replied **"Buy oat milk completed."** and
nothing had been completed. That is worse than an error: you believe it is
handled and stop thinking about it.

The model is now told it cannot perform actions and must never say it has done,
added, set, completed, played, sent or changed anything - if asked to act, it
says plainly that it could not. Measured after the change: "text Dave that I am
running late" gets *"I could not text Dave."*, and "turn off the kitchen
lights" gets *"I could not do that."*

This is a general guard, not a fix for one phrasing. Every command this
assistant does not recognise lands in the same place.

## Finishing with something

"Mark off X" works on anything showing on the glasses, not only on to-dos, and
what removal *means* depends on what it is:

| | what happens |
| --- | --- |
| **to-do** | marked done in the store; its all-day entry goes |
| **reminder** (ours) | the event is deleted outright |
| **mirrored event** | only the glasses' copy goes - **the real calendar event is left exactly where it is**, and the source is recorded as dismissed so the next pass does not put it straight back |

That last row is the one that matters. A mirrored entry is a copy of something
in a real calendar; deleting the original to tidy a display would be destroying
data to fix a rendering problem. The dismissal is remembered only until the
event would have left the card anyway, so the record cannot grow without bound.

"Complete X", "finish X", "done with X", "cross off X", "tick off X" and
"X is done" all work, as well as "mark off X". The pattern used to demand the
word "todo" or "task" in the middle of "complete ... ", so the plainest way to
say it matched nothing at all. Trailing punctuation is stripped too - the ASR
ends utterances with a full stop, and "buy oat milk." matches no to-do called
"buy oat milk".

Matching is word-based: every content word spoken must appear in the title.
Comparing whole normalised strings was useless in practice - "mark off the
Union Democracy panel" has to match "Union Democracy & Fighting Business
Unionism & Concession Contracts: A WorkWeek Panel", and neither string contains
the other.

**Removal is immediate in both directions.** Completing something nudges the
mirror straight away, and the card also wakes exactly when its soonest entry
stops qualifying - a timed event at its start, an all-day one at the end of its
day. Previously that was only noticed on the five-minute pass, so an event that
had already begun could sit on the glasses for another five minutes.

**No undo.** Dismissing is currently one-way until the entry expires by itself.

## The dashboard mirror (`Mirror.java`)

Pointing RayNeo's dashboard at this app's calendar alone makes the schedule card
queryable-by-proxy but empty — the only things in that calendar are the
reminders you asked for. So the mirror copies the next few **real** events
across. The assistant still answers from the real calendar; the copy exists
only to give the lens something worth a glance.

- **Upcoming only.** An event already under way is not a heads-up — you are at
  it. Timed events are taken only while their start is still ahead.
- **All-day events stay for the day.** "Today is Labor Day" has no start time
  to be past and is useful all day.
- **Visible calendars only**, and never this app's own — mirroring the mirror
  would compound every pass.
- **Ten on the card at once, reminders first.** Ten is the whole card's budget,
  not the mirror's: reminders are things you explicitly asked to be told, so
  they keep their place and the mirror takes what is left.
- **Location goes in its own column**, never appended to the title. The lens
  shows about 30 characters of title, and these feeds carry multi-line street
  addresses and Zoom passcodes — folding those into the title erased it.
- Copies are marked in their description (which does not cross the link, so it
  is invisible on the lens). A pass only writes deltas, so an unchanged
  schedule does not churn the Bluetooth link, and deletes go through the
  sync-adapter URI or the rows would only be tombstoned and pile up.

### How the glasses actually render the schedule (measured, not assumed)

Everything below was read off the lens and out of the RayNeo->glasses payload,
because none of it is documented and several early guesses were wrong.

- **Three per page, three pages, nine total.** The schedule widget shows three
  entries to a crown-rotatable page and stops at three pages. Verified: fourteen
  events written, ten forwarded by RayNeo, **nine drawn** - the rest never
  appear. So the budget is nine, filled by the curation order (reminders, then
  to-dos, then events soonest-first).
- **Pagination is decided by date, per event.** Every event crosses the link
  tagged `isTomorrowSchedule`, which RayNeo computes from the date. **Today's
  events paginate; a future day renders as a fixed three-item preview** and does
  not paginate. Proven both ways minutes apart on the lens - six *today* events
  gave two pages, nine *tomorrow* events gave one page of three. Nothing decays
  over time; what looks like "it truncated later" is today's events passing as
  the day ends.

### Seeding future events onto today so they paginate (`Mirror.shownFor`)

Because only *today* paginates, a future day would otherwise show three items on
one page however many are sent. The fix, which the whole `shownFor` method
exists for: **copy a future event onto today so RayNeo paginates it, and carry
the real day and time in the card text.**

- **All-day, dated to LOCAL today, in cal 59, with `eventTimezone=UTC`.** Every
  one of those qualifiers was learned the hard way:
  - It must be in **cal 59** - the user pointed RayNeo's dashboard at the
    iohelper calendar alone, so events in any other calendar are simply not
    read (a whole test round forwarded nothing for this reason).
  - It needs **`eventTimezone=UTC`** or the all-day event is malformed and
    dropped (another wasted round).
  - **All-day** beats a timed seed: measured on the lens, an all-day entry gives
    the **title two lines** and shows a truthful **"All day"** where a timed
    entry would burn a line on a meaningless clock time (`11:00 PM`, or worse
    `23:00~23:01`). Timed paginates too, but all-day reads far better.
  - Dated to **local today** so RayNeo tags it `isTomorrowSchedule=false`. A
    timed seed proved today paginates; the all-day-in-cal-59 test proved all-day
    does the same and RayNeo tags local-today all-day as today.
- **The real day/time goes on the LOCATION line, not the title.** Prefixing the
  title ("Mon 12:00 Richmond: ...") ate the front of a long name and truncated
  away the one thing that says WHAT the event is. The title is left whole; the
  location line leads with the day/time.
- **A redundant city prefix is lifted off the title onto the location.**
  "Richmond: Workers Over Billionaires! ... Rally" becomes title "Workers Over
  Billionaires! ... Rally" with the city "Richmond" on the location line (below
  the day/time for a seeded event, alone for a today event) - freeing the title
  for the event itself, and replacing a long venue with the short city. A prefix
  is treated as a city only when it appears in the venue text OR is in a bounded
  known-cities list (so "San Francisco:" strips even though its federal-building
  address never says the city, while "Seminar 231, Public Finance:" and "SF Mime
  Troupe:" are left alone). This lift (`Mirror.liftCity`) runs for BOTH today's
  timed events and seeded future ones - it used to run only for the seed, so the
  city reverted to the full venue the moment an event rolled into today.

Today's own timed events are shown with their real time line (only the city
prefix is lifted, as above), so a day in progress reads natively; only later days
are seeded. The seed rolls
onto the new today each morning (the reconcile matches by shown date + title, so
a copy left on yesterday is refreshed). One edge: between 23:00 and midnight a
seed dated earlier today could read as past; rare, and it self-corrects after
midnight.

The spoken answer ("what's on my calendar tomorrow") remains the way to hear a
whole future day in one go, seeding or not.
- **Change-driven.** A ContentObserver on the calendar provider wakes the
  mirror, debounced three seconds because one edit fires a burst of
  notifications. Without it the mirror only woke every five minutes, so an
  event you had *just* created was missing from the glasses for up to five
  minutes - precisely when you look, and precisely the moment the mirror is
  meant to earn its keep. The heartbeat behind it is five seconds, not twenty,
  for the same reason.
- **Never shows the same thing twice.** Asking Jarvis to remind you *and*
  putting it in your calendar is a normal thing to do, and it was putting the
  same errand on the lens twice - once from the reminder, once from the mirror.
  A real event within half an hour of a reminder with a matching title is
  skipped; the reminder wins, because it is the one you asked for by name and
  the one carrying an alarm.

## Reminders that survive being missed (`Agenda.java`)

A reminder used to be one transient card; miss it and it was gone, which is
what happened on the first outdoor test. Any reminder with a clock time, or
more than ten minutes out, is now also written into a private LOCAL calendar
named **Jarvis** that the app creates on first use. That buys two more
deliveries: the glasses' dashboard shows it as a persistent schedule card, and
the phone's calendar raises its own notification at the due time, which the
relay forwards like any other. Short kitchen timers stay in-app.

The calendar is ordinary — hide or delete it in any calendar app and every
reminder goes with it — it never syncs anywhere, and events are pruned a week
after they end. A failure to write it is deliberately silent: the in-app card
still goes out, and a reminder that half-works is worse than one that quietly
works once.

## Installing the phone app

One APK, `android/build/iohelper-card.apk` (~1.1 MB), self-contained: Conscrypt,
the ADB client and SPAKE2 are all inside it. No Termux, no Shizuku, no companion
app. Build it with `python android/build.py` — no Gradle.

1. **Sideload the APK** and open it once.
2. **Allow the permissions it asks for** on first launch — notifications,
   location, calendar. Notifications are not optional: cards *are* notifications,
   and a denial means nothing ever reaches the lens while everything still
   reports success. (`READ_LOGS` is in the manifest but is not needed and cannot
   be granted from the phone; the assistant reads logcat through adbd as shell,
   because `logd` filters the buffer by UID.)
3. **Turn on Developer options → Wireless debugging.**
4. **Pair, in the app.** Open *Pair device with pairing code*, leave that dialog
   on screen, type its six digits into the app's "Pairing code" box and tap
   **Pair with code**. The app finds the one-shot pairing port over mDNS itself.
   Both the code and the port change every time the dialog opens, so if it says
   no pairing service was advertised, the dialog had closed.
5. **Paste the keys** (Groq is the only required one; SerpApi for live data,
   Spotify id/secret for music). Sonos needs nothing — it is discovered on the
   LAN.
6. **Allow the app to post notifications to the glasses** in RayNeo's own app.
   Cards reach the lens as notifications; if this app is not on that list,
   nothing ever appears and everything else still reports success.

Step 4 is what removes the computer. The app reads the glasses' transcripts by
talking to the phone's **own** adbd — `logd` filters the buffer by UID, so an
ordinary app sees ~51 lines where adb sees 4154 — and adbd only talks to a key
it already trusts. Before pairing worked, the only way to get this app's key
into `/data/misc/adb/adb_keys` was the "Allow debugging?" prompt on a USB cable.
Pairing puts it there over Wi-Fi instead, once, and it survives reboots.

## Running it away from Wi-Fi

**Turn on "Allow LAN access" in Tailscale before going out.** Without it the
phone's Tailscale connection is unusable on cell data, and the assistant loses
the adb link it reads transcripts through — so it goes quiet with no obvious
cause. This is a per-device Tailscale setting, not something the app can set for
you, and it is the first thing to check if the glasses stop answering outdoors.

The link itself does not depend on a computer. Wireless debugging survives a
reboot where `adb tcpip 5555` does not, and adbd checks a TLS client certificate
against the same key store both pairing and the "Allow debugging?" tap write to.
Three details matter if you touch that code:

- the connect port is **not** a TLS port; adbd speaks plain ADB there and
  upgrades only after an `STLS` exchange;
- adbd sends its `CNXN` banner **unprompted** once the handshake completes, so
  sending a second one makes it drop the transport;
- the bundled SPAKE2 sat behind a self-test gate for a long time because it
  derived the wrong key in silence. The fault was two 19x/38x scalings in the
  vendored ed25519 field arithmetic being evaluated in 32-bit int, which
  overflowed on limb values the group law routinely produces — so scalar
  multiplication returned a wrong point and every layer above it still reported
  success. `python android/verify/verify.py` now checks the field against
  BigInteger on out-of-bound limbs, scalar multiplication against RFC 8032, and
  the M/N points against the seeds they are published as.

## Location

The assistant uses the phone's GPS for weather, routes and anything local — the
city typed into settings is only a fallback, because on glasses it is wrong as
soon as you walk away. Location is read from the newest fix the platform already
has and never blocks on acquiring a new one, so an answer is never held up
waiting for a satellite.

Denying the permission is safe: the foreground-service location type is added
only when the permission is actually granted, so the assistant still starts and
simply falls back to the configured city.

## Playing an artist on Sonos (`Media.artistPlaylist`)

"Play Men Without Hats on Sonos" should fill the queue with that band's music.
Getting there ran into two separate Spotify walls, both measured on this app's
tokens (a client-credentials token and a user OAuth token alike):

- **Every id-based catalogue endpoint answers 403.** `/artists/{id}/top-tracks`,
  `/artists/{id}/albums` and `/v1/albums?ids=` are all refused, so the artist's
  discography cannot be read directly. `/search` is the only endpoint that
  answers, and it caps `limit` at 10 (20 or 50 → HTTP 400).
- **A track search of a hit-heavy artist collapses to almost nothing.** Spotify
  presses one hit into a remaster, a live cut, a radio edit and a dozen reissues;
  a field search for `artist:"Men Without Hats"` returns ten rows that are two
  actual songs ("The Safety Dance" ×several + "Pop Goes The World"). De-duplicating
  by song (see `songKey`) is correct — it just leaves two, which is why the queue
  used to hold two.

The fix is to play a **playlist**, which Sonos streams by URI on its *own*
Spotify account — so our token only has to *find* the playlist, never read its
contents. `artistPlaylist` searches `This Is <artist>` and prefers, in order:

1. Spotify's own editorial **"This Is &lt;artist&gt;"** playlist. This is the
   ideal, but Spotify **nulls its editorial playlists out of search results for
   an app in development mode** — measured: a 10-hit response came back with 6
   `null` entries and not one Spotify-owned row. So it is rarely reachable.
2. The **artist's own official playlist** — for this band, "The Complete Men
   Without Hats", owned by the account *Men Without Hats* itself. A verified,
   career-spanning set, and a far fuller queue than two songs. This is what
   actually plays.
3. Failing both, the caller falls back to one of the artist's **albums** (search
   *can* find those), then finally to the two-song track search.

A stranger's same-named playlist is refused throughout: it is a guess at the
catalogue, not the artist's music. To reach the editorial "This Is" playlists
directly, the Spotify app would need to be taken out of development mode (an
extended-quota review in the Spotify developer dashboard).

## Resolving a described request, not just a named one (`Commands.resolveDescriptive`)

"Play the first album by The Cure" used to fail: the parser handed Spotify the
literal phrase (mangled to "first by the cure"), search found nothing, and the
card said "No album found." Spotify cannot answer "the *first* album" — that is
a fact about the world, and the assistant already has something that knows facts
about the world: the language model. So a descriptive play request is resolved
by the model to a concrete artist + title, and only then played.

The seam that keeps this honest is **verification**. The model is never trusted
to have played anything — it only NAMES a release; the app then confirms that
name exists on Spotify (`Media.findAlbum` / `findTrack`, matching both title and
credited artist via `/search`) before a single note plays, and the card always
shows the **real Spotify title that started**, not the user's words. So a wrong
guess is visible and correctable, never a silent substitution.

- **When the model is consulted.** Only for genuinely descriptive phrasings, so
  literal requests keep today's fast path with zero LLM calls. `isDescriptive`
  fires on strong markers (debut, latest/newest, greatest hits/best of,
  "song from <movie>", "the album with X", "the one where…", "cover of") and on
  ordinals (first/second/…/last) *only* next to a music noun or an explicit
  album/playlist request — so "The Final Countdown" and "Last Christmas" are not
  mistaken for descriptions. Measured: "play disintegration by the cure" and
  "play men without hats" never reach the model.
- **The resolver call.** `Llm.resolveMusic` uses its own system prompt (NOT
  `BRIEF`, which forbids naming specifics) and JSON response mode, returns
  `{artist, title, kind, confident}`, tolerates fenced/prose replies, and never
  throws — an unreachable backend just degrades to the literal path.
- **Honesty branches.** Not confident → "Did you mean X by Y? Say it by name to
  play it." Named but not on Spotify → "I think that's X by Y, but I couldn't
  find it to play." Nothing is ever played that was not verified.

Measured on device (Groq `gpt-oss-120b` backend):

| Said | Played |
| --- | --- |
| play the first album by the cure on sonos | ▶ Three Imaginary Boys — The Cure |
| play the album with just like heaven on sonos | ▶ Kiss Me, Kiss Me, Kiss Me — The Cure |
| play disintegration by the cure on sonos *(literal, no LLM)* | ▶ Disintegration — The Cure |

**Time-sensitive limit.** "Latest/newest album" is only as current as the
model's training cutoff — "the cure latest album" resolved to *4:13 Dream*
(2008), not *Songs of a Lost World* (2024). The app cannot fix this on device:
the id-based catalogue endpoints that would list an artist's releases by date
all 403 for this token (see above), so there is no way to sort by release date.
The behavior stays honest — it announces the real title it found, never "the
latest" — but for a very recent release, name it directly.

## Playing a genre or mood, not just a title (`Commands.resolveCurated`)

"Play the top 10 new wave songs on Sonos" used to play a rap track — the literal
phrase went to a track search, which matched some song that merely contained the
words "new wave." A request for a *kind* of music (a genre, era, mood, or a
"top N" / "best of" list) is neither an artist, an album, nor a single track, so
`isCurated` recognises it — strong markers (top N, best of, "80s", genre and
mood words asked for as a set) that don't fire on a specific name — and
`resolveCurated` builds the actual set.

**The exact set (primary).** The model names real, well-known songs for the
request (`Llm.resolveList`), each is VERIFIED on Spotify by title *and* credited
artist (`Media.verifyQueue` → `findTrack`), and the verified tracks are queued —
`Sonos.playTracks` on the speaker, the Web-API queue on the phone. The model can
name anything, but only real, correctly-credited recordings ever play, so "top
10 new wave songs" queues ten genuine new-wave songs by different artists.

**The fallback.** When the model is unavailable, or returns an empty list
(its signal that the phrase actually named a specific artist), `resolveCurated`
reduces the phrase to a theme ("top 10 new wave songs" → "new wave") and plays a
matching **playlist** container, then a compilation album, then an honest
"couldn't find." Editorial playlists are nulled for this token, so a playlist
fallback lands on a user playlist — fine for a genre, unlike for a specific
artist.

Two things this cost to get right, both measured on the lens:

- **Queuing individual Spotify tracks is state-sensitive, not impossible.** An
  early probe saw `AddURIToQueue` answer UPnP error **800** for every track URI
  variant — but that was a transient degraded speaker state that was *also*
  rejecting container enqueues at the time; once the speaker recovered (a single
  `SetAVTransportURI` play resets it), a track queue builds fine. So the code
  queues the real tracks and, if the speaker refuses them, the honest failure
  stands rather than a wrong track.
- **Enqueue needs a longer timeout than a query.** Resolving and enqueuing tracks
  (or a large playlist) takes the speaker well past the 4-second SOAP timeout
  used for quick queries, so the enqueue path uses a **15-second** budget
  (`ENQUEUE_TIMEOUT_MS`); without it, enqueues timed out as "Can't reach Bedroom
  Sonos."

(An earlier version played only a genre playlist here, on the wrong conclusion
that the 800 was a hard limit and that building our own playlist was blocked —
the token does 403 on playlist creation, lacking `playlist-modify` scope, but
that turned out not to matter once the direct track queue worked.)

## Live Google Maps turn-by-turn on the lens (`NavListener`)

The directions *summary* above ("28 min walk via Mountain Blvd") is a one-line
answer. For an actual walk, `NavListener` relays Google Maps' **live** turn-by-turn
onto the glasses, step by step — Maps' own routing, not a guess. This is the real
cure for the invented-directions bug: instead of the model imagining a street
sequence, the card shows Maps' exact instruction.

**Starting a trip by voice** (`Commands` `nav.start` → `Media.navigate`). "Navigate
to X", "take me to X", and the bare mode phrasings Google Maps itself accepts —
"walk to X", "drive to X", "bicycle to X", "public transportation to X" (also
"take the bus/train to X", "transit to X") — launch Maps turn-by-turn; the
**leading verb picks the mode**, so a mode word inside the place name never
confuses it ("drive to Train Street" drives). A bare "navigate to X" defaults to
driving (Maps' own default). This is distinct from the informational "how
long/how far to X", which stays a one-line summary. The launch pins Google Maps'
`MapsActivity` explicitly (`am start -n …`) because `google.navigation:` also
resolves to Waze/Uber/a browser and a bare VIEW would pop an app-chooser on the
lens; an explicit component never does. One measured wrinkle folded into the
dedup: as you *near* a turn Maps prepends a shrinking distance to the maneuver
title ("500 ft · Turn left onto X", then 400, 300…), so `NavListener` strips a
leading distance token before the dedup compare — otherwise every tick would be a
new card, and a frozen "500 ft" would linger once you were at the turn.

`NavListener` is a `NotificationListenerService`. Google Maps posts one ongoing
navigation notification (`category=navigation`) and updates it every second or
two as the distance and ETA tick; the maneuver text (`android.title`, e.g. "Head
toward Mountain Blvd") only changes at each turn, and the ETA lives in
`android.subText` ("Arrive 12:06 PM"). The listener relays a card **only when the
maneuver title changes** — so the per-second updates collapse to exactly one card
per turn, no spam. The card is `Jarvis / <details> / <glyph> <maneuver>`, verbatim
Maps text.

The `<glyph>` is chosen from the maneuver so a turn *looks* like a turn on the
lens — `←`/`→` for turn left/right, `↖`/`↗` for slight/keep, `↩` U-turn, `↻`
roundabout, `↑` continue/head, `⌖` arrive (plain BMP arrows the lens font
renders). It reads the direction from the instruction HEAD (before "onto/toward
<street>"), so a street name that contains "left"/"right" — "Turn right onto Left
St", "Wright Ave" — can't flip the arrow.

The `<details>` line carries the ETA (`android.subText`) and, for **transit**,
the departure/arrival and the line/vehicle that Maps puts in `android.text` /
`android.bigText` ("Arrive 2:30 PM · Bus 51A") — merged onto the one existing
card, not a second popup. Because a transit step may not carry
`category=navigation`, the filter also accepts Maps notifications grouped under
`navigation_status_notification_group` (still nav-specific). Driving and walking
leave `text` null, so their cards are unchanged apart from the glyph. Transit
formatting is best-effort — confirm it against a real transit trip.

Non-obvious bits, all measured on the device:

- **It has to be granted, once.** Notification access is a runtime grant, not a
  manifest permission. On a dev device: `adb shell cmd notification allow_listener
  com.iohelper.card/com.iohelper.card.NavListener` (it *adds* to the list — RayNeo's
  own listeners stay); a normal user does it in Settings → Notification access. The
  grant persists across reboots and reinstalls.
- **Reinstall drops the binding.** Android unbinds a listener when its app is
  updated and doesn't restore it until reboot on many builds — and `build.py`
  reinstalls constantly. `BootReceiver` calls `NotificationListenerService
  .requestRebind(...)` on `MY_PACKAGE_REPLACED` (and `BOOT_COMPLETED`), before the
  WANTED check, so the relay resumes on its own. Verified: reinstall → rebind →
  re-relay of the in-progress maneuver, with no re-grant.
- **No feedback loop.** The listener sees *every* app's notifications, including
  iohelper's own posted cards. The first check in `relay()` is the package
  (`com.google.android.apps.maps`), so iohelper's own card is dropped before
  anything runs — it can never relay itself.
- **RayNeo may also mirror it.** The RayNeo companion apps are notification
  listeners too and may relay Maps' own (ticking, Maps-labelled) notification. The
  iohelper card is the independent, deduped, app-controlled one; set
  `nav.relay=false` (or turn Maps off in the RayNeo companion) if the two are
  redundant. Default on.
- **The ETA freezes within a maneuver.** Because the ETA is deliberately kept out
  of the dedup key, the displayed arrival time can drift a minute or two until the
  next turn — the accepted price of not re-posting on every tick. On arrival, a
  neutral "Navigation ended" replaces the last card (never a fabricated "Arrived"),
  debounced ~2 s so a reroute's notification swap doesn't flash it.

## What it will not do

**Song lyrics.** The display holds about 115 characters — roughly one line — so
lyrics cannot be shown in any useful form, and reproducing them is a licensing
problem rather than a technical one. Ask for the song, artist or album instead;
those fit and are answerable.
