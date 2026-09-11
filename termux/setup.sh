#!/data/data/com.termux/files/usr/bin/bash
# iohelper - one-shot Termux setup. Run once:
#     bash /sdcard/Download/iohelper/setup.sh
#
# What it does: installs python + adb, copies the app into ~/iohelper,
# connects adb to the phone's OWN adbd over loopback (which is what gives us
# shell-UID access to logcat - an ordinary app, Termux included, can only read
# its own logs), writes a phone-shaped config, and starts the service.
set -e

say() { printf '\n\033[1;35m==>\033[0m %s\n' "$1"; }

say "1/6  packages (python, adb)"
pkg update -y >/dev/null 2>&1 || true
pkg install -y python android-tools >/dev/null

say "2/6  storage access"
if [ ! -d "$HOME/storage" ]; then
    termux-setup-storage || true
    sleep 2
fi
ZIP=""
for c in /sdcard/Download/iohelper-termux.zip \
         "$HOME/storage/downloads/iohelper-termux.zip"; do
    [ -f "$c" ] && ZIP="$c" && break
done
if [ -z "$ZIP" ]; then
    echo "  Could not find /sdcard/Download/iohelper-termux.zip"
    echo "  (If Termux just asked for storage permission, allow it and re-run.)"
    exit 1
fi

say "3/6  installing to ~/iohelper (from $ZIP)"
mkdir -p "$HOME/iohelper"
# python's zipfile - avoids depending on the unzip package
python - "$ZIP" "$HOME/iohelper" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
    print(f"    unpacked {len(z.namelist())} files")
PY
chmod +x "$HOME/iohelper/run.sh" "$HOME/iohelper/setup.sh" 2>/dev/null || true

say "4/6  connecting to this phone's own adbd (127.0.0.1:5555)"
echo "    A dialog may appear on screen: tap ALLOW (and 'always allow')."
adb disconnect >/dev/null 2>&1 || true
adb connect 127.0.0.1:5555 || true
sleep 3
STATE="$(adb -s 127.0.0.1:5555 get-state 2>&1 || true)"
echo "    adb state: $STATE"
if [ "$STATE" != "device" ]; then
    cat <<'EOF'
    Not connected yet. Usual causes:
      * the "Allow debugging?" dialog is still waiting - accept it, then re-run
      * wireless debugging was re-armed and the port changed; on the PC run
        `adb tcpip 5555` once over USB (it does not survive a reboot)
EOF
fi

say "5/6  verifying we can see the glasses transcripts (the whole point)"
LINES="$(adb -s 127.0.0.1:5555 shell 'logcat -d -t 2000' 2>/dev/null | wc -l || echo 0)"
echo "    logcat lines visible through adb: $LINES  (an app alone sees ~50)"
[ "$LINES" -gt 500 ] && echo "    -> full system log access confirmed" \
                     || echo "    -> NOT working yet; fix the adb connection above"

say "6/6  starting iohelper"
cd "$HOME/iohelper"
termux-wake-lock 2>/dev/null || true
echo "    portal: http://127.0.0.1:8765/  (open it in the phone's browser)"
echo "    paste your Groq + SerpApi keys there, then press Save."
echo
exec python -m iohelper
