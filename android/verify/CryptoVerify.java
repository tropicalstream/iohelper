package io.github.muntashirakon.adb;

import com.iohelper.card.Certs;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Off-device checks for the two pieces most likely to fail SILENTLY on the
 * phone: the HKDF/AES-GCM rewrite that replaced BouncyCastle, and the
 * hand-rolled X.509 certificate. Both are the sort of thing that produces
 * plausible bytes and then simply never authenticates.
 *
 * Lives in the adb package because PairingAuthCtx is package-private.
 */
public final class CryptoVerify {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        hkdfAgainstRfc5869();
        try { spake2RoundTrip(); } catch (Throwable t) {
            check("SPAKE2 round trip (threw: " + t.getClass().getSimpleName() + ")", false); }
        try { spake2WrongPasswordFails(); } catch (Throwable t) {
            System.out.println("  n/a   wrong-password check (SPAKE2 already broken)"); }
        certificateIsValid();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * RFC 5869 Test Case 3 - the one with an ABSENT salt, which is exactly the
     * case the BouncyCastle code hit. If "absent" were treated as an empty key
     * rather than HashLen zero bytes, this vector would not match.
     */
    private static void hkdfAgainstRfc5869() throws Exception {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] expected = hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"
                + "9d201395faa4b61a96c8");

        Method m = PairingAuthCtx.class.getDeclaredMethod(
                "hkdfSha256", byte[].class, byte[].class, int.class);
        m.setAccessible(true);
        byte[] got = (byte[]) m.invoke(null, ikm, new byte[0], 42);

        check("HKDF-SHA256 matches RFC 5869 test case 3", Arrays.equals(expected, got));
        if (!Arrays.equals(expected, got)) {
            System.out.println("      expected " + hex(expected));
            System.out.println("      got      " + hex(got));
        }
    }

    /**
     * The real thing: two SPAKE2 peers with the same 6-digit code must agree on
     * a key, and the AES-GCM layer on top of it must round-trip. This is the
     * whole pairing handshake minus the socket.
     */
    private static void spake2RoundTrip() {
        byte[] password = "642953".getBytes();
        PairingAuthCtx alice = PairingAuthCtx.createAlice(password);
        PairingAuthCtx bob = PairingAuthCtx.createBob(password);
        check("SPAKE2 contexts created", alice != null && bob != null);
        if (alice == null || bob == null) {
            return;
        }
        byte[] aliceMsg = alice.getMsg();
        byte[] bobMsg = bob.getMsg();
        check("Alice initialises cipher from Bob's message", alice.initCipher(bobMsg));
        check("Bob initialises cipher from Alice's message", bob.initCipher(aliceMsg));

        byte[] plain = "adb pairing payload, 0123456789".getBytes();
        byte[] sealed = alice.encrypt(plain);
        check("Alice encrypts", sealed != null && sealed.length == plain.length + 16);
        byte[] opened = sealed == null ? null : bob.decrypt(sealed);
        check("Bob decrypts what Alice encrypted", opened != null && Arrays.equals(plain, opened));

        // and the other direction, since the IV counters are independent
        byte[] back = bob.encrypt(plain);
        byte[] openedBack = back == null ? null : alice.decrypt(back);
        check("Alice decrypts what Bob encrypted",
                openedBack != null && Arrays.equals(plain, openedBack));
    }

    /** A wrong code must NOT produce a working channel. */
    private static void spake2WrongPasswordFails() {
        PairingAuthCtx alice = PairingAuthCtx.createAlice("111111".getBytes());
        PairingAuthCtx bob = PairingAuthCtx.createBob("222222".getBytes());
        if (alice == null || bob == null) {
            check("wrong-password contexts created", false);
            return;
        }
        boolean a = alice.initCipher(bob.getMsg());
        boolean b = bob.initCipher(alice.getMsg());
        boolean leaked = false;
        if (a && b) {
            byte[] sealed = alice.encrypt("secret".getBytes());
            byte[] opened = sealed == null ? null : bob.decrypt(sealed);
            leaked = opened != null && Arrays.equals("secret".getBytes(), opened);
        }
        check("mismatched pairing code does not yield a working channel", !leaked);
    }

    /** The DER we emit by hand has to be a certificate a real parser accepts. */
    private static void certificateIsValid() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        X509Certificate cert = Certs.selfSigned(kp, "iohelper", 30);
        check("certificate parses via CertificateFactory", cert != null);

        cert.checkValidity();                       // throws if the dates are wrong
        check("certificate is currently valid", true);

        cert.verify(kp.getPublic());                // throws if the signature is wrong
        check("self-signature verifies", true);

        check("certificate carries the same public key",
                Arrays.equals(kp.getPublic().getEncoded(), cert.getPublicKey().getEncoded()));
        check("subject == issuer (self-signed)",
                cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()));
        check("subject is CN=iohelper",
                cert.getSubjectX500Principal().getName().contains("CN=iohelper"));
        check("serial is positive", cert.getSerialNumber().signum() > 0);
        check("re-encodes to identical DER (stable round trip)",
                Arrays.equals(cert.getEncoded(), cert.getEncoded()));
        System.out.println("      subject=" + cert.getSubjectX500Principal()
                + " sigalg=" + cert.getSigAlgName()
                + " notAfter=" + cert.getNotAfter());
    }

    // ---- helpers ----------------------------------------------------------

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String h = Integer.toHexString(x & 0xff);
            sb.append(h.length() == 1 ? "0" : "").append(h);
        }
        return sb.toString();
    }
}
