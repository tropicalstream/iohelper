"""Render demo/video.html to an MP4 by driving headless Chrome over CDP.

Chrome is the renderer because the composition is the same HTML/CSS as the
published page - so the film and the artifact cannot drift apart.

video.html exposes window.__seek(t), which paints a frame purely as a function
of time, with no timers anywhere. That makes every frame reproducible and lets
this script walk the timeline at a fixed step instead of screen-recording a
page in real time.

PNG frames go straight down ffmpeg's stdin rather than to disk, so a two
thousand frame render never materialises as a gigabyte of files.

    python render.py
"""
import asyncio
import base64
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

import websockets

HERE = pathlib.Path(__file__).resolve().parent
SCRATCH = pathlib.Path(
    r"C:\Users\tropi\AppData\Local\Temp\claude\C--MultiverseVR"
    r"\fe024bb6-3738-4c82-9a2a-45c9fca8c8fb\scratchpad"
)
FFMPEG = (r"C:\Users\tropi\AppData\Local\Microsoft\WinGet\Packages"
          r"\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
          r"\ffmpeg-7.0.2-full_build\bin\ffmpeg.exe")
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

def _arg(flag, default):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default


# Parameterised so the short demo and the full feature reel share one renderer.
PAGE = HERE / _arg("--page", "video.html")
BED = SCRATCH / "music" / _arg("--audio", "bed_upbeat.mp3")
OUT = HERE / _arg("--out", "jarvis-on-glass.mp4")
# Render to a scratch name and publish only when finished. Writing straight to
# the delivered path meant opening it mid-render gave "moov atom not found":
# ffmpeg writes the index atom LAST, so the file is unreadable until the end,
# and +faststart rewrites the whole thing after that.
TMP_OUT = OUT.with_suffix(".partial.mp4")
SAMPLES = SCRATCH / "frames"

FPS = 25
CSS_W, CSS_H = 1280, 720
SCALE = 1.5                      # -> 1920x1080 output, layout still in 1280x720
PORT = 9333

# Whichever track the bed was cut from; the credit must match the audio.
CREDIT = ('Music: <b>&ldquo;' + _arg("--track", "EOTR1") + '&rdquo; by EAR OF THE RAT</b>, '
          'from <i>6 of 1</i> &middot; Creative Commons Attribution 3.0 &middot; archive.org')


class CDP:
    """The smallest DevTools client that does the job."""

    def __init__(self, ws):
        self.ws = ws
        self.n = 0

    async def send(self, method, **params):
        self.n += 1
        mid = self.n
        await self.ws.send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(await self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})
            # events are not interesting here; drop them


async def ws_url():
    """Chrome writes its endpoint list to /json; poll until it answers."""
    import urllib.request
    for _ in range(120):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json") as r:
                for t in json.load(r):
                    if t.get("type") == "page":
                        return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        await asyncio.sleep(0.25)
    raise RuntimeError("Chrome never exposed a debug target")


