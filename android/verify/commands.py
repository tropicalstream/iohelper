#!/usr/bin/env python3
"""Run spoken phrasings through the real command parser, on a desktop JVM.

    python android/verify/commands.py [cases.tsv]

Defaults to cases.tsv beside this script. Each line is "utterance<TAB>expected",
expected being timer_at_clock | timer_duration | todo | none.

Why this exists: a timed reminder that parses as a plain to-do never fires, and
the only symptom is silence at the moment it mattered. That is unpleasant to
reproduce on a phone and trivial to prove here.
"""
import glob
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
SRC = os.path.join(ANDROID, "java")
OUT = os.path.join(HERE, "build-cmd")


def jdk_tool(name):
    for base in (os.environ.get("JAVA_HOME"),
                 r"C:\Program Files\Android\Android Studio\jbr",
                 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"):
        if base and os.path.isdir(os.path.join(base, "bin")):
            return os.path.join(base, "bin", name)
    return name


def main():
    cases = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "cases.tsv")
    if not os.path.exists(cases):
        sys.exit(f"no case file: {cases}")

    shutil.rmtree(OUT, ignore_errors=True)
    stage = os.path.join(OUT, "src")
    shutil.copytree(os.path.join(HERE, "stubs"), stage)

    # the real parser, against stubbed Context/JSONObject/Store
    dst = os.path.join(stage, "com", "iohelper", "card")
    os.makedirs(dst, exist_ok=True)
    shutil.copy(os.path.join(SRC, "com", "iohelper", "card", "Commands.java"), dst)
    shutil.copy(os.path.join(HERE, "CommandsProbe.java"), dst)

    classes = os.path.join(OUT, "classes")
    os.makedirs(classes)
    srcs = glob.glob(os.path.join(stage, "**", "*.java"), recursive=True)
    r = subprocess.run([jdk_tool("javac"), "-nowarn", "-encoding", "UTF-8",
                        "-d", classes] + srcs, capture_output=True, text=True)
    if r.returncode:
        print(r.stderr[-4000:])
        sys.exit("compile failed")

    sys.exit(subprocess.run([jdk_tool("java"), "-Dfile.encoding=UTF-8",
                             "-cp", classes,
                             "com.iohelper.card.CommandsProbe", cases]).returncode)


if __name__ == "__main__":
    main()
