"""Inline the CC BY music bed into the demo page.

The audio has to travel with the page - an Artifact's CSP blocks media loaded
from anywhere off-origin - so it is embedded as a data URI. Kept as a build
step so index.html stays editable by hand.

    python build.py   ->  index.built.html
"""
import base64
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / "index.html"
OUT = HERE / "index.built.html"
MP3 = pathlib.Path(
    r"C:\Users\tropi\AppData\Local\Temp\claude\C--MultiverseVR"
    r"\fe024bb6-3738-4c82-9a2a-45c9fca8c8fb\scratchpad\music\bed_upbeat.mp3"
)

raw = MP3.read_bytes()
uri = "data:audio/mpeg;base64," + base64.b64encode(raw).decode("ascii")

html = SRC.read_text(encoding="utf-8")
assert "__AUDIO_DATA_URI__" in html, "placeholder missing from index.html"
OUT.write_text(html.replace("__AUDIO_DATA_URI__", uri), encoding="utf-8")

print(f"mp3        {len(raw):,} bytes")
print(f"data uri   {len(uri):,} chars")
print(f"wrote      {OUT}  ({OUT.stat().st_size:,} bytes)")
