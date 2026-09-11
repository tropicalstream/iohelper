#!/usr/bin/env python3
r"""Build + install the notification app that gives the glasses a real header.

No Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner, driven straight from
the Android SDK that Android Studio installed. Everything is discovered, so
this works on any machine with the SDK and a JDK.

    python android/build.py                    # build, sign
    python android/build.py --install          # ...and install to the phone
    python android/build.py --label "Athena"   # change the header text
    python android/build.py --install --test   # ...and post a test card

Why an app at all: the glasses render "<app label> / <title> / <body>", and the
label is the POSTING package. `cmd notification post` posts as com.android.shell,
so cards read "Shell". This app's label replaces that.
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
LIBS = os.path.join(HERE, "libs")
PKG = "com.iohelper.card"


def prepare_libs(abis):
    """Unpack android/libs/*.aar and *.jar for javac, d8 and the APK.

    Gradle exists largely to do this. We need exactly one AAR (Conscrypt, for
    TLS with exported keying material - the thing adb's wireless pairing is
    built on), and that AAR turns out to carry no resources at all: just
    classes.jar and jni/<abi>/*.so. So the whole of "consume an AAR" is unzip
    two things and put them where aapt2 and d8 expect them, which is cheaper
    and far less disruptive than migrating this build to Gradle.

    Returns (jars_for_classpath, [(apk_path, file_path), ...] for native libs).
    """
    if not os.path.isdir(LIBS):
        return [], []
    staging = os.path.join(OUT, "libs")
    os.makedirs(staging, exist_ok=True)
    jars, natives = [], []

    def keep_classes(src, dst):
        """Strip everything d8 has no use for; module-info.class trips it up."""
        with zipfile.ZipFile(src) as zin, \
                zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in zin.namelist():
                if n.endswith(".class") and not n.endswith("module-info.class"):
                    zout.writestr(n, zin.read(n))

    for path in sorted(glob.glob(os.path.join(LIBS, "*.aar"))):
        name = os.path.splitext(os.path.basename(path))[0]
        with zipfile.ZipFile(path) as z:
            inner = os.path.join(staging, name + "-raw.jar")
            with open(inner, "wb") as fh:
                fh.write(z.read("classes.jar"))
            jar = os.path.join(staging, name + ".jar")
            keep_classes(inner, jar)
            jars.append(jar)
            for entry in z.namelist():
                m = re.match(r"jni/([^/]+)/(lib.+\.so)$", entry)
                if m and m.group(1) in abis:
                    so = os.path.join(staging, m.group(1) + "-" + m.group(2))
                    os.makedirs(os.path.dirname(so), exist_ok=True)
                    with open(so, "wb") as fh:
                        fh.write(z.read(entry))
                    natives.append((f"lib/{m.group(1)}/{m.group(2)}", so))
        print(f"  aar {os.path.basename(path)} -> {os.path.basename(jar)}"
              f"{' + ' + str(len(natives)) + ' native lib(s)' if natives else ''}")

    for path in sorted(glob.glob(os.path.join(LIBS, "*.jar"))):
        jar = os.path.join(staging, os.path.basename(path))
        keep_classes(path, jar)
        jars.append(jar)
        print(f"  jar {os.path.basename(path)}")
    return jars, natives


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
    ap.add_argument("--label", default=None, help="app label = header on the glasses")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--test", action="store_true", help="post a test card after install")
    ap.add_argument("--serial", default=os.environ.get("IOHELPER_DEVICE", ""))
    ap.add_argument("--adb", default=os.environ.get("ADB", r"C:\platform-tools\adb.exe"
                                                    if os.name == "nt" else "adb"))
    ap.add_argument("--abi", default="arm64-v8a",
                    help="comma-separated ABIs to bundle native libs for")
    a = ap.parse_args()
    abis = [x.strip() for x in a.abi.split(",") if x.strip()]

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

    if a.label:
        p = os.path.join(HERE, "res", "values", "strings.xml")
        with open(p, encoding="utf-8") as fh:
            t = fh.read()
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(re.sub(r'(<string name="app_name">).*?(</string>)',
                            rf"\g<1>{a.label}\g<2>", t))
        print(f"  label -> {a.label}")

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

    # 2. java -> dex
    jars, natives = prepare_libs(abis)
    cp = os.pathsep.join([android_jar] + jars)
    # libs/ holds AARs and JARs, never loose sources - a stray .java dropped in
    # there would otherwise be compiled as if it were part of the app.
    skip = ("build" + os.sep, "libs" + os.sep, "verify" + os.sep)
    srcs = [os.path.join(dp, f) for dp, _, fs in os.walk(HERE)
            for f in fs if f.endswith(".java")
            and not any(k in dp + os.sep for k in skip)]
    srcs += glob.glob(os.path.join(OUT, "gen", "**", "*.java"), recursive=True)
    run([jbin("javac"), "-source", "17", "-target", "17", "-nowarn",
         "-classpath", cp, "-d", os.path.join(OUT, "classes")] + srcs)
    classes = glob.glob(os.path.join(OUT, "classes", "**", "*.class"), recursive=True)
    # Every input on ONE LINE overflowed Windows' 8191-character command limit
    # once the app grew past ~100 classes ("The input line is too long", and d8
    # never runs). d8 takes an @argfile, which has no such ceiling and costs a
    # temporary file - javac has the same problem waiting for it, so srcs would
    # go the same way if this ever gets much bigger.
    argfile = os.path.join(OUT, "d8-inputs.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        # One BARE path per line. d8 does not unquote or unescape argfile
        # entries - a quoted path arrives with the quote still attached and it
        # dies on `Illegal char <"> at index 0`.
        f.write("\n".join(classes + jars))
    run([os.path.join(bt, "d8" + bat), "--lib", android_jar, "--min-api", "26",
         "--output", OUT, "@" + argfile])

    # 3. package dex + native libs into the apk, align, sign
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for dex in sorted(glob.glob(os.path.join(OUT, "classes*.dex"))):
            z.write(dex, os.path.basename(dex))
        for apk_path, file_path in natives:
            z.write(file_path, apk_path)
    aligned = os.path.join(OUT, "aligned.apk")
    # -p page-aligns the .so entries; harmless when there are none
    run([os.path.join(bt, "zipalign" + ext), "-p", "-f", "4", unsigned, aligned])

    # Keep the keystore OUTSIDE build/ (which is wiped each run): a fresh key per
    # build changes the signature and Android then refuses to update the app
    # ("INSTALL_FAILED_UPDATE_INCOMPATIBLE"). Create it once, reuse forever.
    ks = os.path.join(HERE, "debug.keystore")
    if not os.path.exists(ks):
        run([jbin("keytool"), "-genkeypair", "-keystore", ks, "-storepass", "android",
             "-keypass", "android", "-alias", "iohelper", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=iohelper, OU=dev, O=iohelper, C=US"])
        print(f"  created signing key {ks} (keep it: it must not change)")
    apk = os.path.join(OUT, "iohelper-card.apk")
    run([os.path.join(bt, "apksigner" + bat), "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", apk, aligned])
    print(f"  built {apk} ({os.path.getsize(apk)} bytes)")

    if a.install:
        # -s <serial> only when one is actually set; adb picks the sole
        # connected device on its own otherwise, so leaving --serial /
        # IOHELPER_DEVICE unset just works rather than failing on an empty
        # -s "" flag.
        adb = [a.adb] + (["-s", a.serial] if a.serial else [])
        run(adb + ["install", "-r", "-g", apk])
        # -g grants runtime permissions, but be explicit: without
        # POST_NOTIFICATIONS (API 33+) notify() is silently dropped.
        subprocess.run(adb + ["shell", "pm", "grant", PKG,
                              "android.permission.POST_NOTIFICATIONS"],
                       capture_output=True, text=True)
        print(f"  installed {PKG} on {a.serial or 'the connected device'}")
        if a.test:
            run(adb + ["shell", "am", "broadcast", "-n", f"{PKG}/.ShowReceiver",
                       "-a", f"{PKG}.SHOW", "--es", "title", "Hello",
                       "--es", "body", "Header test - this card came from the app."])
            print("  test card posted")


if __name__ == "__main__":
    main()
