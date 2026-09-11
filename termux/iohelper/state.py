"""Runtime status shared between the assistant, proactive pusher and portal,
plus the single logger. Thread-safe; the portal only ever reads it."""
import collections
import os
import threading
import time
from datetime import datetime

from .config import APP_DIR

LOG_PATH = os.path.join(APP_DIR, "iohelper.log")
_lock = threading.Lock()
_ring = collections.deque(maxlen=300)           # recent log lines for the portal
_events = collections.deque(maxlen=40)          # transcripts / answers timeline
STATUS = {
    "started_at": time.time(), "adb": "unknown", "listening": False,
    "last_transcript": "", "last_answer": "", "last_error": "",
    "backend": "", "model": "", "serpapi": "", "captures": 0, "fires": 0,
    "soft_arms": 0, "ignored": 0, "delivered": 0, "unconfirmed": 0,
}


def log(msg):
    line = f"{datetime.now():%m-%d %H:%M:%S}  {msg}"
    with _lock:
        _ring.append(line)
        try:
            with open(LOG_PATH, "a", encoding="utf-8") as fh:
                fh.write(line + "\n")
        except OSError:
            pass
    try:
        print(line, flush=True)
    except (AttributeError, OSError, ValueError):
        pass


def set_status(**kw):
    with _lock:
        STATUS.update(kw)


def bump(key, n=1):
    with _lock:
        STATUS[key] = STATUS.get(key, 0) + n


def event(kind, text):
    with _lock:
        _events.append({"t": time.time(), "kind": kind, "text": text})


def snapshot():
    with _lock:
        return {"status": dict(STATUS), "events": list(_events),
                "log": list(_ring)[-120:]}
