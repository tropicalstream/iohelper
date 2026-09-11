"""The wake-word gate. Pure functions + a small replayable state machine.

Design constraints learned the hard way on an always-recording device:
  * STRICT word-level match (one edit, real tokens only). A fuzzy window over
    long sentences answered ambient conversation.
  * The ASR mangles wake words ('Hermes' -> Hurry/Honey/Harry). A short,
    standalone near-miss ('soft arm') is accepted ONLY when a request-shaped
    utterance follows within a window, and the arm is consumed by that one
    follow-up - so a stray word can never capture later talk.
  * The glasses' VAD truncates utterance HEADS (a whole question once arrived
    as its tail). A TRAILING wake word survives that, so '..., Jarvis?' is the
    robust phrasing and is stripped cleanly from the query.
  * RayNeo can publish the question 19-39 s after the wake-word round, so a
    bare wake word arms for a long window, gated by request shape after 6 s.
"""
import re

REQUEST_OPENERS = {
    "what", "whats", "where", "when", "who", "whose", "why", "how", "which",
    "can", "could", "would", "will", "do", "does", "did", "is", "are", "was",
    "were", "should", "tell", "show", "find", "search", "check", "give", "set",
    "start", "add", "remind", "create", "mark", "navigate", "calculate", "look",
    "play", "open", "close", "turn", "text", "call", "translate", "define",
}


def _lev(a, b):
    if a == b:
        return 0
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def trigger_hit(trigger, text):
    """True when a real spoken token (or an ASR split of one) matches the wake
    word within one edit. Never matches substrings inside longer words."""
    nt = _norm(trigger)
    if not nt:
        return True
    tol = 1 if len(nt) >= 5 else 0
    words = re.findall(r"[a-z0-9]+", text.lower())
    for i, w in enumerate(words):
        if w == nt or (abs(len(w) - len(nt)) <= 1 and _lev(w, nt) <= tol):
            return True
        if i + 1 < len(words):
            combo = w + words[i + 1]
            if abs(len(combo) - len(nt)) <= 1 and _lev(combo, nt) <= tol:
                return True
    return False


def strip_trigger(trigger, text):
    """Remove a leading AND/OR trailing wake word (tolerating ASR splits)."""
    nt = _norm(trigger)
    words = text.split()
    acc, i = "", 0
    while i < len(words):
        cand = _norm(acc + words[i])
        if cand and (cand in nt or nt.startswith(cand) or _lev(cand, nt) <= 2):
            acc += words[i]
            i += 1
            continue
        break
    words = words[i:]

    def _is_wake(cand):
        return bool(cand) and abs(len(cand) - len(nt)) <= 1 and _lev(cand, nt) <= 1

    if words and _is_wake(_norm(words[-1])):
        words = words[:-1]
    elif len(words) >= 2 and _is_wake(_norm(words[-2] + words[-1])):
        words = words[:-2]
    return " ".join(words).strip(" ,.-")


def soft_trigger_rest(trigger, text, soft_list=()):
    """If `text` starts with a known/near mangle of the wake word, return what
    follows it ('' when the utterance was just the mangle); else None.
    Generic rule: a SHORT utterance (<=3 words) whose first token is within two
    edits of the trigger; plus any explicitly configured mangles."""
    nt = _norm(trigger)
    m = re.match(r"\s*[\"']*([A-Za-z0-9']+)([\"',.:;!?-]*)\s*(.*)$", text, re.S)
    if not m or not nt:
        return None
    first, punct, rest = _norm(m.group(1)), m.group(2), m.group(3)
    words = re.findall(r"[A-Za-z0-9']+", text)
    explicit = {_norm(x) for x in soft_list}
    near = abs(len(first) - len(nt)) <= 2 and _lev(first, nt) <= 2
    if not (first in explicit or (near and len(words) <= 3)):
        return None
    # A mangle that simply starts a longer sentence ("Travis is coming over
    # later") is conversation, not a call: require a separator after the word
    # ("Travis, what's…" / "Travis. What's…") unless the utterance is short.
    if rest and not punct and len(words) > 3:
        return None
    return rest.strip(" \t\"',.:;!?-")


def looks_like_request(text):
    words = re.findall(r"[a-z0-9]+", text.lower())
    if len(words) < 2:
        return False
    return "?" in text or words[0] in REQUEST_OPENERS


def duplicate_request(st, query, now, window):
    key = _norm(query)
    if not key:
        return False
    dup = key == st.get("last_request") and now - st.get("last_request_at", 0) <= window
    if not dup:
        st["last_request"], st["last_request_at"] = key, now
    return dup


def classify_wake(trigger, text, st, now, soft_list=(), arm_window=45,
                  fast_window=6, soft_arm_window=25):
    """Classify one transcript -> (action, query). Pure: no side effects beyond
    `st`, so it is replayable against real logs and deterministic in tests."""
    if not trigger:
        return "fire", text
    hard_armed = st.get("armed_at", 0)
    if trigger_hit(trigger, text):
        st["soft_armed_at"] = 0
        rest = strip_trigger(trigger, text)
        if len(_norm(rest)) >= 4:
            st["armed_at"] = 0
            return "fire-hard", rest
        st["armed_at"] = now
        return "hard-arm", None
    if hard_armed:
        st["armed_at"] = 0
        age = now - hard_armed
        if age <= fast_window or (age <= arm_window and looks_like_request(text)):
            return "fire-hard-followup", text
    soft_rest = soft_trigger_rest(trigger, text, soft_list)
    if soft_rest is not None:
        if soft_rest and looks_like_request(soft_rest):
            st["soft_armed_at"] = 0
            return "fire-soft-inline", soft_rest
        if not soft_rest:
            st["soft_armed_at"] = now
            return "soft-arm", None
        st["soft_armed_at"] = 0
        return "ignore-soft-inline", None
    soft_armed = st.get("soft_armed_at", 0)
    if soft_armed:
        st["soft_armed_at"] = 0
        if now - soft_armed <= soft_arm_window and looks_like_request(text):
            return "fire-soft-followup", text
        return "ignore-soft-followup", None
    return "ignore", None
