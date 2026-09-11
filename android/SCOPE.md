# Scope: running iohelper entirely on the phone

**Question:** can the Jarvis assistant run on the phone with the PC switched off?

**Answer:** yes — but not the way it looked at first. The obvious design (an app
that tails `logcat` itself) is **blocked by Android**, and I proved that on your
device tonight rather than discovering it halfway through the build. The design
below routes around it.

---

## The blocking finding, and the evidence

`READ_LOGS` is `signature|privileged|development`, so `adb shell pm grant` grants
it and it persists. That part works:

```
android.permission.READ_LOGS: granted=true
```

But the grant is **necessary and not sufficient**. `logd` filters the buffer by
UID for non-privileged apps. I shipped a `PROBE` action inside the real
`com.iohelper.card` app that execs `logcat -d -t 4000` and counts what it can
see, force-stopped it so it started fresh with the permission already granted,
and compared against adb reading the same buffer at the same moment:

| Reader | Lines visible | Glasses ASR lines | RNLink lines |
| --- | --- | --- | --- |
| the app (READ_LOGS granted, fresh process) | **51** | 0 | 0 |
| adb (shell UID) | **4154** | many | many |

The 51 are the app's *own* log lines. So on Android 16 a sideloaded app cannot
read another app's logs, whatever permission it holds. **This also rules out
Termux running the Python directly** — Termux is an ordinary app with its own UID
and hits exactly the same filter.

Only three principals can read the whole buffer: **root**, a **system/privileged**
app on the system partition, or the **shell** UID. The phone isn't rooted and we
can't install to `/system`, which leaves shell.

## The route that is open: the phone talking to its own adbd

`adbd` is already listening on the phone, on all interfaces:

```
service.adb.tcp.port = 5555
init.svc.adbd        = running
```

Anything that connects to `127.0.0.1:5555` and speaks the adb protocol executes
as the **shell** UID — full `logcat`, and `cmd notification post` as a bonus.
That is the same door the PC uses today, just entered from inside the house.

So the phone-resident assistant is the current architecture with one substitution:

```
today   PC ──(adb over Tailscale)──▶ adbd ──▶ logcat / notifications
phone   app ──(adb over loopback)──▶ adbd ──▶ logcat / notifications
```

---

## Design

Six components. Everything above the transport is a direct port of code that is
already working and tested.

| # | Component | Notes | Effort |
| --- | --- | --- | --- |
| 1 | **adb client** | Embed a Java/Kotlin adb implementation (e.g. `dadb`) or ship the `adb` binary in `jniLibs` and exec it. Handles RSA key auth + `shell:` streams. **The only genuinely new engineering.** | 2–4 d |
| 2 | **Transcript reader** | Long-lived `shell:logcat` stream, parse `onAlwaysOnResponse` / `phone_asr_text`. Port of `transcripts.py` (76 lines). | 0.5 d |
| 3 | **Wake gate** | Straight port of `wake.py` (140 lines) — strict match, trailing wake word, soft-arm, dedup. Pure logic, no I/O; the 40-odd tests port with it. | 1 d |
| 4 | **Backends + search** | `llm.py` + `search.py` (526 lines) → OkHttp calls. Groq, Gemini, OpenAI-compatible, SerpApi router. | 2–3 d |
| 5 | **Cards + commands** | `display.py`, `glyphs.py`, `commands.py`, `proactive.py` (500 lines). Posting is *native* here — the app already does it, no adb needed. Calendar becomes `READ_CALENDAR`, simpler than the adb content query. | 2 d |
| 6 | **Foreground service + settings UI** | `specialUse` FGS, battery-optimisation exemption, boot receiver, a settings screen replacing the web portal. | 2–3 d |

**Total: roughly 10–15 focused days**, of which ~2–4 is the adb client and the
rest is porting ~1,850 lines of Python that already works.

### What ports cleanly, and what doesn't

Clean: the wake gate, the SerpApi router (including all the hardening — the
"Fully empty" directions fallback, the personal-calendar guard, quota-conscious
routing), the 115-character display limit, glyph composition, the RayNeo-answer
deferral, timers/to-dos.

**Lost: the Claude OAuth backend.** It shells out to the Claude Code CLI, which
cannot run on Android. Groq and Gemini work fine with API keys — and Groq is what
you're actually using — but if you want Claude on the phone it needs an Anthropic
API key, which is a different billing arrangement to your Claude plan.

---

## Risks, honestly

1. **The reboot wart (the real one).** `adb tcpip 5555` does **not** survive a
   reboot — proven tonight. After every restart something must re-arm it, and
   that needs USB or the Developer-options *Wireless debugging* toggle. Wireless
   debugging persists across reboots but uses a **random port** each session, so
   the app would have to discover it (mDNS `_adb-tls-connect._tcp`) and handle
   the pairing flow. **Budget a day for this alone; it is the difference between
   "works" and "works after every reboot without you thinking about it."**
2. **adb authorisation.** The first connection raises the "Allow debugging?"
   dialog. Accepted once with "always allow", it persists — but a factory reset
   or key change repeats it.
3. **Android 16 FGS rules.** `specialUse` needs a declared justification. Fine for
   a sideloaded app; would be a problem for Play Store distribution.
4. **Battery.** A permanent `logcat` stream plus a foreground service is modest
   but not free. Needs the battery-optimisation exemption or Samsung will kill it.
5. **It depends on a debugging channel.** Elegant it is not. If Google tightens
   local adb access in a future release, this breaks.

## The alternative worth weighing

**Termux + `android-tools`**, running the existing Python almost unchanged, with
`ADB=adb` and serial `127.0.0.1:5555`. Same loopback trick, none of the porting —
call it an evening rather than two weeks. The trade is that Termux is a poor host
for an always-on service (Android fights it) and it is a clumsy thing to hand to
anyone else.

**Recommendation:** if the goal is "get the PC out of the loop this week", do
Termux. If the goal is a real phone app you can hand to another iO owner, do the
native build — but treat the adb-loopback transport and the reboot re-arm as the
two spikes to prove first, before porting a single line of the assistant logic.
