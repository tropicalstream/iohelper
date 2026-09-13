# Setup guide, with time estimates

For someone setting this up for the first time, on their own phone and
glasses. Times assume no prior familiarity with adb, Android sideloading, or
this specific project — add time back if any of that is already second
nature, and see *If something doesn't work* at the end, because one step
below is the one that trips up almost everyone.

**Total: 30–60 minutes** for a clean run, **up to 90** if you hit the one
common snag (covered below) or need to create developer accounts you don't
already have.

## Before you start

- **RayNeo iO glasses, already paired to a phone through RayNeo's own app**
  (`com.rayneo.venus.pub`). This guide does not pair the glasses themselves —
  that's RayNeo's own app's job, and it must be done first. **iO glasses
  specifically** — the X3 app (`com.rayneo.mercury`) is a different protocol
  and this will not work with it.
- **An Android phone**, Android 8.0+ (the app targets 34; wireless debugging
  itself needs Android 11+, which is effectively every phone still sold).
- A **Groq account** (free) is the one required piece of external setup — get
  the key during Step 5, or in advance if you'd rather not context-switch
  mid-setup.

| Step | Time | Notes |
|---|---:|---|
| 1. Get the APK | 2–20 min | prebuilt is instant; building from source varies a lot |
| 2. Sideload and open it | 3–5 min | first-run permission prompts |
| 3. Grant permissions | 2 min | notifications, location, calendar |
| 4. Turn on Wireless debugging | 3–5 min | if Developer options aren't already on |
| 5. Pair the app to adb | 3–10 min | the one step most likely to need a retry |
| 6. Add your Groq (or OpenAI) key | 5–10 min | new account signup adds most of this |
| 7. Optional: SerpApi, Spotify | 0–15 min | skip for now if you just want it working |
| 8. Allow the app in RayNeo's notification list | 2 min | easy to miss entirely |
| 9. Test with the crown | 2–5 min | first real answer |

---

## Step 1 — Get the APK (2–20 min)

Two paths. Pick one.

**Fastest: the prebuilt APK in the repo (`iohelper.apk`, ~1.1 MB).**
Download it to the phone (~2 min). One caveat worth knowing: a prebuilt APK
committed to a repo is a snapshot — it will not include fixes made after it
was last rebuilt, so if something described elsewhere in this project's docs
doesn't match what you see, that's the likely reason. Rebuilding (below)
always gets you the current code.

**Building from source**, if you want the latest fixes or plan to change
anything:

- If Android Studio is **already installed** (which bundles the SDK and a
  JDK): clone the repo, run `python android/build.py`, done. **~5 min.**
- If **nothing is installed**: installing Android Studio is the long pole
  here — it's a large download and the installer sets up the SDK on first
  launch. Budget **15–20 min** on a decent connection, more on a slow one.
  Once it's there, the build itself takes seconds; there's no Gradle step.

```bash
python android/build.py                    # build, sign
python android/build.py --install          # ...and install straight to a connected phone
```

## Step 2 — Sideload and open it once (3–5 min)

Transfer the APK to the phone if you built it on a computer, tap it, and
allow "install from this source" when Android asks (a one-time toggle per
source app — Files, Chrome, whatever you used).

**On Android 13+, Samsung specifically hides a toggle you'll need in Step 3**
behind **Settings → Apps → Jarvis → ⋮ (top right) → Allow restricted
settings.** It has no visible effect yet — just note where it is, since
without it a later permission will silently fail to stick.

Open the app once so it can request permissions.

## Step 3 — Grant permissions (2 min)

Allow **notifications**, **location**, and **calendar** when asked.

**Notifications are not optional.** The cards that appear on the glasses
*are* notifications under the hood — deny this one and the app will still
report success at every step while nothing ever reaches the lens. This is
the single most common source of "it says it worked but I see nothing."

Location and calendar are genuinely optional — deny them and the assistant
falls back to a configured city and skips calendar answers, without
breaking anything else.

## Step 4 — Turn on Wireless debugging (3–5 min)

This is what lets the app read the glasses' transcripts with no computer
involved, ever again, after this one-time setup.

1. **Settings → About phone → tap Build number 7 times** to unlock Developer
   options, if it isn't already unlocked.
