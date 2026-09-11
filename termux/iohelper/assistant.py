"""The assistant loop: transcript -> wake gate -> (local command | LLM) -> card.

ask() is also called by the portal's "try it" box, so a question typed there
goes through exactly the path a spoken one does.
"""
import threading
import time

from . import commands, display, glyphs, llm, search, state, transcripts, wake
from .config import CFG


def build_prompt(query):
    parts = []
    if CFG.get("search.mode") != "off" and search.available():
        try:
            ctx = search.search_context(query, CFG.get("search.location"), CFG.get("search.mode"))
            if ctx:
                parts.append(ctx)
                state.log("    + live search")
            elif getattr(search, "LAST_ERROR", None):
                state.log(f"    search failed: {search.LAST_ERROR}")
        except Exception as e:
            state.log(f"    search unavailable: {e!r}")
    if CFG.get("calendar.enabled"):
        try:
            snap = commands.calendar_snapshot(CFG.get("calendar.hours"))
            if snap:
                parts.append(snap)
        except Exception as e:
            state.log(f"    calendar unavailable: {e!r}")
    parts.append(f"Using the context above only when relevant, answer: {query}")
    return "\n\n".join(parts)


def run_command(cmd):
    kind, p = cmd
    if kind == "timer":
        if p["seconds"] <= 0:
            return "Timer needs a duration - try '10 minutes'."
        t = commands.add_timer(p["seconds"], p["label"])
        state.log(f"    timer set: {t['label']} in {commands.span(p['seconds'])}")
        # add_timer defaults an empty label to "Timer" - don't say "Timer: Timer"
        label = t["label"] if t["label"].lower() != "timer" else ""
        return glyphs.card("timer", f"Timer set for {commands.span(p['seconds'])}"
                                    + (f" - {label}" if label else "") + ".")
    if kind == "todo":
        t = commands.add_todo(p["text"])
        return glyphs.card("ok", f"To-do added: {t['text']}")
    if kind == "done":
        t = commands.complete_todo(p["match"])
        return glyphs.card("ok", f"To-do done: {t['text']}") if t else "No matching to-do found."
    return None


def ask(query, deliver=True):
    """Full pipeline for one query. Returns {'answer','delivered','rendered','source'}."""
    query = query.strip()
    cmd = commands.parse_command(query)
    if cmd:
        answer, source = run_command(cmd), "command"
    else:
        backend = CFG.get("llm.backend")
        state.set_status(backend=backend)
        try:
            answer = llm.ask(build_prompt(query))
            state.set_status(model=llm.effective_model(backend), last_error="")
        except llm.LLMError as e:
            state.log(f"    llm error: {e}")
            state.set_status(last_error=str(e)[:160])
            return {"answer": "", "delivered": False, "rendered": False, "source": "error",
                    "error": str(e)}
        answer = glyphs.decorate(display.sanitize(answer))
        if not answer:
            state.log("    llm returned nothing - not delivering")
            state.set_status(last_error="empty reply")
            return {"answer": "", "delivered": False, "rendered": False, "source": "error",
                    "error": "empty reply"}
        source = backend
    state.log(f"    {source}: {answer}")
    state.set_status(last_answer=answer)
    state.event("answer", answer)
    res = {"sent": False, "rendered": False}
    if deliver and answer:
        if CFG.get("wake.source") == "assistant" and CFG.get("display.defer_after_native"):
            waited = display.wait_native_idle()
            if waited:
                state.log(f"    held {waited:.0f}s for RayNeo's own answer to clear")
        res = display.deliver(answer)
    return {"answer": answer, "delivered": res["sent"], "rendered": res["rendered"],
            "source": source}


def handle(text, sess):
    """One transcript through the wake gate."""
    if len(text) < CFG.get("wake.min_chars"):
        return
    now = time.time()
    if sess.get("text") == text and now - sess.get("at", 0) < 20:
        return
    sess["text"], sess["at"] = text, now
    state.bump("captures")
    state.set_status(last_transcript=text)
    state.event("heard", text)
    state.log(f'heard: "{text}"')

    trig = CFG.get("wake.trigger")
    if CFG.get("wake.source") == "assistant":
        # The crown press IS the trigger on this channel - every transcript is
        # a deliberate request. Still strip the wake word if the user says it.
        query = wake.strip_trigger(trig, text) if wake.trigger_hit(trig, text) else text
        if wake.duplicate_request(sess, query, now, CFG.get("wake.dedup_window")):
            state.bump("ignored")
            state.log(f'    duplicate suppressed: "{query}"')
            return
        state.bump("fires")
        state.log(f'    (crown) -> "{query}"')
        ask(query)
        return
    action, query = wake.classify_wake(
        trig, text, sess, now, soft_list=CFG.get("wake.soft_mangles"),
        arm_window=CFG.get("wake.arm_window"), fast_window=CFG.get("wake.fast_window"),
        soft_arm_window=CFG.get("wake.soft_arm_window"))
    if action.startswith("fire"):
        if wake.duplicate_request(sess, query, now, CFG.get("wake.dedup_window")):
            state.bump("ignored")
            state.log(f'    duplicate suppressed: "{query}"')
            return
        state.bump("fires")
        state.log(f'    ({"soft-armed" if "soft" in action else "armed"}) -> "{query}"')
        ask(query)
    elif action == "hard-arm":
        state.log("    wake word heard - waiting for the question...")
    elif action == "soft-arm":
        state.bump("soft_arms")
        state.log(f"    possible {trig!r} mangle - waiting for a request...")
    else:
        state.bump("ignored")
        state.log(f"    (no {trig!r} - ignored)")


def run(stop_event: threading.Event):
    sess, backoff = {}, 5
    while not stop_event.is_set():
        if not display.ensure_connected():
            state.log(f"bridge unreachable - retrying in {backoff}s")
            stop_event.wait(backoff)
            backoff = min(backoff * 2, 120)
            continue
        backoff = 5
        state.set_status(listening=True)
        state.log(f"listening  device={CFG.get('device.serial')}  wake={CFG.get('wake.trigger')!r}  "
                  f"source={CFG.get('wake.source')}  backend={CFG.get('llm.backend')}")
        try:
            for ev in transcripts.stream(stop_event):
                if ev["type"] == "screen_on":
                    display.maybe_repost_on_wake(ev["line"])
                elif ev["type"] == "transcript":
                    try:
                        handle(ev["text"], sess)
                    except Exception as e:
                        state.log(f"handler error: {e!r}")
        except Exception as e:
            state.log(f"stream error: {e!r}")
        state.set_status(listening=False)
        if not stop_event.is_set():
            state.log("log stream ended - reconnecting")
            stop_event.wait(3)
