"""Live web/search context via SerpApi (ported from the proven Hermes module).

Routes an utterance to one engine - google (weather/facts/sports/finance),
google_maps_directions (commute), google (events pack), google_news,
google_local - and returns a short block the assistant prepends to the LLM
prompt, so the model answers from real data. Key/location come from the
iohelper config (portal -> Search); SERPAPI_KEY in the environment overrides.
Only live-data questions search (quota), and never a personal-calendar one.
"""

import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

from .config import CFG

ENDPOINT = "https://serpapi.com/search.json"
ACCOUNT = "https://serpapi.com/account.json"

# Python 3.13 turned on VERIFY_X509_STRICT by default, which rejects some HTTPS-
# interception roots (Avast on this machine). Clear only that flag: the chain is
# still fully verified against the OS trust store and the hostname is checked.
_SSL_CTX = ssl.create_default_context()
_SSL_CTX.verify_flags &= ~ssl.VERIFY_X509_STRICT


# ---- config ------------------------------------------------------------------
def serpapi_key():
    return CFG.get("search.serpapi_key") or None


def search_location():
    return CFG.get("search.location") or None


def available():
    return bool(serpapi_key())


# ---- low-level call --------------------------------------------------------
LAST_ERROR = None      # why the most recent search produced nothing (for the log)