2. **Settings → Developer options → Wireless debugging → on.**

If Developer options were already on, skip straight to turning the toggle
on — **under a minute.**

## Step 5 — Pair the app to adb (3–10 min, the step most likely to need a retry)

1. In **Developer options → Wireless debugging**, tap **Pair device with
   pairing code**. A dialog appears with a six-digit code and stays open.
2. **Without closing that dialog**, switch to the iohelper app, open its
   pairing screen, type the six digits into the **Pairing code** box, and
   tap **Pair with code**. The app finds the pairing port itself over mDNS —
   there's no port number to type in.

**The timing is the whole trick here.** Both the code and the port are
regenerated every time the dialog is reopened, so if iohelper says no
pairing service was found, the dialog you're looking at has already closed
or was closed and reopened after you copied the code. Reopen it fresh, and
enter the new code immediately. Most first attempts succeed; budget one
retry as normal, not a sign anything is actually wrong.

Once paired, this survives reboots — it's a one-time step, not something
you'll repeat on ordinary use.

## Step 6 — Add a model key (5–10 min)

**Groq is the only required key**, and it's free:

1. Go to `console.groq.com/keys`, sign in or create an account (**2–5 min**
   if you're new to it), generate a key.
2. Paste it into iohelper's settings screen and set the backend to Groq.

OpenAI (`gpt-5.6-luna`) is the paid alternative — about a tenth of a cent
per question, with tool-calling built in the same way. Use
`platform.openai.com/api-keys` instead if you'd rather pay a small amount
per use than deal with Groq's free-tier limits. If you already have an
OpenAI account with billing set up, this step is **under 2 minutes**; a
brand-new account with billing to add takes closer to the 10-minute end.

## Step 7 — Optional extras (0–15 min, skip freely for now)

None of these block a working setup. Add them later if you want the
features they unlock.

- **SerpApi** (weather, traffic, news, prices, local search): free-tier
  signup at their site, paste the key. **~5 min.**
- **Spotify** (music by voice): requires creating an app in the **Spotify
  Developer dashboard** to get a client ID and secret — this has a bit more
  friction than the others since it's a full developer-app registration, not
  just an API key. **~10 min** if you've never used Spotify's developer
  console before.
- **Sonos**: needs nothing from you — it's discovered automatically on the
  local network the first time it's asked to play something.

## Step 8 — Allow the app in RayNeo's notification list (2 min, easy to skip by accident)

Open **RayNeo's own app**, find its notification-access or notification-relay
settings, and allow iohelper's app to post to the glasses. Skip this and
every earlier step will have worked, the app will report success on every
answer, and *still nothing will appear on the lens* — this permission lives
entirely inside RayNeo's app, not iohelper's, which is exactly why it's easy
to forget.

## Step 9 — Test it (2–5 min)

Put the glasses on, press the **crown**, and ask something simple —
*"what's the weather in \[your city\]"* is a good first test since it
exercises the model, SerpApi (if configured) or the fallback path, and the
display all at once. There's no wake word: the crown press itself is the
trigger.

An answer on the lens within a few seconds means everything above is wired
up correctly.

---

## If something doesn't work

Don't start changing settings — this project ships a single diagnostic
command that tells you which of five things is actually wrong, and it's
faster than guessing:

```bash
adb shell am broadcast -n com.iohelper.card/.ShowReceiver -a com.iohelper.card.DIAG
adb logcat -d | grep -o 'status=[^"]*' | tail -1
```

The three counters it prints (`lines`, `heard`, `fired`) map directly to
where the chain broke — full detail is in the README under *"It says
'listening' but nothing happens."* The single most common cause, by far, is
Step 8 above being missed: everything reports success, and the fix is a
toggle inside RayNeo's own app, not iohelper's.

**A path that skips most of this entirely:** the phone-based voice mode (the
big button at the top of the app) doesn't use the glasses' microphone, adb,
or any of the transcript pipeline in Steps 4–5 — it's the phone's own mic and
speaker, with the same brain, and answers still land on the lens as cards.
If Steps 4 or 5 are the part fighting you, that whole class of problem is
simply absent there, and it's worth trying first to confirm the model and
key setup (Steps 6–8) are correct before troubleshooting the adb link
separately.
