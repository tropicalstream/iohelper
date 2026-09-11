#!/usr/bin/env python3
"""Run the crypto checks off-device, on a desktop JVM.

The pairing stack fails in ways that look like success: a PAKE that derives the
wrong key, a curve that returns the wrong point, or a certificate that is valid
DER and already expired. All three are miserable to debug through a phone, and
all three are provable here in seconds.

Order matters. Ed25519Verify runs first because everything else is built on it:
when the field arithmetic is wrong, SPAKE2 fails in a way that looks like a
protocol bug and sends you looking in the wrong place for a long time.

    python android/verify/verify.py
"""
import glob
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
SRC = os.path.join(ANDROID, "java")
OUT = os.path.join(HERE, "build")


def jdk_tool(name):
    for base in (os.environ.get("JAVA_HOME"),
                 r"C:\Program Files\Android\Android Studio\jbr",
                 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"):
        if base and os.path.isdir(os.path.join(base, "bin")):
            return os.path.join(base, "bin", name)
    return name


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    stage = os.path.join(OUT, "src")
    for pkg in ("io/github/muntashirakon/crypto",):
        shutil.copytree(os.path.join(SRC, pkg), os.path.join(stage, pkg))
    for f in ("io/github/muntashirakon/adb/StringCompat.java",
              "io/github/muntashirakon/adb/PairingAuthCtx.java",
              "com/iohelper/card/Certs.java"):
        dst = os.path.join(stage, f)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(os.path.join(SRC, f), dst)

    # android.os.Build, stubbed: StringCompat wants it and nothing else does
    stub = os.path.join(stage, "android", "os")
    os.makedirs(stub, exist_ok=True)
    shutil.copy(os.path.join(HERE, "Build.java.stub"), os.path.join(stub, "Build.java"))

    shutil.copy(os.path.join(HERE, "CryptoVerify.java"),
                os.path.join(stage, "io/github/muntashirakon/adb"))
    for f in ("Spake2Probe.java", "Ed25519Verify.java"):
        shutil.copy(os.path.join(HERE, f),
                    os.path.join(stage, "io/github/muntashirakon/crypto/spake2"))

    classes = os.path.join(OUT, "classes")
    os.makedirs(classes)
    srcs = glob.glob(os.path.join(stage, "**", "*.java"), recursive=True)
    r = subprocess.run([jdk_tool("javac"), "-nowarn", "-d", classes] + srcs,
                       capture_output=True, text=True)
    if r.returncode:
        print(r.stderr[-4000:])
        sys.exit("compile failed")

    rc = 0
    for main_class in ("io.github.muntashirakon.crypto.spake2.Ed25519Verify",
                       "io.github.muntashirakon.adb.CryptoVerify",
                       "io.github.muntashirakon.crypto.spake2.Spake2Probe"):
        print(f"\n===== {main_class.rsplit('.', 1)[-1]} =====")
        rc |= subprocess.run([jdk_tool("java"), "-cp", classes, main_class]).returncode
    sys.exit(rc)


if __name__ == "__main__":
    main()
