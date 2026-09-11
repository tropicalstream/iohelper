"""The glasses transport: adb -> phone notification -> RNLink/SPP -> display.

Everything here is measured behaviour of RayNeo iO-F761 / Strix OS 1.0.3.11:
  * body <=115 chars renders fully; 116-130 is visually cut; >=131 NEVER renders
    (display_result=fail) while every upstream indicator still says success;
  * one stable notification tag: reposting under the same tag updates the card
    in place, re-renders on the glasses, and keeps the shade count flat (fresh
    tags pile up and Android's LOCAL_ONLY auto-group silently stops forwarding);
  * delivery is confirmed by the glasses' own render receipt (display_result),
    matched by VALUE - the buffer is full of unrelated *_success strings;
  * cards render (and ACK) even to a dark screen, so the last card is re-posted
    once when the phone reports the screen turning on.
Independence: we use our OWN tag and title, never touch the RayNeo app process,
and never write to its VOICE_ASSISTANT channel - the native assistant is
unaffected by anything in this module.
"""
import re
import shlex
import subprocess
import time

from . import state
from .config import CFG

RENDER_CEILING = 130
RESULT = re.compile(r"display_result[^,]{0,8}?(success|fail)")
TRUNC_B = re.compile(r"is_body_truncated[^,]{0,8}?(true|false)")


def adb(*args, timeout=30):
    # -s <serial> only when one is actually set; adb picks the sole connected
    # device on its own otherwise, so an unset device.serial just works
    # instead of failing on an empty -s "" flag.
    serial = CFG.get("device.serial")
    target = ["-s", serial] if serial else []
    return subprocess.run([CFG.get("device.adb"), *target, *args],
                          capture_output=True, text=True, errors="replace", timeout=timeout)


def ensure_connected():
    """True when the adb transport is usable, reconnecting a network device."""
    try:
        if adb("get-state", timeout=15).stdout.strip() == "device":
            state.set_status(adb="connected")
            return True
        serial = CFG.get("device.serial")
        if ":" in serial:
            subprocess.run([CFG.get("device.adb"), "connect", serial],
                           capture_output=True, text=True, timeout=30)
            time.sleep(2)
            ok = adb("get-state", timeout=15).stdout.strip() == "device"
            state.set_status(adb="connected" if ok else "unreachable")
            return ok
    except (subprocess.TimeoutExpired, OSError) as e:
        state.set_status(adb=f"error: {e!r}"[:80])
        return False
    state.set_status(adb="unreachable")
    return False


def sanitize(text):
    """Collapse whitespace; drop non-BMP characters (emoji render inconsistently
    on the glasses; BMP symbols like the ones in glyphs.py render fine)."""
    text = re.sub(r"\s+", " ", str(text)).strip()
    return "".join(c for c in text if ord(c) < 0x10000)


def clip(text, limit=None):
    limit = limit or CFG.get("device.body_limit")
    text = sanitize(text)
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def title():
    return CFG.get("device.title") or CFG.get("wake.trigger", "iO").capitalize()


# The glasses render "<app label> / <title> / <body>". `cmd notification post`
# posts as com.android.shell, so cards read "Shell". android/ builds a tiny app
# whose label IS the header; when it is installed we broadcast to it instead
# (and can also pass a per-card header, and cancel - neither is possible with
# `cmd notification`). Falls back to the shell path automatically.
CARD_PKG = "com.iohelper.card"
_have_card_app = None


def card_app_installed(refresh=False):
    global _have_card_app
    if _have_card_app is None or refresh:
        try:
            r = adb("shell", f"pm list packages {CARD_PKG}", timeout=20)
            _have_card_app = CARD_PKG in r.stdout
        except (subprocess.TimeoutExpired, OSError):
            return False
    return _have_card_app


def send(body, ttl=None, tag=None):
    ttl = ttl or title()
    if CFG.get("device.use_card_app", True) and card_app_installed():
        remote = (
            f"am broadcast -n {CARD_PKG}/.ShowReceiver -a {CARD_PKG}.SHOW"
            f" --es title {shlex.quote(ttl)} --es body {shlex.quote(body)}"
            f" --es header {shlex.quote(CFG.get('device.header') or title())} --ei id 1"
        )
        r = adb("shell", remote)
        # am reports its own success; a missing receiver still exits 0 with an
        # error line, so check the payload before trusting it.
        if r.returncode == 0 and "Broadcast completed" in r.stdout:
            return r
        state.log("    card app broadcast failed - falling back to shell notification")
    remote = "cmd notification post -S bigtext -t {} {} {}".format(
        shlex.quote(ttl), shlex.quote(tag or CFG.get("device.tag")), shlex.quote(body))
    return adb("shell", remote)


def cancel():
    """Dismiss the current card. Only possible via the card app - `cmd
    notification` has no cancel subcommand."""
    if not card_app_installed():
        return False
    r = adb("shell", f"am broadcast -n {CARD_PKG}/.ShowReceiver "
                     f"-a {CARD_PKG}.CANCEL --ei id 1")
    return r.returncode == 0 and "Broadcast completed" in r.stdout