def _call(params, timeout=20, _retry=True):
    """One SerpApi request. A cold scrape can exceed the timeout while SerpApi
    keeps finishing it server-side and caches the result - so on a timeout we
    retry ONCE with a short timeout, which is then served from that cache in
    well under a second. (Measured: a first-time weather query took >10 s and
    the LLM answered blind; the same query returned instantly right after.)"""
    global LAST_ERROR
    key = serpapi_key()
    if not key:
        LAST_ERROR = "no SerpApi key"
        return {"error": LAST_ERROR}
    q = {**params, "api_key": key, "output": "json"}
    url = ENDPOINT + "?" + urllib.parse.urlencode(q)
    req = urllib.request.Request(url, headers={"User-Agent": "iohelper"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as resp:
            d = json.load(resp)
            # HTTP 200 can still carry an error in the body, e.g. the maps
            # engine's "Google hasn't returned any results for this query."
            LAST_ERROR = str(d["error"]) if isinstance(d, dict) and d.get("error") else None
            return d
    except urllib.error.HTTPError as e:          # SerpApi returns JSON errors on 4xx
        try:
            d = json.load(e)
        except Exception:
            d = {"error": f"HTTP {e.code}"}
        LAST_ERROR = str(d.get("error") or f"HTTP {e.code}")
        return d
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        timed_out = isinstance(e, TimeoutError) or "timed out" in str(e).lower() \
            or isinstance(getattr(e, "reason", None), TimeoutError)
        if timed_out and _retry:
            return _call(params, timeout=8, _retry=False)
        LAST_ERROR = f"{'timeout' if timed_out else 'network'}: {getattr(e, 'reason', e)}"
        return {"error": LAST_ERROR}
    except json.JSONDecodeError as e:
        LAST_ERROR = f"bad JSON: {e}"
        return {"error": LAST_ERROR}


def _call_localized(params, location, timeout=10):
    """_call with a `location` param, degrading gracefully. SerpApi's location
    parameter only accepts its canonical city/region strings — a precise address
    like 'Main Street, Springfield, Illinois, United States' returns an
    'Unsupported ... location' error. Trim leading comma-segments (street →
    city → state) until one is accepted, then fall back to no location at all.
    (Directions are unaffected: start_addr/end_addr geocode full addresses.)"""
    loc = (location or "").strip()
    while loc:
        d = _call({**params, "location": loc}, timeout)
        err = str(d.get("error") or "").lower()
        if not ("unsupported" in err and "location" in err):
            return d
        loc = loc.split(",", 1)[1].strip() if "," in loc else ""
    return _call(params, timeout)


def _acct_call(timeout=6):
    key = serpapi_key()
    if not key:
        return {"error": "no SERPAPI_KEY"}
    url = ACCOUNT + "?" + urllib.parse.urlencode({"api_key": key})
    try:
        with urllib.request.urlopen(url, timeout=timeout, context=_SSL_CTX) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)
        except Exception:
            return {"error": f"HTTP {e.code}"}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as e:
        return {"error": str(e)}


# ---- per-engine extractors (concise, glasses-sized) ------------------------
_BULK = {"thumbnail", "thumbnails", "images", "image", "serpapi_link", "link",
         "links", "favicon", "icon", "source_logo", "logo", "photos"}


def _prune(obj, depth=0):
    """Drop bulky/irrelevant keys so the digest stays small and readable."""
    if depth > 3:
        return "…"
    if isinstance(obj, dict):
        return {k: _prune(v, depth + 1) for k, v in obj.items()
                if k not in _BULK and v not in (None, "", [], {})}
    if isinstance(obj, list):
        return [_prune(v, depth + 1) for v in obj[:4]]
    return obj


def web_answer(query, location=None):
    """google engine — the workhorse (weather, facts, sports, finance, defs)."""
    p = {"engine": "google", "q": query, "hl": "en", "gl": "us"}
    d = _call_localized(p, location) if location else _call(p)
    if d.get("error"):
        return None
    parts = []
    # weather and finance arrive INSIDE answer_box (type=weather_result /
    # finance_results), not as top-level keys — answer_box first captures both.
    for key in ("answer_box", "knowledge_graph", "sports_results"):
        v = d.get(key)
        if v:
            parts.append(f"{key}: {json.dumps(_prune(v), ensure_ascii=False)[:420]}")
    for r in (d.get("organic_results") or [])[:3]:
        t, s = r.get("title"), r.get("snippet")
        if t and s:
            parts.append(f"- {t}: {s}")
    return "\n".join(parts)[:750] or None


def directions(dest, origin, mode="best"):
    """google_maps_directions — one-line trip time."""
    modes = {"drive": 0, "driving": 0, "car": 0, "bike": 1, "biking": 1,
             "cycling": 1, "walk": 2, "walking": 2, "transit": 3, "bus": 3,
             "bart": 3, "train": 3, "subway": 3, "best": 6}
    tm = modes.get(mode, 6)
    d = _call({"engine": "google_maps_directions",
               "start_addr": origin, "end_addr": dest, "travel_mode": tm})
    if d.get("error"):
        return None

    def _fmt(dur, dist, via=""):
        return (f"{origin} → {dest}: {dur}{f' ({dist})' if dist else ''}"
                + (f" via {via}" if via else "") + f" by {mode}")

    # Duration/distance may sit directly on a directions[] row or nested one level
    # under directions[].trips[] — SerpApi's payload varies. Handle both. Rows are
    # route ALTERNATIVES (fastest first, each with a `via` road) — report the best
    # plus one alternative so "fastest route" answers name the actual roads.
    lines = []
    for row in (d.get("directions") or []):
        if row.get("formatted_duration"):
            lines.append(_fmt(row["formatted_duration"],
                              row.get("formatted_distance", ""), row.get("via", "")))
        else:
            for trip in (row.get("trips") or []):
                if trip.get("formatted_duration"):
                    lines.append(_fmt(trip["formatted_duration"],
                                      trip.get("formatted_distance", ""), trip.get("via", "")))
                    break
        if len(lines) == 2:
            break
    if lines:
        return lines[0] + (f"  (alt: {lines[1].split(': ', 1)[-1]})" if len(lines) > 1 else "")
    for row in (d.get("durations") or []):
        if row.get("formatted_duration"):
            return _fmt(row["formatted_duration"], row.get("formatted_distance", ""))
    return None


def events(query, location=None, when=None):
    """Events via the plain google engine's events pack. (SerpApi's dedicated
    google_events engine returns 'Unsupported search engine' now that Google
    has retired its events vertical; the main SERP still shows an events pack,
    surfaced as events_results.) `when` is folded into the query text."""
    when_q = ("" if not when
              else f"this {when}" if when in ("week", "weekend", "month") else when)
    q = " ".join(x for x in [query or "events", when_q] if x)
    p = {"engine": "google", "q": q, "hl": "en", "gl": "us"}
    d = _call_localized(p, location) if location else _call(p)
    if d.get("error"):
        return None
    out = []
    for e in (d.get("events_results") or [])[:5]:
        if not e.get("title"):
            continue
        date = e.get("date")                    # dict {'when': ...} or plain string
        when_s = date.get("when", "") if isinstance(date, dict) else (date or "")
        addr = e.get("address")
        where = (e.get("venue") or {}).get("name") if isinstance(e.get("venue"), dict) else ""
        if not where:
            where = addr[0] if isinstance(addr, list) and addr else (addr or "")
        kind = e.get("type", "")
        out.append(f"- {e['title']}" + (f" ({kind})" if kind else "")
                   + (f" — {when_s}" if when_s else "") + (f" @ {where}" if where else ""))
    if not out:                                 # no events pack — organic fallback
        for r in (d.get("organic_results") or [])[:3]:
            if r.get("title") and r.get("snippet"):
                out.append(f"- {r['title']}: {r['snippet']}")
    return "\n".join(out) or None


def news(query):
    """google_news — headlines."""
    d = _call({"engine": "google_news", "q": query, "gl": "us", "hl": "en"})
    if d.get("error"):
        return None
    out = []
    for n in (d.get("news_results") or [])[:5]:
        title = n.get("title") or (n.get("highlight") or {}).get("title")
        if not title:
            continue                       # story-cluster item with no flat title
        src = (n.get("source") or {}).get("name", "")
        out.append(f"- {title}" + (f" ({src})" if src else ""))
    return "\n".join(out) or None


def local(query, location=None):
    """google_local — nearby places."""
    p = {"engine": "google_local", "q": query, "hl": "en", "gl": "us"}
    d = _call_localized(p, location) if location else _call(p)
    if d.get("error"):
        return None
    out = []
    for r in (d.get("local_results") or [])[:5]:
        if not r.get("title"):
            continue
        rating = r.get("rating")
        meta = " ".join(str(x) for x in [f"{rating}★" if rating else "",
                                         r.get("type") or "", r.get("address") or ""] if x)
        out.append(f"- {r['title']} {meta}".rstrip())
    return "\n".join(out) or None


def account():
    """Remaining quota. Free of charge, does not use a search credit."""
    d = _acct_call()
    if d.get("error"):
        return d
    return {k: d.get(k) for k in ("plan_name", "searches_per_month",
            "this_month_usage", "total_searches_left", "plan_searches_left",
            "account_rate_limit_per_hour") if k in d}


# ---- intent routing --------------------------------------------------------
def _origin_dest_mode(utterance):
    """Parse (origin, dest, mode) from a commute utterance. origin is None unless
    stated explicitly ('from A to B'); the caller falls back to SEARCH_LOCATION."""
    u = utterance
    # travel mode, then strip a trailing "... by <mode>" so it can't leak into dest
    low = u.lower()
    mode = "best"
    for k in ("driving", "drive", "car", "transit", "bart", "subway", "bus",
              "train", "walking", "walk", "cycling", "biking", "bike", "traffic"):
        if re.search(rf"\b{k}\b", low):
            mode = "drive" if k in ("car", "traffic") else k
            break
    u = re.sub(r"\s+by\s+(car|driving|drive|transit|bus|train|bart|subway|"
               r"walking|walk|cycling|biking|bike)\b.*$", "", u, flags=re.I)
    # explicit "from A to B"
    origin = None
    mo = re.search(r"\bfrom\s+(.+?)\s+to\b", u, re.I)
    if mo:
        origin = mo.group(1).strip(" ?.,")
    # destination = text after the LAST "to" (so "how long to get to X" -> "X")
    parts = re.split(r"\bto\b", u, flags=re.I)
    dest = parts[-1].strip(" ?.,") if len(parts) > 1 else None
    return origin, dest, mode


def _events_when(low):
    for w in ("today", "tomorrow", "weekend", "week", "month"):
        if re.search(rf"\b{w}\b", low):   # all valid SerpApi htichips date: filters
            return w
    return None


def classify(utterance):
    """Return the engine intent for an utterance, or None to skip searching.
    'weather' and 'web' both use the google engine. None means "let the LLM
    answer from its own knowledge (or the injected calendar)" — which saves
    quota on chit-chat and on questions that don't need live data."""
    low = utterance.lower().strip()
    # Personal calendar/schedule: already answered from the injected calendar, so
    # never spend a search on it. Covers "my events", "the schedule", and the
    # common first-person phrasings that carry time words ("do I have anything
    # today", "am I free this weekend", "what am I doing tonight").
    if re.search(r"\b(my|our)\b.{0,20}\b(schedule|calendar|agenda|events?|"
                 r"meetings?|appointments?|plans?)\b", low) \
            or re.search(r"\b(my|the) (schedule|calendar|agenda)\b", low) \
            or re.search(r"\bdo i have\b|\bam i (free|busy|available|around)\b|"
                         r"\bwhat do i have\b|\bwhat am i doing\b|"
                         r"\banything (on|planned|going on)\b", low):
        return None
    if re.search(r"\b(directions?|routes?|(fastest|quickest|best) way|"
                 r"how (long|far)|drive|driving|commute|traffic|"
                 r"travel time|bike|biking|walk|walking|transit|bart|bus)\b", low) \
            and re.search(r"\bto\b", low):
        return "directions"
    if re.search(r"\b(concerts?|shows?|things to do|what'?s (happening|on|going on))\b", low) \
            or re.search(r"\bevents?\b", low):
        return "events"
    if re.search(r"\b(news|headlines?|latest on|breaking)\b", low):
        return "news"
    if re.search(r"\b(near me|nearby|nearest|closest|around here)\b", low):
        return "local"
    if re.search(r"\b(weather|forecast|temperature|rain|snow|humidity|"
                 r"wind|umbrella|how (hot|cold|warm))\b", low):
        return "weather"
    # Store hours (pure live data): "when does Target close", "is the pharmacy open".
    if re.search(r"\bwhen (do|does|is|are)\b.{0,30}\b(open|close|closes|closing)\b", low) \
            or re.search(r"\bis\b.{0,30}\bopen\b", low) \
            or re.search(r"\bwhat time\b.{0,30}\b(open|close|closes)\b", low):
        return "web"
    # General google search ONLY on strong current/live cues (sports, finance,
    # market, currency, live time) — not bare time words like "today", which the
    # weather/events/directions routes already cover and which else waste quota.
    if re.search(r"\b(scores?|who won|who'?s winning|winning|standings|final score|"
                 r"price|prices|stock|stocks|shares|market cap|trading|"
                 r"exchange rate|how much is\b.{0,25}\b(worth|cost|trading)|"
                 r"time in\b|what time is it)\b", low):
        return "web"
    return None


def search_context(utterance, location=None, mode="auto"):
    """The entry point the voice loop calls. Returns a short context block to
    prepend to the prompt, or "" (no key / not a live-data query / no result)."""
    if mode == "off" or not available():
        return ""
    location = location or search_location()
    intent = "web" if mode == "always" else classify(utterance)
    if not intent:
        return ""
    try:
        if intent == "directions":
            origin, dest, m = _origin_dest_mode(utterance)
            origin = origin or location
            res = None
            if dest and origin:
                res = directions(dest, origin, m)
                # The maps engine's scrape comes back "Fully empty" for some
                # perfectly ordinary destinations (measured: Oakland -> "San
                # Francisco" while "San Jose" worked). Retry with the origin's
                # region appended, then fall back to the plain google engine,
                # whose answer box usually carries the trip time anyway.
                if not res and "," not in dest and "," in origin:
                    res = directions(f"{dest}, {origin.split(',', 1)[1].strip()}", origin, m)
                if not res:
                    res = web_answer(f"driving time from {origin} to {dest} with current traffic",
                                     location)
        elif intent == "events":
            # keep the event noun ("concerts"); strip only filler + time words
            # (the time filter is re-added by events() via `when`)
            topic = re.sub(r"\b(what'?s|what are|are there|any|happening|going on|"
                           r"near me|nearby|around here|tonight|today|tomorrow|"
                           r"this|weekend|week|month)\b", " ", utterance, flags=re.I)
            res = events(re.sub(r"\s+", " ", topic).strip(" ?.,") or "events",
                         location, _events_when(utterance.lower()))
        elif intent == "news":
            topic = re.sub(r"\b(what'?s the|news|headlines?|latest on|latest|about)\b",
                           " ", utterance, flags=re.I)
            res = news(re.sub(r"\s+", " ", topic).strip() or "top stories")
        elif intent == "local":
            res = local(utterance, location)
        else:                                    # weather / web
            res = web_answer(utterance, location)
    except Exception:                            # never let search break the answer
        return ""
    if not res:
        return ""
    return f"Live search results (SerpApi — use only if relevant, be concise):\n{res}"
