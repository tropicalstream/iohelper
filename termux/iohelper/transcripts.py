"""Transcript source: a read-only tail of the phone's logcat.

The companion app logs every final ASR transcript it receives from the glasses.
Two channels exist:
  * alwayson  - the glasses' VAD-triggered continuous capture (Life Log). No
                wake word or crown press; RayNeo only transcribes, it does not
                answer - so our wake word never triggers RayNeo's assistant.
                This is the hands-free mode and the one that does not interfere.
  * assistant - the crown/native wake channel (phone_asr_text). RayNeo's cloud
                assistant ALSO answers every one of these, so expect two replies.
Also surfaced: the screen-on event the phone receives when the display wakes.
Nothing here writes to the device.
"""
import json
import re
import subprocess

from . import display, state
from .config import CFG

ASR_ASSISTANT = re.compile(r"phone_asr_text.*?payload=(\{.*?\})\s+eventTs=(\d+)")
ASR_ALWAYSON = re.compile(
    r"onAlwaysOnResponse role=(host|guest) text=(.*?) roundId=(\S+) finished=true")


def stream(stop_event=None):
    """Yield {'type': 'transcript'|'screen_on', ...} events until the logcat
    process ends (caller reconnects). Starts from *now* so a reconnect never
    replays old speech; round ids / event timestamps de-duplicate re-emits."""
    source = CFG.get("wake.source")
    role_filter = CFG.get("wake.role")
    serial = CFG.get("device.serial")
    cmd = [CFG.get("device.adb")] + (["-s", serial] if serial else []) + ["logcat"]
    since = display.device_time()
    if since:
        cmd += ["-T", since]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, errors="replace", bufsize=1)
    seen_ids, last_ts = set(), 0
    try:
        for line in proc.stdout:
            if stop_event is not None and stop_event.is_set():
                break
            if '"cmd":"screen_status"' in line:
                yield {"type": "screen_on" if '"value":1' in line else "screen_off",
                       "line": line}
                continue
            if source == "alwayson":
                m = ASR_ALWAYSON.search(line)
                if not m:
                    continue
                role, text, rid = m.group(1), m.group(2).strip(), m.group(3)
                if role_filter != "any" and role != role_filter:
                    continue
                if rid in seen_ids:
                    continue
                seen_ids.add(rid)
                if len(seen_ids) > 5000:
                    seen_ids = set(list(seen_ids)[-1000:])
                yield {"type": "transcript", "text": text, "role": role}
            else:
                m = ASR_ASSISTANT.search(line)
                if not m:
                    continue
                try:
                    payload = json.loads(m.group(1))
                except json.JSONDecodeError:
                    continue
                if not payload.get("final"):
                    continue
                ev = int(m.group(2))
                if ev <= last_ts:
                    continue
                last_ts = ev
                yield {"type": "transcript", "text": (payload.get("text") or "").strip(),
                       "role": "host"}
    finally:
        try:
            proc.kill()
        except OSError:
            pass
        state.set_status(listening=False)
