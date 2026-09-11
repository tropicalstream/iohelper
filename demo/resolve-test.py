#!/usr/bin/env python3
"""Measure the Spotify resolver against real spoken requests.

Mirrors the Java in Media.searchTrack exactly, so the number this prints is the
number the phone would get. Runs BOTH the old rule (top track for the query) and
the new one (resolve an artist as an artist, skip impersonation records), because
"it's better now" is worth nothing without the before.
"""
import json
import pathlib
import re
import subprocess
import sys
import urllib.parse

KEYS = pathlib.Path(r"C:\MyCroft\keys\spotify.txt")
JOURNAL = pathlib.Path(
    r"C:\Users\tropi\.claude\projects\C--MultiverseVR"
    r"\fe024bb6-3738-4c82-9a2a-45c9fca8c8fb\subagents\workflows"
    r"\wf_8df84d93-8a6\journal.jsonl")

IMPOSTOR = re.compile(
    r"\b(karaoke|tribute|originally performed|made famous by|in the style of"
    r"|cover version|instrumental version|backing track|playback)\b", re.I)


def curl(url, token):
    return json.loads(subprocess.run(
        ["curl", "-s", url, "-H", f"Authorization: Bearer {token}"],
        capture_output=True).stdout.decode("utf-8", "replace") or "{}")


def token():
    a, b = [l.strip() for l in KEYS.read_text().splitlines() if l.strip()][:2]
    r = subprocess.run(["curl", "-s", "-X", "POST",
                        "https://accounts.spotify.com/api/token",
                        "-u", f"{a}:{b}", "-d", "grant_type=client_credentials"],
                       capture_output=True).stdout.decode("utf-8", "replace")
    return json.loads(r)["access_token"]


def clean(q):
    q = re.sub(r"^\s*(?:the\s+)?(?:artist|band|group|singer|song|track|album)\b[,:]?\s*", "", q, flags=re.I)
    q = re.sub(r"^\s*(?:some|a little|a bit of|anything by|music by|songs? by|stuff by)\b\s*", "", q, flags=re.I)
    q = re.sub(r"^\s*(?:i want to hear|i wanna hear|let'?s hear)\b\s*", "", q, flags=re.I)
    return re.sub(r"[\s,.!?]+$", "", q).strip()


def norm(s):
    return re.sub(r"[^a-z0-9]", "", re.sub(r"^the\s+", "", (s or "").lower()))


def old_way(q, tok):
    d = curl("https://api.spotify.com/v1/search?q=" + urllib.parse.quote(q)
             + "&type=track&limit=1", tok)
    items = d.get("tracks", {}).get("items", [])
    if not items:
        return None, None
    t = items[0]
    return t["name"], (t.get("artists") or [{}])[0].get("name", "")


def new_way(raw, tok):
    q = clean(raw)
    if not q:
        return None, None
    d = curl("https://api.spotify.com/v1/search?q=" + urllib.parse.quote(q)
             + "&type=artist&limit=3", tok)
    for a in d.get("artists", {}).get("items", []):
        if norm(a.get("name")) == norm(q):
            top = curl(f"https://api.spotify.com/v1/artists/{a['id']}/top-tracks?market=US", tok)
            tr = top.get("tracks") or []
            if tr:
                return tr[0]["name"], a["name"]
    d = curl("https://api.spotify.com/v1/search?q=" + urllib.parse.quote(q)
             + "&type=track&limit=10", tok)
    for t in d.get("tracks", {}).get("items", []):
        artist = (t.get("artists") or [{}])[0].get("name", "")
        if IMPOSTOR.search(f"{t['name']} {artist}"):
            continue
        return t["name"], artist
    return None, None


def main():
    cases, seen = [], set()
    for line in JOURNAL.open(encoding="utf-8"):
        try:
            o = json.loads(line)
        except Exception:
            continue
        if o.get("type") != "result" or not isinstance(o.get("result"), dict):
            continue
        for c in o["result"].get("cases", []):
            u = (c.get("utterance") or "").strip()
            if u and u.lower() not in seen and c.get("expectArtist"):
                seen.add(u.lower())
                cases.append(c)
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else len(cases)
    cases = cases[:limit]
    tok = token()

    old_hits = new_hits = 0
    regressions = []
    for c in cases:
        want = norm(c["expectArtist"])
        _, oa = old_way(c["utterance"], tok)
        nt, na = new_way(c["utterance"], tok)
        ok_old = norm(oa) == want
        ok_new = norm(na) == want
        old_hits += ok_old
        new_hits += ok_new
        if ok_old and not ok_new:
            regressions.append((c["utterance"], c["expectArtist"], na))
        if not ok_new:
            print(f"  MISS  {c['utterance'][:44]:<46} want {c['expectArtist'][:22]:<24} got {na}")
    n = len(cases)
    print(f"\nold rule (top track):      {old_hits}/{n}  ({100*old_hits//max(n,1)}%)")
    print(f"new rule (artist-aware):   {new_hits}/{n}  ({100*new_hits//max(n,1)}%)")
    if regressions:
        print(f"\nregressions ({len(regressions)}):")
        for u, w, g in regressions:
            print(f"  {u[:50]}  want {w}  got {g}")


if __name__ == "__main__":
    main()
