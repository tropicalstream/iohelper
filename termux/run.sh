#!/data/data/com.termux/files/usr/bin/bash
# Start iohelper on the phone. Reconnects adb-over-loopback first, because the
# connection is dropped by a reboot (and `adb tcpip 5555` itself does not
# survive one - see README-TERMUX.md).
cd "$HOME/iohelper" || exit 1
termux-wake-lock 2>/dev/null || true
adb connect 127.0.0.1:5555 >/dev/null 2>&1 || true
exec python -m iohelper
