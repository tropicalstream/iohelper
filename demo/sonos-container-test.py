#!/usr/bin/env python3
"""Prove the Sonos container URI format before writing it into the app.

Albums and playlists are not tracks: different URI prefix, different DIDL class.
A wrong combination is accepted by the HTTP layer and then silently does nothing,
which is miserable to debug through a phone. Verified here first.
"""
import html
import json
import pathlib
import subprocess
import sys
import urllib.parse

IP = "192.168.1.36"          # Bedroom Sonos
SID, SN = "12", "20"
TOKEN = "SA_RINCON3079_X_#Svc3079-0-Token"
AV = "urn:schemas-upnp-org:service:AVTransport:1"
KEYS = pathlib.Path(r"C:\MyCroft\keys\spotify.txt")

PREFIX = {"album": "1004206c", "playlist": "1006206c"}
CLASS = {"album": "object.container.album.musicAlbum",
         "playlist": "object.container.playlistContainer"}


def spotify_token():
    a, b = [l.strip() for l in KEYS.read_text().splitlines() if l.strip()][:2]
    r = subprocess.run(["curl", "-s", "-X", "POST",
                        "https://accounts.spotify.com/api/token",
                        "-u", f"{a}:{b}", "-d", "grant_type=client_credentials"],
                       capture_output=True).stdout.decode("utf-8", "replace")
    return json.loads(r)["access_token"]


def find(kind, query, tok):
    url = ("https://api.spotify.com/v1/search?q=" + urllib.parse.quote(query)
           + f"&type={kind}&limit=5")
    d = json.loads(subprocess.run(["curl", "-s", url, "-H", f"Authorization: Bearer {tok}"],
                                  capture_output=True).stdout.decode("utf-8", "replace"))
    for o in d.get(kind + "s", {}).get("items", []):
        if o:
            return o["id"], o["name"]
    return None, None


def soap(action, args, service=AV, path="/MediaRenderer/AVTransport/Control"):
    body = ('<?xml version="1.0"?><s:Envelope '
            'xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{service}"><InstanceID>0</InstanceID>{args}'
            f'</u:{action}></s:Body></s:Envelope>')
    return subprocess.run(["curl", "-s", "--max-time", "12", f"http://{IP}:1400{path}",
                           "-H", 'Content-Type: text/xml; charset="utf-8"',
                           "-H", f'SOAPAction: "{service}#{action}"',
                           "-d", body], capture_output=True).stdout.decode("utf-8", "replace")


def play_container(kind, cid, name, shuffle):
    uri = (f"x-rincon-cpcontainer:{PREFIX[kind]}spotify%3a{kind}%3a{cid}"
           f"?sid={SID}&flags=8300&sn={SN}")
    didl = ('<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
            'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" '
            'xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">'
            f'<item id="{PREFIX[kind]}spotify%3a{kind}%3a{cid}" parentID="00020000" restricted="true">'
            f'<dc:title>{html.escape(name)}</dc:title>'
            f'<upnp:class>{CLASS[kind]}</upnp:class>'
            '<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">'
            f'{TOKEN}</desc></item></DIDL-Lite>')
    soap("RemoveAllTracksFromQueue", "")
    r = soap("AddURIToQueue",
             f"<EnqueuedURI>{html.escape(uri)}</EnqueuedURI>"
             f"<EnqueuedURIMetaData>{html.escape(didl)}</EnqueuedURIMetaData>"
             "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>"
             "<EnqueueAsNext>0</EnqueueAsNext>")
    if "<errorCode>" in r:
        return f"AddURIToQueue errorCode {r.split('<errorCode>')[1].split('<')[0]}"
    # play the QUEUE, which is where the container was just expanded
    # The queue belongs to THIS speaker, so its uuid must come from the speaker
    # itself. Scraping it out of a track URI picked up a different player.
    desc = subprocess.run(["curl", "-s", "--max-time", "8",
                           f"http://{IP}:1400/xml/device_description.xml"],
                          capture_output=True).stdout.decode("utf-8", "replace")
    rincon = desc.split("<UDN>uuid:")[1].split("<")[0]
    soap("SetAVTransportURI",
         f"<CurrentURI>x-rincon-queue:{rincon}#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
    soap("SetPlayMode", f"<NewPlayMode>{'SHUFFLE_NOREPEAT' if shuffle else 'NORMAL'}</NewPlayMode>")
    p = soap("Play", "<Speed>1</Speed>")
    return "OK" if "<errorCode>" not in p else \
        f"Play errorCode {p.split('<errorCode>')[1].split('<')[0]}"


def main():
    kind = sys.argv[1] if len(sys.argv) > 1 else "album"
    query = sys.argv[2] if len(sys.argv) > 2 else "Kind of Blue Miles Davis"
    shuffle = "--shuffle" in sys.argv
    tok = spotify_token()
    cid, name = find(kind, query, tok)
    if not cid:
        sys.exit(f"no {kind} found for {query!r}")
    print(f"{kind}: {name}  ({cid})")
    print("result:", play_container(kind, cid, name, shuffle))


if __name__ == "__main__":
    main()