async def main():
    if not BED.exists():
        sys.exit(f"missing audio bed: {BED}")
    SAMPLES.mkdir(parents=True, exist_ok=True)

    profile = SCRATCH / "chrome-profile"
    if profile.exists():
        shutil.rmtree(profile, ignore_errors=True)

    chrome = subprocess.Popen([
        CHROME, "--headless=new", f"--remote-debugging-port={PORT}",
        f"--user-data-dir={profile}", "--no-first-run", "--no-default-browser-check",
        "--hide-scrollbars", "--disable-gpu", "--force-color-profile=srgb",
        "--font-render-hinting=none", "--disable-features=DefaultPassthroughCommandDecoder",
        "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    try:
        url = await ws_url()
        async with websockets.connect(url, max_size=64 * 1024 * 1024) as ws:
            c = CDP(ws)
            await c.send("Page.enable")
            await c.send("Runtime.enable")
            await c.send("Emulation.setDeviceMetricsOverride",
                         width=CSS_W, height=CSS_H,
                         deviceScaleFactor=SCALE, mobile=False)
            # the credit line has to exist before the page script reads it
            await c.send("Page.addScriptToEvaluateOnNewDocument",
                         source=f"window.__CREDIT__ = {json.dumps(CREDIT)};")

            await c.send("Page.navigate", url=PAGE.as_uri())
            await asyncio.sleep(2.5)                       # let CSS + fonts arrive
            await c.send("Runtime.evaluate",
                         expression="window.__ready", awaitPromise=True)
            await asyncio.sleep(0.6)

            total = (await c.send("Runtime.evaluate", expression="window.__total"))
            total = float(total["result"]["value"])
            frames = int(round(total * FPS))
            print(f"timeline {total:.2f}s -> {frames} frames @ {FPS}fps "
                  f"({int(CSS_W*SCALE)}x{int(CSS_H*SCALE)})", flush=True)

            if "--probe" in sys.argv:
                # A handful of stills to check the phosphor stack and fonts
                # before spending several minutes on the full walk.
                for k, t in enumerate([1.0, 13.0, 22.0, 38.0, 46.0, 58.0, 70.0, 82.0]):
                    await c.send("Runtime.evaluate", expression=f"window.__seek({t:.4f})")
                    shot = await c.send("Page.captureScreenshot",
                                        format="png", fromSurface=True)
                    p = SAMPLES / f"probe_{k:02d}_t{int(t):02d}.png"
                    p.write_bytes(base64.b64decode(shot["data"]))
                    print("probe", p.name, flush=True)
                return

            ff = subprocess.Popen([
                FFMPEG, "-y", "-loglevel", "error",
                "-f", "image2pipe", "-framerate", str(FPS), "-i", "-",
                "-i", str(BED),
                "-c:v", "libx264", "-preset", "medium", "-crf", "18",
                "-pix_fmt", "yuv420p", "-r", str(FPS),
                "-c:a", "aac", "-b:a", "192k",
                "-shortest", "-movflags", "+faststart", str(TMP_OUT),
            ], stdin=subprocess.PIPE)

            t0 = time.time()
            for i in range(frames):
                t = i / FPS
                await c.send("Runtime.evaluate", expression=f"window.__seek({t:.4f})")
                shot = await c.send("Page.captureScreenshot",
                                    format="png", fromSurface=True, captureBeyondViewport=False)
                png = base64.b64decode(shot["data"])
                ff.stdin.write(png)
                if i in (0, int(frames * 0.18), int(frames * 0.42),
                         int(frames * 0.62), int(frames * 0.86), frames - 1):
                    (SAMPLES / f"f{i:05d}.png").write_bytes(png)
                if i % 100 == 0:
                    el = time.time() - t0
                    rate = (i + 1) / max(el, 1e-6)
                    print(f"  {i:5d}/{frames}  {rate:5.1f} fps  "
                          f"eta {(frames - i) / max(rate, 1e-6):5.0f}s", flush=True)
            ff.stdin.close()
            ff.wait()
            print(f"captured in {time.time() - t0:.0f}s", flush=True)
    finally:
        chrome.terminate()
        try:
            chrome.wait(timeout=10)
        except Exception:
            chrome.kill()

    if TMP_OUT.exists():
        # Decode the whole thing before publishing it. A file that merely EXISTS
        # can still be truncated, and the only place to catch that is here -
        # not in the hands of whoever opens it.
        probe = subprocess.run([FFMPEG, "-v", "error", "-i", str(TMP_OUT),
                                "-f", "null", "-"], capture_output=True, text=True)
        if probe.returncode != 0:
            sys.exit("render did not decode cleanly:\n" + probe.stderr[-800:])
        if OUT.exists():
            OUT.unlink()
        TMP_OUT.rename(OUT)
        print(f"\nwrote {OUT}  ({OUT.stat().st_size / 1e6:.1f} MB, decodes clean)")
        print(f"sample frames in {SAMPLES}")
    else:
        sys.exit("ffmpeg produced no file")


if __name__ == "__main__":
    asyncio.run(main())
