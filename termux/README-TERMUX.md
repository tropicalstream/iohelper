# Running iohelper on the phone (no PC)

## Why it is built this way

An ordinary Android app — Termux included — can only read **its own** logcat.
Measured on this phone: with `READ_LOGS` granted, the app saw **51** lines while
adb saw **4154** in the same buffer. `logd` filters by UID, so tailing the
glasses transcripts from inside an app is impossible.

What *is* possible: the phone connects to **its own `adbd`** over loopback
(`127.0.0.1:5555`). Anything speaking adb to it runs as the **shell** UID, which
does see the whole buffer. So the phone runs the same code the PC ran, with the
transport swapped from adb-over-Tailscale to adb-over-loopback.

## Install (once)

1. **Install Termux from F-Droid** — <https://f-droid.org/packages/com.termux/>.
   The Play Store build is deprecated and will not work.
2. On the PC, with the phone connected, push the app and arm wireless adb:
   ```bash
   adb push termux /sdcard/Download/iohelper
   adb tcpip 5555          # only needed if adbd is not already on 5555
   ```
3. In Termux:
   ```bash
   bash /sdcard/Download/iohelper/setup.sh
   ```
   Accept the storage prompt and the **"Allow debugging?"** dialog (tick
   *always allow*) when they appear.
4. Open **<http://127.0.0.1:8765/>** in the phone's browser, paste your **Groq**
   and **SerpApi** keys, press **Save**. Keys are deliberately not shipped in
   the bundle.

## Daily use

```bash
~/iohelper/run.sh
```

Click the crown on the glasses, ask, and the Jarvis card follows RayNeo's own
answer. The portal is at <http://127.0.0.1:8765/> on the phone.

## After a reboot — the one wart

`adb tcpip 5555` **does not survive a reboot**. Until it is re-armed, iohelper
cannot reach logcat. Two options:

* **USB once**: plug into any PC and run `adb tcpip 5555`.
* **Wireless debugging** (Developer options): survives reboots, but uses a
  **random port** each session. Read the port from the Wireless debugging screen
  and connect to it:
  ```bash
  adb connect 127.0.0.1:<port>
  ```
  then set `device.serial` to the same `127.0.0.1:<port>` in the portal.

## Keeping it alive

* `termux-wake-lock` (the scripts do this) stops Android dozing the process.
* Exempt Termux from battery optimisation in Android settings, or Samsung will
  kill it within the hour.
* For start-on-boot, install the **Termux:Boot** addon and drop `run.sh` into
  `~/.termux/boot/`.

## What differs from the PC build

* **Claude via OAuth is unavailable** — it shells out to the Claude Code CLI,
  which has no Android build. Groq, Gemini and any OpenAI-compatible endpoint
  work normally.
* Everything else is identical: the same wake gate, SerpApi router, display
  limits, card deferral, timers, to-dos and calendar.
