package com.iohelper.card;

import android.content.Context;
import android.os.Build;

import com.cgutman.adblib.AdbConnection;

import io.github.muntashirakon.adb.PairingConnectionCtx;
import io.github.muntashirakon.adb.android.AdbMdns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Wireless debugging: the reboot-proof way in.
 *
 * WHY. `adb tcpip 5555` does not survive a reboot, and the cheap escape hatch
 * does not exist - `setprop persist.adb.tcp.port` is refused by SELinux for the
 * shell UID (measured, not assumed). Android's own Wireless debugging DOES
 * persist across reboots, so pairing with it once is what turns this app from
 * "works until you restart your phone" into something another iO owner could
 * actually live with.
 *
 * NO PAIRING IS NEEDED - measured, not assumed. Pairing exists to ADD a key to
 * the set adbd trusts, and this app's key is already there from the one-time
 * "Allow debugging?" tap. adbd checks a TLS client certificate's public key
 * against that same store (it logs "loading keys from /data/misc/adb/adb_keys"),
 * so presenting a certificate over the key it already trusts authenticates with
 * no SPAKE2 exchange at all. Verified end to end: id -un returns "shell", and a
 * logcat read over this link returns the full buffer (3154 lines against the
 * ~51 an ordinary app can see).
 *
 * TWO THINGS MAKE THIS LOOK BROKEN UNTIL YOU KNOW THEM, and both cost a long
 * debugging session:
 *
 *  1. The connect port is NOT a TLS port. adbd speaks plain ADB on it and
 *     upgrades only after an STLS exchange - see {@link #startTls}. Opening with
 *     a ClientHello puts binary into adbd's packet parser, and the two ends then
 *     blame each other: the daemon logs "connection terminated: read failed"
 *     while this side sees the server hang up mid-handshake.
 *  2. adbd sends its CNXN banner UNPROMPTED once the handshake completes; it
 *     already considers the client connected. Sending a second CNXN - which is
 *     what every ordinary ADB client does - reads as a re-connection and adbd
 *     drops the transport ("ADB wifi device disconnected"), after which both
 *     ends wait on each other until something times out. Hence connect(false).
 *
 * So what is actually required is a hand-rolled certificate ({@link Certs}),
 * Conscrypt for TLSv1.3, NsdManager to find the per-boot randomised port, and
 * the two protocol details above.
 *
 * PAIRING, which used to be gated off here, now works - see {@link #pair}. It
 * is what removes the last reason a new user needs a computer: without it the
 * app's key can only reach /data/misc/adb/adb_keys through the "Allow
 * debugging?" prompt on a USB cable, and with it a six-digit code typed into
 * this app does the same job. The bundled SPAKE2 was deriving the wrong key
 * because the vendored ed25519 field arithmetic overflowed int32 (see
 * Ed25519FieldElement#multiply); that is fixed and verified against BoringSSL's
 * own vectors, RFC 8032 and the published M/N constants.
 */
public final class Wireless {

    private static final String TAG = "iohelperWireless";

    private static final String CERT_FILE = "adb_cert.der";
    private static final String CN = "iohelper";
    private static final int DISCOVER_TIMEOUT_MS = 8000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;

    private static SSLContext sslContext;

    private Wireless() {
    }

    // ---- identity ---------------------------------------------------------

    /**
     * The keypair AdbLib persisted, read back through the standard encodings.
     * Deliberately does not touch AdbCrypto: it keeps the pair private, and
     * patching a vendored library to add a getter is a worse trade than reading
     * the two files it already wrote.
     */
    public static KeyPair keyPair(Context ctx) throws Exception {
        LocalAdb.crypto(ctx);                    // ensures both files exist
        File dir = ctx.getFilesDir();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(read(new File(dir, "adb_key"))));
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(read(new File(dir, "adb_key.pub"))));
        return new KeyPair(pub, priv);
    }

    /** The TLS client certificate, generated once and then reused forever. */
    public static X509Certificate certificate(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), CERT_FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                return (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(in);
            } catch (Exception ignored) {
                // regenerate below
            }
        }
        X509Certificate cert = Certs.selfSigned(keyPair(ctx), CN, 30);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(cert.getEncoded());
        }
        return cert;
    }

    // ---- pairing ----------------------------------------------------------

    /**
     * Pair with the code Android shows under Developer options -> Wireless
     * debugging -> "Pair device with pairing code", finding the one-shot
     * pairing port over mDNS. Blocking; call from a background thread.
     *
     * The pairing service is advertised ONLY while that dialog is open and the
     * code changes every time it is, so a "no _adb-tls-pairing._tcp" failure
     * almost always means the dialog was closed rather than anything being
     * wrong. Six digits, no spaces.
     */
    public static void pair(Context ctx, String code) throws Exception {
        Endpoint e = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, DISCOVER_TIMEOUT_MS);
        if (e == null) {
            throw new java.io.IOException("no _adb-tls-pairing._tcp service found - open "
                    + "Wireless debugging -> Pair device with pairing code and leave that "
                    + "dialog on screen");
        }
        android.util.Log.i(TAG, "pairing with " + e);
        pair(ctx, e.host, e.port, code);
    }

    /**
     * One-time pairing against a known pairing endpoint.
     *
     * What this actually buys: adbd adds our public key to
     * /data/misc/adb/adb_keys, the same store the "Allow debugging?" prompt
     * writes to. After it, {@link #connect} authenticates on the certificate
     * alone - so this is the step that lets someone set the app up with no
     * computer at all. The key survives reboots, and so does Wireless
     * debugging, so it is done once and never again.
     *
     * Blocking; call from a background thread.
     */
    public static void pair(Context ctx, String host, int port, String code) throws Exception {
        if (!spake2Works()) {
            // Kept as a guard, not as a verdict. A PAKE that derives the wrong
            // key still completes a handshake and still writes a useless entry;
            // refusing is better than pairing to nothing.
            throw new UnsupportedOperationException(
                    "SPAKE2 self-test failed - refusing to pair, because a broken PAKE "
                    + "fails silently. Approve this app's key over USB instead.");
        }
        try (PairingConnectionCtx p = new PairingConnectionCtx(host, port,
                code.getBytes("UTF-8"), keyPair(ctx).getPrivate(), certificate(ctx),
                Build.MODEL == null ? "iohelper" : Build.MODEL)) {
            p.start();
        }
        Prefs.put(ctx, Prefs.WIRELESS_PAIRED, "1");
        Prefs.put(ctx, Prefs.WIRELESS_ENABLED, "1");
    }

    /**
     * Does the bundled SPAKE2 actually agree on a key with itself?
     *
     * It does now. It did not until the vendored ed25519 was fixed: two 19x/38x
     * scalings were being evaluated in 32-bit int arithmetic and overflowed on
     * limb values the group law routinely produces, so scalarMultiply returned
     * the wrong point and Alice and Bob derived different keys - while every
     * layer reported success. That is the worst failure mode a PAKE has, which
     * is why this gate exists at all and why it stayed shut for so long.
     *
     * Run at most once per process: it costs two scalar multiplications.
     * The offline proof lives in android/verify (Ed25519Verify, Spake2Probe).
     */
    public static synchronized boolean spake2Works() {
        if (spake2Ok == null) {
            spake2Ok = Boolean.FALSE;
            try {
                java.lang.reflect.Method create = Class
                        .forName("io.github.muntashirakon.adb.PairingAuthCtx")
                        .getDeclaredMethod("createAlice", byte[].class);
                create.setAccessible(true);
                spake2Ok = create.invoke(null, (Object) "000000".getBytes("UTF-8")) != null
                        && selfAgrees();
            } catch (Throwable t) {
                spake2Ok = Boolean.FALSE;
            }
        }
        return spake2Ok;
    }

    private static Boolean spake2Ok;

    /** Alice and Bob, same password, must reach the same key - and the AES-GCM
     *  channel derived from it must round-trip, which is what actually gets used. */
    private static boolean selfAgrees() {
        try {
            Class<?> c = Class.forName("io.github.muntashirakon.adb.PairingAuthCtx");
            java.lang.reflect.Method mkA = c.getDeclaredMethod("createAlice", byte[].class);
            java.lang.reflect.Method mkB = c.getDeclaredMethod("createBob", byte[].class);
            java.lang.reflect.Method getMsg = c.getDeclaredMethod("getMsg");
            java.lang.reflect.Method init = c.getDeclaredMethod("initCipher", byte[].class);
            java.lang.reflect.Method enc = c.getDeclaredMethod("encrypt", byte[].class);
            java.lang.reflect.Method dec = c.getDeclaredMethod("decrypt", byte[].class);
            for (java.lang.reflect.Method m : new java.lang.reflect.Method[]{
                    mkA, mkB, getMsg, init, enc, dec}) {
                m.setAccessible(true);
            }
            byte[] pw = "424242".getBytes("UTF-8");
            Object a = mkA.invoke(null, (Object) pw), b = mkB.invoke(null, (Object) pw);
            if (a == null || b == null) {
                return false;
            }
            byte[] am = (byte[]) getMsg.invoke(a), bm = (byte[]) getMsg.invoke(b);
            if (!Boolean.TRUE.equals(init.invoke(a, (Object) bm))
                    || !Boolean.TRUE.equals(init.invoke(b, (Object) am))) {
                return false;
            }
            byte[] probe = "probe".getBytes("UTF-8");
            byte[] sealed = (byte[]) enc.invoke(a, (Object) probe);
            byte[] opened = sealed == null ? null : (byte[]) dec.invoke(b, (Object) sealed);
            return opened != null && java.util.Arrays.equals(probe, opened);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean paired(Context ctx) {
        return "1".equals(Prefs.str(ctx, Prefs.WIRELESS_PAIRED, ""));
    }

    /** Whether to try the TLS path at all. On by default - it costs one short
     *  mDNS lookup, and the classic port is still tried straight after. */
    public static boolean enabled(Context ctx) {
        return !"0".equals(Prefs.str(ctx, Prefs.WIRELESS_ENABLED, "1"));
    }

    // ---- discovery --------------------------------------------------------

    /** A host/port pair found over mDNS. */
    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /**
     * Find one of adbd's advertised services. The connect port is randomised at
     * every boot, which is exactly why this cannot be a constant.
     *
     * @param serviceType {@link AdbMdns#SERVICE_TYPE_TLS_PAIRING} or
     *                    {@link AdbMdns#SERVICE_TYPE_TLS_CONNECT}
     */
    public static Endpoint discover(Context ctx, String serviceType, int timeoutMs)
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> host = new AtomicReference<>();
        final AtomicInteger found = new AtomicInteger(-1);
        AdbMdns mdns = new AdbMdns(ctx, serviceType, (address, port) -> {
            if (port <= 0) {
                return;
            }
            host.set(address == null ? "127.0.0.1" : address.getHostAddress());
            found.set(port);
            latch.countDown();
        });
        mdns.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                mdns.stop();
            } catch (Exception ignored) {
            }
        }
        return found.get() > 0 ? new Endpoint(host.get(), found.get()) : null;
    }

    // ---- TLS transport ----------------------------------------------------

    /**
     * TLSv1.3 from Conscrypt, presenting our certificate.
     *
     * Bundled Conscrypt rather than the platform's: the pairing exchange needs
     * SSL_export_keying_material, which the platform copy only exposes behind
     * hidden-API restrictions this app cannot use on Android 14.
     *
     * The trust manager accepts anything on purpose. There is no CA in this
     * story - the daemon proves itself by knowing the pairing code, and after
     * that by recognising our key. Nothing here is exposed off-device.
     */
    public static SSLContext sslContext(Context ctx) throws Exception {
        if (sslContext == null) {
            sslContext = build(ctx, false);
        }
        return sslContext;
    }

    /** Build a context. forcePlatform skips bundled Conscrypt, for the sweep. */
    private static SSLContext build(Context ctx, boolean forcePlatform) throws Exception {
        final X509Certificate cert = certificate(ctx);
        final PrivateKey key = keyPair(ctx).getPrivate();

        SSLContext c;
        if (forcePlatform) {
            c = SSLContext.getInstance("TLSv1.3");
        } else {
            try {
                Provider conscrypt = (Provider) Class.forName("org.conscrypt.OpenSSLProvider")
                        .getDeclaredConstructor().newInstance();
                c = SSLContext.getInstance("TLSv1.3", conscrypt);
                android.util.Log.i(TAG, "TLS provider: bundled Conscrypt");
            } catch (Throwable e) {
                c = SSLContext.getInstance("TLSv1.3");
                android.util.Log.i(TAG, "TLS provider: platform (" + e + ")");
            }
        }
        c.init(new KeyManager[]{new X509ExtendedKeyManager() {
            private static final String ALIAS = "adb";

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket s) {
                // Log what was asked for, then offer our one certificate
                // regardless. Matching on "RSA" exactly is how a client
                // silently sends NO certificate: TLS 1.3 stacks name key types
                // in several ways ("RSA", "RSASSA-PSS", ...), and returning
                // null here looks identical, from the daemon's side, to having
                // no trusted key at all.
                android.util.Log.i(TAG, "chooseClientAlias keyTypes="
                        + java.util.Arrays.toString(keyTypes)
                        + " issuers=" + (issuers == null ? 0 : issuers.length));
                return ALIAS;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket s) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                android.util.Log.i(TAG, "getCertificateChain(" + alias + ")");
                return ALIAS.equals(alias) ? new X509Certificate[]{cert} : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                android.util.Log.i(TAG, "getPrivateKey(" + alias + ")");
                return ALIAS.equals(alias) ? key : null;
            }
        }}, new X509TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return c;
    }

    /**
     * Connect over the TLS path: find the port, handshake with our certificate,
     * then run the ordinary ADB protocol inside the tunnel. adbd skips the token
     * exchange here because the client certificate already identified us.
     */
    public static AdbConnection connect(Context ctx) throws Exception {
        return connect(ctx, DISCOVER_TIMEOUT_MS);
    }

    /**
     * NOTE: this deliberately does NOT require pairing first.
     *
     * Pairing exists to ADD a key to the set adbd trusts. Once the key is in
     * that set - from the "Allow debugging?" prompt over USB, or from
     * {@link #pair} - adbd checks a TLS client certificate's public key against
     * the same store, so presenting a certificate over that key authenticates
     * with no SPAKE2 exchange at all.
     *
     * This is the reboot-proof path, because Wireless debugging persists across
     * reboots where `adb tcpip 5555` does not.
     */
    public static AdbConnection connect(Context ctx, int discoverTimeoutMs) throws Exception {
        Endpoint e = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, discoverTimeoutMs);
        if (e == null) {
            throw new java.io.IOException("no _adb-tls-connect._tcp service found "
                    + "(is Wireless debugging on?)");
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(e.host, e.port), HANDSHAKE_TIMEOUT_MS);
        raw.setTcpNoDelay(true);
        startTls(raw);
        SSLSocket ssl = (SSLSocket) sslContext(ctx).getSocketFactory()
                .createSocket(raw, e.host, e.port, true);
        ssl.setUseClientMode(true);
        android.util.Log.i(TAG, "handshaking with " + e
                + " protocols=" + java.util.Arrays.toString(ssl.getEnabledProtocols()));
        try {
            ssl.startHandshake();
        } catch (Exception ex) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = ex; t != null; t = t.getCause()) {
                chain.append(chain.length() == 0 ? "" : " <- ").append(t);
            }
            android.util.Log.i(TAG, "handshake FAILED: " + chain);
            throw ex;
        }
        android.util.Log.i(TAG, "handshake OK proto=" + ssl.getSession().getProtocol()
                + " cipher=" + ssl.getSession().getCipherSuite());
        android.util.Log.i(TAG, "creating AdbConnection over the TLS socket");
        AdbConnection c = AdbConnection.create(ssl, LocalAdb.crypto(ctx));
        // false: adbd already sent its CNXN when the handshake finished, and a
        // second one from us makes it drop the transport.
        android.util.Log.i(TAG, "AdbConnection created; waiting for adbd's CNXN");
        c.connect(false);
        android.util.Log.i(TAG, "ADB connected over TLS");
        return c;
    }

    /**
     * Try several TLS configurations against adbd and report how far each gets.
     *
     * The PC's adb connects to this same port with a key approved the same way,
     * so the daemon is not the problem - something about our ClientHello is. One
     * rebuild-and-run beats a sequence of single-variable guesses on a device
     * that has to be reached over the network each time.
     */
    public static String sweep(Context ctx) {
        StringBuilder report = new StringBuilder();
        Endpoint e;
        try {
            e = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 8000);
            if (e == null) {
                return "no _adb-tls-connect._tcp advertised";
            }
        } catch (Exception ex) {
            return "discover failed: " + ex;
        }
        report.append("endpoint=").append(e).append('\n');

        String[] names = {"conscrypt/TLSv1.3-only", "conscrypt/default-protocols",
                          "conscrypt/TLSv1.3+SNI", "platform/TLSv1.3-only"};
        for (int i = 0; i < names.length; i++) {
            String outcome;
            Socket raw = null;
            try {
                SSLContext c = build(ctx, i == 3);
                raw = new Socket();
                raw.connect(new InetSocketAddress(e.host, e.port), 5000);
                raw.setTcpNoDelay(true);
                startTls(raw);
                SSLSocket ssl = (SSLSocket) c.getSocketFactory()
                        .createSocket(raw, e.host, e.port, true);
                ssl.setUseClientMode(true);
                if (i != 1) {
                    ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
                }
                if (i == 2) {
                    try {
                        javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                        p.setServerNames(java.util.Collections.singletonList(
                                new javax.net.ssl.SNIHostName("localhost")));
                        ssl.setSSLParameters(p);
                    } catch (Throwable ignored) {
                    }
                }
                ssl.startHandshake();
                outcome = "HANDSHAKE OK proto=" + ssl.getSession().getProtocol()
                        + " cipher=" + ssl.getSession().getCipherSuite();
                try {
                    ssl.close();
                } catch (Exception ignored) {
                }
            } catch (Throwable t) {
                StringBuilder chain = new StringBuilder();
                for (Throwable x = t; x != null; x = x.getCause()) {
                    chain.append(chain.length() == 0 ? "" : " <- ").append(x);
                }
                outcome = "fail: " + chain;
            } finally {
                if (raw != null) {
                    try {
                        raw.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            android.util.Log.i(TAG, "sweep[" + names[i] + "] " + outcome);
            report.append(names[i]).append(" -> ").append(outcome).append('\n');
        }
        return report.toString();
    }

    /** ADB's "start TLS" command, and the version adbd expects with it. */
    private static final int CMD_STLS = 0x534c5453;      // "STLS", little-endian
    private static final int STLS_VERSION = 0x01000000;

    /**
     * Negotiate TLS the way adbd actually wants it.
     *
     * The connect port is NOT a TLS port. adbd speaks plain ADB on it first and
     * only then upgrades: the client sends CNXN, the daemon answers STLS, the
     * client echoes STLS, and the TLS handshake starts after that. Opening with
     * a ClientHello instead puts binary into adbd's packet parser, which is why
     * the daemon logged "connection terminated: read failed" while this side saw
     * the server hang up mid-handshake - each blaming the other.
     *
     * AdbLib predates wireless debugging and has no STLS, so this is done by
     * hand before handing the upgraded socket back to it.
     */
    private static void startTls(Socket raw) throws Exception {
        java.io.OutputStream out = raw.getOutputStream();
        java.io.InputStream in = raw.getInputStream();

        out.write(com.cgutman.adblib.AdbProtocol.generateConnect());
        out.flush();

        byte[] header = new byte[24];
        readFully(in, header);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(header)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt();
        b.getInt();                       // arg0
        b.getInt();                       // arg1
        int length = b.getInt();
        if (length > 0) {
            readFully(in, new byte[Math.min(length, 1 << 20)]);
        }
        if (cmd != CMD_STLS) {
            throw new java.io.IOException("expected STLS, got 0x" + Integer.toHexString(cmd));
        }
        android.util.Log.i(TAG, "adbd asked for STLS; upgrading");
        out.write(com.cgutman.adblib.AdbProtocol.generateMessage(
                CMD_STLS, STLS_VERSION, 0, null));
        out.flush();
    }

    private static void readFully(java.io.InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new java.io.EOFException("adbd closed during STLS negotiation");
            }
            off += n;
        }
    }

    private static byte[] read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return b;
    }
}
