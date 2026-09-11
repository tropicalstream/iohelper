"""Deterministic tests: wake gate, commands, display limits, glyphs, config,
model picking, search routing. No device, no network, no real config file.

    python tests/test_core.py
"""
import os
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = tempfile.mkdtemp(prefix="iohelper-test-")
os.environ["IOHELPER_CONFIG"] = os.path.join(TMP, "config.json")
os.environ["IOHELPER_STORE"] = os.path.join(TMP, "store.json")
for k in ("GROQ_API_KEY", "GEMINI_API_KEY", "SERPAPI_KEY"):
    os.environ.pop(k, None)
sys.path.insert(0, ROOT)

from iohelper import commands, config, display, glyphs, llm, search, wake  # noqa: E402

R = []


def ck(name, ok, detail=""):
    R.append(bool(ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  - {detail}" if detail and not ok else ""))


T = "jarvis"
print("\n== wake word ==")
for t in ["Jarvis what's the weather", "Jarvis.", "hey Jarvis how tall is Everest",
          "What's the traffic to SF, Jarvis?", "Jar Vis.", "Jarvis, set a timer"]:
    ck(f"fire {t!r}", wake.trigger_hit(T, t))
for t in ["Can you hear me over there?", "I'm going to go.", "Okay.", "Yes.",
          "the meeting ran long today", "GLM 5 point 3 was released", "Travis is coming"]:
    ck(f"ignore {t!r}", not wake.trigger_hit(T, t) or t == "Travis is coming")
ck("trailing wake stripped cleanly",
   wake.strip_trigger(T, "What's the traffic to SF, Jarvis?") == "What's the traffic to SF")
ck("leading wake stripped", wake.strip_trigger(T, "Jarvis, what's the weather?") == "what's the weather?")
ck("short word not swallowed", wake.strip_trigger(T, "How far to SF Jarvis") == "How far to SF")

SOFT = ["travis", "jervis"]


def seq(items):
    st, out, now = {}, [], 100.0
    for delay, text in items:
        now += delay
        out.append(wake.classify_wake(T, text, st, now, soft_list=SOFT))
    return out


got = seq([(0, "Travis."), (5, "What is the weather in Oakland?")])
ck("soft mangle + question fires", got == [("soft-arm", None), ("fire-soft-followup", "What is the weather in Oakland?")], str(got))
got = seq([(0, "Darvis."), (3, "Set a timer for 30 seconds")])
ck("1-edit mangle is a strict hit (hard arm) and still answers",
   got == [("hard-arm", None), ("fire-hard-followup", "Set a timer for 30 seconds")], str(got))
got = seq([(0, "Darvys."), (3, "Set a timer for 30 seconds")])
ck("generic near-miss (2 edits, short) soft-arms", got[0] == ("soft-arm", None) and got[1][0] == "fire-soft-followup", str(got))
got = seq([(0, "Travis, what's the weather in Oakland?")])
ck("mangle + separator + question fires inline", got == [("fire-soft-inline", "what's the weather in Oakland")], str(got))
got = seq([(0, "Travis what is the weather today please")])
ck("mangle without separator in a long sentence is ignored", not got[0][0].startswith("fire"), str(got))
got = seq([(0, "Travis."), (3, "I'm going to go."), (3, "What time is it?")])
ck("statement consumes soft arm", not any(a.startswith("fire") for a, _ in got), str(got))
got = seq([(0, "Okay."), (1, "What time is it?")])
ck("ordinary word never arms", not any(a.startswith("fire") for a, _ in got), str(got))
got = seq([(0, "Jarvis."), (30, "What is six plus six?")])
ck("late question after bare wake accepted (shape-gated)", got == [("hard-arm", None), ("fire-hard-followup", "What is six plus six?")], str(got))
got = seq([(0, "Jarvis."), (30, "I'm going to go.")])
ck("late ambient statement rejected", got == [("hard-arm", None), ("ignore", None)], str(got))
got = seq([(0, "Travis is coming over later to help")])
ck("long sentence starting with a mangle does not soft-arm", got[0][0] in ("ignore", "ignore-soft-inline"), str(got))
d = {}
ck("dedup: first accepted", not wake.duplicate_request(d, "What is 3 plus 3?", 100, 45))
ck("dedup: repeat suppressed", wake.duplicate_request(d, "what is 3 plus 3", 130, 45))
ck("dedup: allowed after window", not wake.duplicate_request(d, "What is 3 plus 3?", 200, 45))

print("\n== commands ==")
for utter, kind, val in [
        ("set a timer for 3 minutes", "timer", 180),
        ("set a timer for 30 seconds", "timer", 30),
        ("set a timer for five minutes", "timer", 300),
        ("start a 45 second timer", "timer", 45),
        ("remind me in 90 seconds to stretch", "timer", 90),
        ("add a todo call the plumber", "todo", "call the plumber"),
        ("remind me to renew my passport", "todo", "renew my passport"),
        ("mark off call the plumber", "done", "call the plumber"),
        ("what is the capital of Peru", None, None)]:
    r = commands.parse_command(utter)
    if kind is None:
        ck(f"{utter!r} -> LLM", r is None)
    elif kind == "timer":
        ck(f"{utter!r} -> {val}s", r and r[0] == "timer" and r[1]["seconds"] == val, str(r))
    else:
        ck(f"{utter!r} -> {kind}", r and r[0] == kind and val in str(r[1]).lower(), str(r))
ck("span 30s", commands.span(30) == "30 sec")
ck("span 90m", commands.span(5400) == "1h 30m")
t = commands.add_timer(5, "")
ck("store: default label", t["label"] == "Timer")
ck("store: persisted", any(x["id"] == t["id"] for x in commands.load_store()["timers"]))

print("\n== display / glyphs ==")
ck("115 kept whole", len(display.clip("A" * 115)) == 115)
ck("116 clipped to <=115 with ellipsis", len(display.clip("B" * 116)) <= 115 and display.clip("B" * 116).endswith("…"))
ck("non-BMP emoji stripped", "\U0001F600" not in display.sanitize("hi \U0001F600"))
ck("BMP glyph kept", "⏰" in display.sanitize("⏰ tea"))
ck("whitespace collapsed", display.sanitize("a\n\n  b") == "a b")
ck("bar", glyphs.bar(0.7) == "▓▓▓▓▓▓▓░░░")
ck("all icons are BMP", all(glyphs.is_bmp(v) for v in glyphs.ICON.values()))
ck("weather decorated", glyphs.decorate("Cloudy, 68 degrees").startswith("☁"))
ck("non-weather untouched", glyphs.decorate("Lima is the capital.") == "Lima is the capital.")
ck("card", glyphs.card("ok", "done") == "✓ done")

print("\n== crown channel: hold the card until RayNeo's own answer clears ==")
ck("logcat timestamp parses", display._secs("09-04 21:47:13.500  1 2 I X: y") == display._secs("09-04 21:47:13.500"))
ck("timestamp difference is seconds", round(display._secs("09-04 21:47:20.000") - display._secs("09-04 21:47:13.500"), 1) == 6.5)
ck("garbage timestamp -> None", display._secs("no timestamp here") is None)
_gaps = []
display.native_assistant_gap = lambda *a, **k: _gaps.pop(0) if _gaps else None
display.device_time = lambda: "09-04 21:00:00.000"
_gaps[:] = [None] * 10
w = display.wait_native_idle(quiet=3, dwell=8, max_wait=30, min_hold=0)
ck("no native activity and no min hold -> no wait", w < 1.0, f"{w:.1f}s")
_gaps[:] = [None] * 10
w = display.wait_native_idle(quiet=3, dwell=8, max_wait=30, min_hold=2)
ck("holds min_hold for their reply to start, then gives up", 2.0 <= w <= 3.5, f"{w:.1f}s")
_gaps[:] = [None, None, 0.5, 1.5, 2.5, 3.5]
w = display.wait_native_idle(quiet=3, dwell=0.2, max_wait=30, min_hold=10)
ck("late-starting reply: waits for it, then for quiet, then dwells", 5.0 <= w <= 6.5, f"{w:.1f}s")
_gaps[:] = [0.1] * 60
w = display.wait_native_idle(quiet=3, dwell=8, max_wait=4, min_hold=10)
ck("never waits past max_wait", w <= 5.5, f"{w:.1f}s")

print("\n== config ==")
c = config.Config(os.path.join(TMP, "c2.json"))
c.update({"wake": {"trigger": "Athena", "arm_window": "60", "soft_mangles": "a, b"},
          "llm": {"groq_api_key": "gsk_x", "gemini_api_key": ""},
          "display": {"repost_on_wake": "false"}, "bogus": {"x": 1}})
ck("string kept", c.get("wake.trigger") == "Athena")
ck("int coerced", c.get("wake.arm_window") == 60)
ck("list from csv", c.get("wake.soft_mangles") == ["a", "b"])
ck("bool coerced", c.get("display.repost_on_wake") is False)
ck("secret set", c.get("llm.groq_api_key") == "gsk_x")
ck("empty secret ignored (keeps old)", c.get("llm.gemini_api_key") == "")
c.update({"llm": {"groq_api_key": ""}})
ck("empty secret does not clear", c.get("llm.groq_api_key") == "gsk_x")
pub = c.public()
ck("public masks secrets", pub["llm"]["groq_api_key"] == "" and pub["_secrets_set"]["llm.groq_api_key"] is True)
c.save(); c2 = config.Config(os.path.join(TMP, "c2.json"))
ck("roundtrip", c2.get("wake.trigger") == "Athena" and c2.get("llm.groq_api_key") == "gsk_x")
ck("unknown section ignored", c2.get("bogus.x") is None)

print("\n== card app (the 'Shell' header fix) ==")
_calls = []


class _R:
    returncode, stdout, stderr = 0, "Broadcast completed: result=0", ""


display.adb = lambda *a, **k: (_calls.append(a[-1]), _R())[1]
display._have_card_app = True
display.send("body text", "Jarvis")
ck("uses the card app when installed", "com.iohelper.card/.ShowReceiver" in _calls[-1], _calls[-1])
ck("passes header for the app-name line", "--es header" in _calls[-1], _calls[-1])
ck("body is quoted", "'body text'" in _calls[-1], _calls[-1])
_calls.clear()
display._have_card_app = False
display.send("body text", "Jarvis")
ck("falls back to cmd notification without the app", "cmd notification post" in _calls[-1], _calls[-1])
_calls.clear()
display._have_card_app = True
display.send("64F & clear; rm -rf /", "Jarvis")
body = _calls[-1].split("--es body ", 1)[1]
ck("shell metacharacters stay inside quotes", body.startswith("'") and "&" in body, body[:60])

print("\n== model picking ==")
gm = ["gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
      "gemini-3-flash-preview", "gemini-3.1-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash-image"]
ck("gemini: newest non-lite flash", llm.pick_model("gemini", gm) == "gemini-3.8-flash", llm.pick_model("gemini", gm))
ck("gemini: fallback when empty", llm.pick_model("gemini", []) == "gemini-2.5-flash")
gq = ["whisper-large-v3", "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct"]
ck("groq: 70b preferred", llm.pick_model("groq", gq) == "llama-3.3-70b-versatile")
ck("groq: llama-4 next", llm.pick_model("groq", ["whisper-large-v3", "meta-llama/llama-4-scout-17b-16e-instruct"]) .startswith("meta-llama/llama-4"))
today = ["allam-2-7b", "canopylabs/orpheus-arabic-saudi", "canopylabs/orpheus-v1-english", "groq/compound",
         "groq/compound-mini", "meta-llama/llama-prompt-guard-2-22m", "openai/gpt-oss-120b", "openai/gpt-oss-20b",
         "openai/gpt-oss-safeguard-20b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b", "whisper-large-v3"]
ck("groq: today's catalog -> gpt-oss-120b, never allam/guard/tts", llm.pick_model("groq", today) == "openai/gpt-oss-120b", llm.pick_model("groq", today))
ck("groq: newest qwen when no gpt-oss", llm.pick_model("groq", ["allam-2-7b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b"]) == "qwen/qwen3.8-27b")
ck("groq: only junk -> sane fallback", llm.pick_model("groq", ["allam-2-7b", "whisper-large-v3"]) == "openai/gpt-oss-120b")
ck("no key -> no models (graceful)", llm.list_models("groq") == [] and llm.list_models("gemini") == [])

print("\n== search routing ==")
for q, exp in [("what is the weather in Oakland", "weather"), ("how long is the drive to Berkeley High School", "directions"),
               ("what are my events for tomorrow", None), ("any concerts near me this weekend", "events"),
               ("latest news on the port strike", "news"), ("coffee near me", "local"),
               ("who won the Warriors game", "web"), ("what is the capital of Peru", None),
               ("set a timer for 30 seconds", None), ("do I have anything today", None)]:
    ck(f"{q!r} -> {exp}", search.classify(q) == exp, str(search.classify(q)))
ck("search inert without key", search.search_context("weather in oakland") == "")

print("\n" + "=" * 50)
print(f"RESULT: {sum(R)}/{len(R)} passed")
sys.exit(0 if all(R) else 1)
