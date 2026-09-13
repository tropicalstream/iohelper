#!/usr/bin/env python3
"""Copy the assistant's system prompts out of the Java into PROMPTS.md.

The constants are the source of truth; this only makes them readable without
opening a 700-line file. Run it after editing any of them:

    python tools/dump-prompts.py
"""
import datetime
import io
import os
import re
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "android", "java", "com", "iohelper", "card")

# A Java string constant assembled from concatenated literals.
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
LINE_COMMENT = re.compile(r"//[^\n]*")

UNESCAPE = [("\\\"", "\""), ("\\n", "\n"), ("\\t", "\t"), ("\\\\", "\\")]


def literal(filename, name):
    """The runtime value of `static final String <name>` in `filename`."""
    text = io.open(os.path.join(SRC, filename), encoding="utf-8").read()
    m = re.search(r"String\s+" + name + r"\s*=(.*?);\s*\n", text, re.S)
    if not m:
        raise SystemExit("could not find " + name + " in " + filename)
    body = LINE_COMMENT.sub("", m.group(1))
    out = "".join(STRING_LITERAL.findall(body))
    for a, b in UNESCAPE:
        out = out.replace(a, b)
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda h: chr(int(h.group(1), 16)), out)


def wrap(text, width=78):
    """Readable paragraphs; the prompt itself is one long line in the source."""
    lines = []
    for para in text.split("\n"):
        line = ""
        for word in para.split():
            if line and len(line) + len(word) + 1 > width:
                lines.append(line)
                line = word
            else:
                line = word if not line else line + " " + word
        lines.append(line)
    return "\n".join(lines)


def main():
    voice = literal("TalkService.java", "VOICE_PROMPT")
    agent = literal("Llm.java", "AGENT")
    brief = literal("Llm.java", "BRIEF")
    resolver = literal("Llm.java", "RESOLVER")
    curator = literal("Llm.java", "CURATOR")
    stamp = datetime.date.today().isoformat()

    doc = """# The assistant's system prompts

Copied out of the source on {stamp}, so they can be read and argued with
without opening the Java. THE CONSTANTS ARE STILL THE SOURCE OF TRUTH: edit
the ones named below, rebuild, then re-run `python tools/dump-prompts.py` to
refresh this file.

There are two that matter, and they are deliberately different jobs.

---

## 1. VOICE_PROMPT - what GPT-Live itself is told

`android/java/com/iohelper/card/TalkService.java` - `TalkService.VOICE_PROMPT`

Sent once as the session's `instructions` when a live voice session starts,
and immutable for the life of that session. Deliberately small: personality,
brevity, and WHEN TO DELEGATE. It carries no procedures and no tool knowledge,
because the live model's context window is short and its job is talking, not
reasoning.

```
{voice}
```

---

## 2. AGENT - what the tool-calling backend is told

`android/java/com/iohelper/card/Llm.java` - `Llm.AGENT`

The model that actually DOES things: the one holding the tools - timers,
lists, calendar, music, radio, navigation, live search. It answers both a
crown press on the glasses and whatever the live voice delegates to it, so
the two mouths always give the same answer.

```
{agent}
```

---

## 3. BRIEF - the answer-only prompt, for when tools are off

`android/java/com/iohelper/card/Llm.java` - `Llm.BRIEF`

Used when `llm.tools` is false. Its central rule - "You CANNOT perform
actions" - is exactly what AGENT above exists to replace.

```
{brief}
```

---

## 4. RESOLVER - naming one release from a description

`android/java/com/iohelper/card/Llm.java` - `Llm.RESOLVER`

Machine-only, never shown to anyone: it turns "their most popular album" into
an artist and a title, which is then VERIFIED against Spotify before a note
is played. Nothing it names can play unless the catalogue agrees it exists.

```
{resolver}
```

---

## 5. CURATOR - building a set from a genre, mood or "top N"

`android/java/com/iohelper/card/Llm.java` - `Llm.CURATOR`

Also machine-only, and also verified track by track before anything plays.

```
{curator}
```
""".format(stamp=stamp, voice=wrap(voice), agent=wrap(agent),
           brief=wrap(brief), resolver=wrap(resolver), curator=wrap(curator))

    out = os.path.join(ROOT, "PROMPTS.md")
    fd, tmp = tempfile.mkstemp(dir=ROOT, suffix=".md")
    with io.open(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    os.replace(tmp, out)
    print("wrote " + out)
    for name, value in (("VOICE_PROMPT", voice), ("AGENT", agent), ("BRIEF", brief),
                        ("RESOLVER", resolver), ("CURATOR", curator)):
        print("  {:<13} {:>5} chars".format(name, len(value)))


if __name__ == "__main__":
    main()
