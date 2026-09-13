#!/usr/bin/env python3
r"""Build + install the "Jarvis Talk" side-button shortcut.

Same no-Gradle toolchain as android/build.py, trimmed to what a one-Activity,
zero-dependency app actually needs: no AAR unpacking, no native libs, no
--label rewriting.

    python android-talk/build.py                    # build, sign
    python android-talk/build.py --install          # ...and install
    python android-talk/build.py --install --test   # ...then fire it once

See README.md for what this app is and why it exists as its own package.
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "build")
PKG = "com.iohelper.talk"


def find_sdk():
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env) and os.path.isdir(os.environ[env]):
            return os.environ[env]
    for c in (os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
              os.path.expanduser("~/Android/Sdk"),
              os.path.expanduser("~/Library/Android/sdk"), r"C:\Android\Sdk"):
        if os.path.isdir(c):
            return c
    sys.exit("Android SDK not found - set ANDROID_SDK_ROOT")


def newest(paths):
    def key(p):
        return [int(x) for x in re.findall(r"\d+", os.path.basename(p.rstrip("/\\")))] or [0]
    return sorted(paths, key=key)[-1] if paths else None


def find_jdk():
    for c in (os.environ.get("JAVA_HOME"),
              r"C:\Program Files\Android\Android Studio\jbr",
              "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
              os.path.expanduser("~/.jdks")):
        if c and os.path.isdir(os.path.join(c, "bin")):
            return c
    if shutil.which("javac"):
        return None                     # on PATH
    sys.exit("No JDK found - install Android Studio or set JAVA_HOME")


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace", **kw)
    if r.returncode != 0:
        print(" ".join(str(c) for c in cmd))
        print(r.stdout[-3000:])
        print(r.stderr[-3000:])
        sys.exit(f"failed: {os.path.basename(str(cmd[0]))}")
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--test", action="store_true", help="launch it once after installing")
    ap.add_argument("--serial", default=os.environ.get("IOHELPER_DEVICE", ""))
    ap.add_argument("--adb", default=os.environ.get("ADB", r"C:\platform-tools\adb.exe"
                                                    if os.name == "nt" else "adb"))
    a = ap.parse_args()

    sdk = find_sdk()
    bt = newest(glob.glob(os.path.join(sdk, "build-tools", "*")))
    plat = newest(glob.glob(os.path.join(sdk, "platforms", "android-*")))
    if not bt or not plat:
        sys.exit("SDK is missing build-tools or a platform")
    android_jar = os.path.join(plat, "android.jar")
    jdk = find_jdk()
    jbin = (lambda t: os.path.join(jdk, "bin", t)) if jdk else (lambda t: t)
    ext = ".exe" if os.name == "nt" else ""
    bat = ".bat" if os.name == "nt" else ""
    aapt2 = os.path.join(bt, "aapt2" + ext)
    print(f"  sdk={sdk}\n  build-tools={os.path.basename(bt)}  platform={os.path.basename(plat)}")

    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, "gen"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "classes"), exist_ok=True)

    # 1. resources
    compiled = os.path.join(OUT, "res.zip")
    run([aapt2, "compile", "--dir", os.path.join(HERE, "res"), "-o", compiled])
    unsigned = os.path.join(OUT, "unsigned.apk")
    run([aapt2, "link", "-o", unsigned, "-I", android_jar,
         "--manifest", os.path.join(HERE, "AndroidManifest.xml"),
         "--java", os.path.join(OUT, "gen"), "--min-sdk-version", "26",
         "--target-sdk-version", "34", compiled])

    # 2. java -> dex (no libs/ - this app has zero dependencies)
    skip = ("build" + os.sep,)
    srcs = [os.path.join(dp, f) for dp, _, fs in os.walk(HERE)
            for f in fs if f.endswith(".java")
            and not any(k in dp + os.sep for k in skip)]
    srcs += glob.glob(os.path.join(OUT, "gen", "**", "*.java"), recursive=True)
    run([jbin("javac"), "-source", "17", "-target", "17", "-nowarn",
         "-classpath", android_jar, "-d", os.path.join(OUT, "classes")] + srcs)
    classes = glob.glob(os.path.join(OUT, "classes", "**", "*.class"), recursive=True)
    run([os.path.join(bt, "d8" + bat), "--lib", android_jar, "--min-api", "26",
         "--output", OUT] + classes)

    # 3. package dex into the apk, align, sign
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for dex in sorted(glob.glob(os.path.join(OUT, "classes*.dex"))):
            z.write(dex, os.path.basename(dex))
    aligned = os.path.join(OUT, "aligned.apk")
    run([os.path.join(bt, "zipalign" + ext), "-p", "-f", "4", unsigned, aligned])

    # A key of its own: this is a genuinely separate app, not a build variant
    # of the main one, and the two never need to share a signature for
    # anything (the broadcast it sends carries no permission requirement).
    ks = os.path.join(HERE, "debug.keystore")
    if not os.path.exists(ks):
        run([jbin("keytool"), "-genkeypair", "-keystore", ks, "-storepass", "android",
             "-keypass", "android", "-alias", "jarvistalk", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=iohelper, OU=dev, O=iohelper, C=US"])
        print(f"  created signing key {ks} (keep it: it must not change)")
    apk = os.path.join(OUT, "jarvis-talk.apk")
    run([os.path.join(bt, "apksigner" + bat), "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", apk, aligned])
    print(f"  built {apk} ({os.path.getsize(apk)} bytes)")

    if a.install:
        adb = [a.adb] + (["-s", a.serial] if a.serial else [])
        run(adb + ["install", "-r", apk])
        print(f"  installed {PKG} on {a.serial or 'the connected device'}")
        if a.test:
            run(adb + ["shell", "am", "start", "-n", f"{PKG}/.LaunchActivity"])
            print("  launched once (toggled iohelper's talk session)")


if __name__ == "__main__":
    main()