def device_time():
    """Device-local logcat timestamp, millisecond precision (second granularity
    let a late receipt from a previous send count for the next one)."""
    r = adb("shell", 'date +"%m-%d %H:%M:%S.%3N"', timeout=20)
    ts = r.stdout.strip()
    return ts if re.match(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$", ts) else None


def _recent(ts):
    args = ["logcat", "-d"] + (["-T", ts] if ts else ["-t", "400"])
    try:
        return adb(*args, timeout=60).stdout
    except subprocess.TimeoutExpired:
        return ""


# Two possible confirmations, newest firmware first:
#  * TRANSMITTED - the phone's RNLink stack acked sending the frame to the
#    glasses ("rayneonet_message_send_success" / "messageSendSuccess").
#  * RENDERED    - the glasses replied with an analytics_log carrying
#    display_result=success. Older firmware sent this for every drawn card;
#    on Strix OS 1.0.3.11 as of 2026-09 the glasses no longer emit analytics_log
#    at all (only brightness_change), so a card can draw perfectly with no
#    such receipt. Treating its absence as failure produced false negatives on
#    every send, so transmission is the primary signal and the render receipt
#    is used as a bonus when the firmware provides it.
SENT_OK = re.compile(r"rayneonet_message_send_success|messageSendSuccess")


def rendered_since(ts):
    """True if the glasses confirmed drawing the card (older firmware only)."""
    return "success" in RESULT.findall(_recent(ts))


def confirm_since(ts):
    """('rendered'|'sent'|'none') - the strongest confirmation available."""
    out = _recent(ts)
    if "success" in RESULT.findall(out):
        return "rendered"
    if SENT_OK.search(out):
        return "sent"
    return "none"


_TS = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2}\.\d{3})")


def _secs(ts):
    """logcat 'MM-DD HH:MM:SS.mmm' -> seconds (year-relative; only differences matter)."""
    m = _TS.match(ts or "")
    if not m:
        return None
    mo, d, h, mi, s = m.groups()
    return (((int(mo) * 31 + int(d)) * 24 + int(h)) * 60 + int(mi)) * 60 + float(s)


def native_assistant_gap(since=None):
    """Seconds since RayNeo's own assistant last streamed to the glasses
    (VOICE_ASSISTANT frames) - looking only at log since `since` (a device
    timestamp) when given. None if there has been no such activity."""
    try:
        out = _recent(since) if since else adb("logcat", "-d", "-t", "600", timeout=20).stdout
        now = _secs(device_time())
    except (subprocess.TimeoutExpired, OSError):
        return None
    last = None
    for line in out.splitlines():
        if "VOICE_ASSISTANT" in line:
            t = _secs(line)
            if t:
                last = t
    if last is None or now is None:
        return None
    return max(0.0, now - last)


def wait_native_idle(quiet=None, dwell=None, max_wait=None, min_hold=None):
    """Crown channel only. RayNeo's assistant answers the same utterance and
    its streamed reply OWNS the display - a card sent meanwhile is never seen.

    Timing matters: our LLM often answers BEFORE RayNeo's reply even starts
    streaming (measured: 3 s vs their 6-10 s), so a simple "is the channel
    quiet?" check released too early and we were preempted anyway. So:
      1. hold at least `min_hold` s for their reply to START (bail early if it
         never does - e.g. they had no answer);
      2. once seen, wait until VOICE_ASSISTANT has been silent `quiet` s;
      3. then `dwell` s more so theirs can be read or dismissed.
    Everything is bounded by `max_wait`. Returns the seconds waited."""
    quiet = quiet if quiet is not None else CFG.get("display.native_quiet")
    dwell = dwell if dwell is not None else CFG.get("display.native_dwell")
    max_wait = max_wait if max_wait is not None else CFG.get("display.native_max_wait")
    min_hold = min_hold if min_hold is not None else CFG.get("display.native_min_hold")
    start = time.time()
    since = device_time()
    seen = False
    while time.time() - start < max_wait:
        gap = native_assistant_gap(since)
        if gap is not None:
            seen = True
            if gap >= quiet:
                break
        elif time.time() - start >= min_hold:
            break                                  # they never answered
        time.sleep(1)
    if seen:
        time.sleep(min(dwell, max(0.0, max_wait - (time.time() - start))))
    return time.time() - start


# The most recent card, re-posted once when the screen wakes shortly after.
LAST_CARD = {"body": None, "title": None, "at": 0.0, "reposted": True}


def deliver(body, ttl=None, tag=None, settle=None):
    """Send ONCE and confirm by receipt. A missing receipt is 'unconfirmed', not
    a reason to resend: blind retries post visible duplicates (measured)."""
    body = clip(body)
    if not body:
        return {"sent": False, "rendered": False}
    ttl = ttl or title()
    LAST_CARD.update(body=body, title=ttl, at=time.time(), reposted=False)
    ts = device_time()
    r = send(body, ttl, tag)
    if r.returncode != 0:
        state.log(f"    adb send error: {r.stderr.strip()[:160]}")
        state.set_status(last_error=f"send: {r.stderr.strip()[:80]}")
        return {"sent": False, "rendered": False}
    time.sleep(settle or CFG.get("device.settle"))
    conf = confirm_since(ts)
    if conf == "rendered":
        state.bump("delivered")
        state.log("    delivered (glasses render receipt)")
    elif conf == "sent":
        state.bump("delivered")
        state.log("    delivered (transmitted to glasses over RNLink)")
    else:
        state.bump("unconfirmed")
        state.log("    posted, but no RNLink ack (glasses link down?)")
    return {"sent": True, "rendered": conf != "none", "confirm": conf}


def maybe_repost_on_wake(line, now=None):
    """One-shot re-post of the last card when a screen-on event arrives."""
    if not CFG.get("display.repost_on_wake"):
        return False
    if '"cmd":"screen_status"' not in line or '"value":1' not in line:
        return False
    c = LAST_CARD
    now = now if now is not None else time.time()
    if c["reposted"] or not c["body"] or now - c["at"] > CFG.get("display.repost_window"):
        return False
    c["reposted"] = True
    state.log("    screen woke - re-posting last card")
    try:
        send(c["body"], c["title"])
    except Exception as e:
        state.log(f"    re-post failed: {e!r}")
    return True
