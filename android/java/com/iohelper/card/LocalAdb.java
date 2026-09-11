package com.iohelper.card;

import android.content.Context;
import android.util.Base64;

import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Shell-UID access from inside this app, with nothing else installed.
 *
 * WHY THIS EXISTS. The assistant has to read the glasses' transcripts, which the
 * RayNeo app only ever writes to logcat. An ordinary Android app cannot read
 * that: logd filters the buffer by UID, so even with READ_LOGS granted this app
 * measured 51 visible lines against adb's 4154 in the same buffer. Termux is an
 * ordinary app too and is blocked identically.
 *
 * WHAT WORKS. The phone's own adbd is listening on 127.0.0.1:5555, and anything
 * that speaks the ADB protocol to it runs as the SHELL uid, which sees
 * everything. So the app becomes its own adb client: one install, one "Allow
 * debugging?" tap, no Termux and no Shizuku.
 *
 * The protocol is handled by AdbLib (Cameron Gutman, BSD-3-Clause, vendored in
 * com/cgutman/adblib - pure JDK, no dependencies).
 *
 * THE KEYPAIR IS THE AUTHORISATION. adbd remembers the public key the user
 * approved, so we generate one on first run and reuse it forever; lose it and
 * the user gets prompted again.
 */
public final class LocalAdb {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private LocalAdb() {
    }

    /** android.util.Base64 wired into AdbLib's tiny abstraction. */
    private static final AdbBase64 BASE64 = new AdbBase64() {
        @Override
        public String encodeToString(byte[] data) {
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }
    };

    /** Load the saved keypair, generating and persisting one on first use. */
    public static AdbCrypto crypto(Context ctx) throws Exception {
        File dir = ctx.getFilesDir();
        File pub = new File(dir, "adb_key.pub");
        File priv = new File(dir, "adb_key");
        if (pub.exists() && priv.exists()) {
            try {
                return AdbCrypto.loadAdbKeyPair(BASE64, priv, pub);
            } catch (Exception e) {
                // corrupt or from an older keygen - fall through and regenerate
            }
        }
        AdbCrypto c = AdbCrypto.generateAdbKeyPair(BASE64);
        c.saveAdbKeyPair(priv, pub);
        return c;
    }

    /**
     * Connect to this phone's adbd. The FIRST call blocks until the user taps
     * "Allow debugging?" on screen; afterwards adbd recognises the key and it
     * returns immediately. Always call from a background thread.
     */
    public static AdbConnection connect(Context ctx) throws Exception {
        // Prefer the wireless-debugging path once paired: it is the only one that
        // survives a reboot. It can still fail for ordinary reasons - Wi-Fi off,
        // the toggle turned off, mDNS slow - so a failure here is not fatal and
        // the classic port is tried straight after.
        if (Wireless.enabled(ctx)) {
            try {
                AdbConnection w = Wireless.connect(ctx, 3000);
                lastWirelessError = null;
                return w;
            } catch (Exception e) {
                lastWirelessError = e;      // usually just "Wireless debugging is off"
            }
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        AdbConnection c = AdbConnection.create(s, crypto(ctx));
        c.connect();
        return c;
    }

    /** Why the wireless path was skipped, for the diagnostics line. */
    static volatile Exception lastWirelessError;

    /** Run one shell command and return its output (bounded wait). */
    public static String shell(Context ctx, String command, int maxMillis) throws Exception {
        AdbConnection c = null;
        try {
            c = connect(ctx);
            AdbStream stream = c.open("shell:" + command);
            StringBuilder out = new StringBuilder();
            long deadline = System.currentTimeMillis() + maxMillis;
            while (!stream.isClosed() && System.currentTimeMillis() < deadline) {
                try {
                    out.append(new String(stream.read(), "UTF-8"));
                } catch (java.io.IOException eof) {
                    break;                       // stream closed = command done
                }
            }
            try {
                stream.close();
            } catch (Exception ignored) {
            }
            return out.toString();
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
