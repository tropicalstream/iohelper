"""Local commands (timers, to-dos) and the phone calendar.

Timers/to-dos are handled without an LLM round-trip and shared with the
proactive pusher through store.json. The calendar is read from the phone over
adb (content provider) - RayNeo's own calendar shade is untouched.
"""
import json
import os
import re
import time
from datetime import datetime

from . import display
from .config import APP_DIR

STORE_PATH = os.environ.get("IOHELPER_STORE", os.path.join(APP_DIR, "store.json"))
CAL_ROW = re.compile(r"begin=(\d+), end=(\d+), allDay=(\d+), title=(.*)$")


# ---- calendar ----------------------------------------------------------------
def device_timezone():
    try:
        r = display.adb("shell", "getprop persist.sys.timezone", timeout=20)
        return r.stdout.strip() or "UTC"
    except Exception:
        return "UTC"


def tz():
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(device_timezone())
    except Exception:
        return None


def calendar_events(hours=24):
    """Sorted, de-duplicated events in the next `hours` from the phone."""
    now = int(time.time() * 1000)
    end = now + hours * 3600 * 1000
    uri = f"content://com.android.calendar/instances/when/{now}/{end}"
    r = display.adb("shell", f'content query --uri "{uri}" --projection "begin:end:allDay:title"',
                    timeout=60)
    if r.returncode != 0 or "Row:" not in r.stdout:
        return []
    seen, events = set(), []
    for line in r.stdout.splitlines():
        m = CAL_ROW.search(line.strip())
        if not m:
            continue
        b, e, allday, title = int(m.group(1)), int(m.group(2)), m.group(3), m.group(4).strip()
        key = (b, e, title.lower())
        if key in seen:
            continue
        seen.add(key)
        events.append({"begin": b, "end": e, "allday": allday == "1", "title": title})
    events.sort(key=lambda x: x["begin"])
    return events


def calendar_snapshot(hours=24, limit=12):
    events = calendar_events(hours)
    if not events:
        return f"[Calendar: no events in the next {hours}h]"
    z = tz()
    out = []
    for ev in events[:limit]:
        if ev["allday"]:
            when = datetime.fromtimestamp(ev["begin"] / 1000, z).strftime("%a") + " all day"
        else:
            s = datetime.fromtimestamp(ev["begin"] / 1000, z)
            f = datetime.fromtimestamp(ev["end"] / 1000, z)
            when = f"{s:%a %H:%M}-{f:%H:%M}"
        out.append(f"- {when}  {ev['title']}")
    more = f"\n- (+{len(events) - limit} more)" if len(events) > limit else ""
    return f"[Calendar for the next {hours}h, {device_timezone()}]\n" + "\n".join(out) + more


# ---- store -----------------------------------------------------------------
def load_store():
    try:
        with open(STORE_PATH, encoding="utf-8") as fh:
            d = json.load(fh)
    except (OSError, json.JSONDecodeError):
        d = {}
    d.setdefault("timers", [])
    d.setdefault("todos", [])
    d.setdefault("notified", {})
    return d


def save_store(d):
    tmp = STORE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(d, fh, indent=2)
    os.replace(tmp, STORE_PATH)


def _new_id(prefix):
    return f"{prefix}{int(time.time() * 1000) % 100000000}"


def add_timer(seconds, label=""):
    d = load_store()
    t = {"id": _new_id("t"), "label": label.strip() or "Timer",
         "fire_at": time.time() + seconds, "notified": False}
    d["timers"].append(t)
    save_store(d)
    return t


def add_todo(text, due_epoch=None):
    d = load_store()
    t = {"id": _new_id("d"), "text": text.strip(), "due_at": due_epoch,
         "done": False, "created": time.time(), "notified": False}
    d["todos"].append(t)
    save_store(d)
    return t


def complete_todo(match):
    d = load_store()
    m = match.lower().strip()
    for t in d["todos"]:
        if not t["done"] and (m in t["text"].lower() or m == t["id"]):
            t["done"] = True
            save_store(d)
            return t
    return None


# ---- parsing -------------------------------------------------------------------
_DUR = re.compile(r"(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)\b", re.I)
_WORDNUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
            "eight": 8, "nine": 9, "ten": 10, "fifteen": 15, "twenty": 20, "thirty": 30,
            "forty": 40, "forty-five": 45, "sixty": 60, "ninety": 90, "a": 1, "an": 1}


def _digits(text):
    """'five minutes' -> '5 minutes' so the duration regex sees a number."""
    def rep(m):
        return str(_WORDNUM[m.group(1).lower()])
    return re.sub(r"\b(" + "|".join(re.escape(k) for k in _WORDNUM) + r")\b(?=\s*(?:hour|hr|min|sec|h\b|m\b|s\b))",
                  rep, text, flags=re.I)


def parse_duration(text):
    total, found = 0, False
    for n, unit in _DUR.findall(_digits(text)):
        found = True
        u = unit.lower()
        mult = 3600 if u[0] == "h" else 1 if u[0] == "s" else 60
        total += int(n) * mult
    return total if found else None


def span(secs):
    if secs >= 3600:
        return f"{secs // 3600}h {secs % 3600 // 60}m"
    if secs >= 60:
        return f"{secs // 60} min"
    return f"{secs} sec"


def parse_command(text):
    """('timer', {...}) | ('todo', {...}) | ('done', {...}) | None (-> LLM)."""
    t = text.strip()
    low = t.lower()
    m = re.search(r"\b(?:mark|check)\s+off\s+(.+)|\bcomplete[d]?\s+(?:the\s+)?(?:todo|task|to-do)\s+(.+)|\b(?:todo|task)\s+(.+?)\s+(?:is\s+)?done\b", low)
    if m:
        return ("done", {"match": next(g for g in m.groups() if g)})
    dur = parse_duration(low)
    remind_in = re.search(r"\bremind me (?:in|after)\b", low)
    if dur and (re.search(r"\btimer\b|\bcountdown\b", low) or remind_in):
        if remind_in:
            lbl = re.sub(r".*\bto\b", "", t, count=1, flags=re.I).strip(" .,") or "Reminder"
        else:
            lbl = re.sub(r"\b(?:set|start|a|an|the|for|to|me|please)\b|\btimer\b|\bcountdown\b",
                         " ", _digits(t), flags=re.I)
            lbl = _DUR.sub(" ", lbl)
            lbl = re.sub(r"\s+", " ", lbl).strip(" .,")
        return ("timer", {"seconds": dur, "label": lbl})
    m = re.search(r"\b(?:add|create)\s+a?\s*(?:todo|to-do|task)\b[:,]?\s*(.+)|\bremind me to\b\s*(.+)|\bnote to self\b[:,]?\s*(.+)", low)
    if m:
        txt = next(g for g in m.groups() if g)
        idx = low.rfind(txt[:12]) if len(txt) >= 12 else low.find(txt)
        orig = t[idx:] if idx >= 0 else txt
        return ("todo", {"text": orig.strip(" .,?"), "due": None})
    return None
