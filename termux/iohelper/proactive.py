"""Unprompted pushes: calendar heads-ups, fired timers, due to-dos.

Each push is confirmed by the glasses' render receipt before it is marked
done, so a sleeping link simply retries on the next poll.
"""
import threading
import time
from datetime import datetime

from . import commands, display, glyphs, state
from .config import CFG


def push(body, title=None):
    """Every card goes out under the assistant's name (the card header the
    glasses show is the poster's app label - Android's "Shell" - which cannot
    be changed from adb; the *title* line is ours, so keep it one consistent
    name and let a leading glyph say what kind of card it is)."""
    return display.deliver(body, ttl=title or display.title())["rendered"]


def fmt_time(epoch, z):
    return datetime.fromtimestamp(epoch, z).strftime("%I:%M %p").lstrip("0")


def poll():
    now = time.time()
    d = commands.load_store()
    dirty = False
    notified = d["notified"]
    lead = CFG.get("proactive.lead_minutes")
    interval = CFG.get("proactive.interval")

    if CFG.get("calendar.enabled"):
        try:
            events = commands.calendar_events(12)
        except Exception as e:
            state.log(f"calendar read failed: {e!r}")
            events = []
        z = commands.tz()
        for ev in events:
            if ev["allday"]:
                continue
            begin = ev["begin"] / 1000
            mins = (begin - now) / 60
            key = f"{ev['begin']}:{ev['title'][:40]}"
            lk = key + f":lead{lead}"
            if 0 < mins <= lead and not notified.get(lk):
                body = glyphs.card("cal", f"{ev['title']} in {int(round(mins))} min ({fmt_time(begin, z)})")
                if push(body):
                    notified[lk] = dirty = True
                    state.log(f"calendar heads-up: {ev['title']} in {int(round(mins))}m")
            sk = key + ":start"
            if CFG.get("proactive.start_ping") and -interval / 60 - 1 <= mins <= 0.5 \
                    and not notified.get(sk):
                if push(glyphs.card("cal", f"Now: {ev['title']}")):
                    notified[sk] = dirty = True

    for t in list(d["timers"]):
        if t["fire_at"] <= now and not t.get("notified"):
            label = t["label"] if t["label"].lower() != "timer" else ""
            if push(glyphs.card("timer", f"Timer done{': ' + label if label else ''}")):
                state.log(f"timer fired: {t['label']}")
                d["timers"].remove(t)
                dirty = True

    for t in d["todos"]:
        if t.get("due_at") and not t["done"] and not t.get("notified") and t["due_at"] <= now:
            if push(glyphs.card("ok", f"To-do: {t['text']}")):
                t["notified"] = dirty = True

    dh = CFG.get("proactive.digest_hours")
    if dh and dh > 0 and now - d.get("_digest_at", 0) >= dh * 3600:
        open_todos = [t["text"] for t in d["todos"] if not t["done"]]
        if not open_todos or push(glyphs.card("ok", "To-dos: " + "; ".join(open_todos))):
            d["_digest_at"] = now
            dirty = True

    if len(notified) > 200:
        d["notified"] = {k: v for k, v in notified.items()
                         if int(k.split(":")[0]) / 1000 > now - 86400}
        dirty = True
    if dirty:
        commands.save_store(d)


def run(stop_event: threading.Event):
    state.log("proactive pusher up")
    while not stop_event.is_set():
        if CFG.get("proactive.enabled"):
            try:
                if display.ensure_connected():
                    poll()
            except Exception as e:
                state.log(f"proactive error: {e!r}")
        stop_event.wait(max(10, int(CFG.get("proactive.interval"))))
