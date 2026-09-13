# Jarvis Talk

A separate, one-job app whose only purpose is to be its own icon: bind it to
a hardware shortcut, and pressing that shortcut toggles iohelper's live GPT
voice session.

## Why a second package at all

Samsung's own side-button assignment (**Settings → Advanced features → Side
button → Double press → Apps**) is a plain picker of installed apps - but it
turns out to enumerate **one entry per installed package**, not per launcher
activity within a package. A second `Activity` added directly to iohelper
showed up fine as its own icon on the **home screen** (Android's ordinary
multi-launcher-activity mechanism, the same trick a "Lite" edition of an app
uses), but never appeared in that specific Samsung picker - confirmed by
opening it and reading its contents directly. A genuinely separate package is
the only thing that picker will list on its own.

## What it actually does

The whole app is one `Activity` (`LaunchActivity`), no permissions, no
network, no UI:

```java
Intent i = new Intent("com.iohelper.card.TALK");
i.setClassName("com.iohelper.card", "com.iohelper.card.ShowReceiver");
sendBroadcast(i);
finish();
```

`com.iohelper.card.TALK` is a broadcast action iohelper's `ShowReceiver`
already exposes (exported, no permission required) - the very thing this
project already used to test the talk toggle from adb. This app is nothing
more than a tap-free way to fire that same broadcast. If iohelper ever changes
what that toggle does, this app needs no changes at all.

## Building and installing

```bash
python android-talk/build.py --install
```

Needs iohelper (`com.iohelper.card`) already installed - this app is inert
without it. Uses the exact same SDK-discovery, aapt2/javac/d8/apksigner
toolchain as `android/build.py`; see that file's header for the tool
requirements.

## Wiring it to the side button

This app cannot bind itself to the side button - that is a system setting,
set by hand:

1. Settings → search **"side button"** → **Side button**
2. **Double press** → **Apps** → the gear icon
3. Choose **Jarvis Talk**

Double-pressing the side button then starts or ends a live voice
conversation, screen on or off, with iohelper's tool-calling brain behind it.

Long press is a dead end for this: on this build it only offers registered
digital-assistant apps (Google, Bixby) - no generic "open app" option, so
neither this app nor Tasker can be bound there without registering as a
system assistant, a much larger undertaking. Samsung's own Modes and
Routines automation was also checked directly (searched its trigger list for
"side button" and "button") and has no side-button condition at all on this
build.
