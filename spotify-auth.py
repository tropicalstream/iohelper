#!/usr/bin/env python3
r"""One-time Spotify user login, to get a refresh token.

WHY THIS EXISTS. The app already has a client id + secret, and that is enough to
SEARCH the catalog - which is why "play bob dylan" works. It is not enough to
choose a speaker. Picking a Connect device is PUT /v1/me/player, a *user*
endpoint: it needs a token issued for your account, not for the app. That token
only comes from the Authorization Code flow, which requires you to log in once
in a browser. After this runs, the refresh token is stored and no login is ever
needed again.

WHAT IT GETS YOU. With a user token the assistant can list your Spotify Connect
devices, move playback to the Sonos, and start a track directly - which also
removes the "open the URI then press play" workaround the app uses today.

BEFORE RUNNING: add this redirect URI to your app at
https://developer.spotify.com/dashboard -> your app -> Settings -> Redirect URIs

    http://127.0.0.1:8888/callback

Spotify requires https or a loopback IP, and no longer accepts "localhost" -
it must be the literal 127.0.0.1.

    python spotify-auth.py            # log in, save the refresh token
    python spotify-auth.py --push     # ...and send it to the phone
"""
import base64
import os
import http.server
import json
import pathlib
import secrets
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import urllib.request
import webbrowser

KEYS = pathlib.Path(r"C:\MyCroft\keys\spotify.txt")
OUT = pathlib.Path(__file__).resolve().parent / "spotify-refresh.json"
REDIRECT = "http://127.0.0.1:8888/callback"
PORT = 8888
SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"

ADB = r"C:\platform-tools\adb.exe"
DEVICE = os.environ.get("IOHELPER_DEVICE", "")
# -s <serial> only when one is actually set; adb picks the sole connected
# device on its own otherwise, so an unset IOHELPER_DEVICE just works rather
# than failing on `adb -s "" ...`.
ADB_TARGET = [ADB] + (["-s", DEVICE] if DEVICE else [])
PKG = "com.iohelper.card"

_result = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        _result.update({k: v[0] for k, v in q.items()})
        ok = "code" in _result
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(
            (("<h2>Authorized.</h2><p>You can close this tab and go back to the terminal.</p>")
             if ok else
             ("<h2>Authorization failed.</h2><pre>" + json.dumps(_result) + "</pre>"))
            .encode("utf-8"))

    def log_message(self, *a):
        pass                                    # keep the console quiet


def main():
    if not KEYS.exists():
        sys.exit(f"no credentials at {KEYS}")
    lines = [l.strip() for l in KEYS.read_text().splitlines() if l.strip()]
    client_id, client_secret = lines[0], lines[1]

    state = secrets.token_urlsafe(16)
    auth_url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT,
        "scope": SCOPES,
        "state": state,
        "show_dialog": "true",
    })

    server = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.handle_request, daemon=True).start()

    print("Opening your browser to log in to Spotify...")
    print("If it does not open, paste this into a browser:\n")
    print(auth_url + "\n")
    try:
        webbrowser.open(auth_url)
    except Exception:
        pass

    print(f"Waiting for the redirect to {REDIRECT} ...")
    for _ in range(3000):                       # ~5 minutes
        if _result:
            break
        threading.Event().wait(0.1)
    server.server_close()

    if "code" not in _result:
        sys.exit("no authorization code received: " + json.dumps(_result))
    if _result.get("state") != state:
        sys.exit("state mismatch - refusing the response")

    basic = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    body = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": _result["code"],
        "redirect_uri": REDIRECT,
    }).encode()
    req = urllib.request.Request(
        "https://accounts.spotify.com/api/token", data=body,
        headers={"Authorization": "Basic " + basic,
                 "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=30) as r:
        tok = json.load(r)

    refresh = tok.get("refresh_token")
    if not refresh:
        sys.exit("no refresh token in the response: " + json.dumps(tok)[:300])

    OUT.write_text(json.dumps({"media.spotify_refresh_token": refresh}), encoding="utf-8")
    print(f"\nrefresh token saved to {OUT}  (<{len(refresh)} chars>, not printed)")
    print("scopes granted:", tok.get("scope", "?"))

    # Sanity check: can we actually see the Connect devices now?
    access = tok["access_token"]
    dev_req = urllib.request.Request(
        "https://api.spotify.com/v1/me/player/devices",
        headers={"Authorization": "Bearer " + access})
    try:
        with urllib.request.urlopen(dev_req, timeout=20) as r:
            devices = json.load(r).get("devices", [])
        if devices:
            print("\nSpotify Connect devices visible to your account:")
            for d in devices:
                print(f"  - {d.get('name')}  ({d.get('type')})"
                      + ("  [active]" if d.get("is_active") else ""))
        else:
            print("\nNo Connect devices are awake right now. Play something to the "
                  "Sonos from the Spotify app once, then re-check - a sleeping "
                  "speaker does not appear in this list.")
    except Exception as e:
        print("could not list devices:", e)

    if "--push" in sys.argv:
        fd, tmp = tempfile.mkstemp(suffix=".json")
        pathlib.Path(tmp).write_text(OUT.read_text(), encoding="utf-8")
        subprocess.run(ADB_TARGET + ["push", tmp,
                        "/data/local/tmp/iohelper-config.json"], check=False)
        subprocess.run(ADB_TARGET + ["shell", "am", "broadcast",
                        "-n", f"{PKG}/.ShowReceiver",
                        "-a", f"{PKG}.CONFIGFILE"], check=False,
                       capture_output=True)
        pathlib.Path(tmp).unlink(missing_ok=True)
        print("\npushed to the phone via the config-file path (never touches logcat)")


if __name__ == "__main__":
    main()
