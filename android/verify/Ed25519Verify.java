package io.github.muntashirakon.crypto.spake2;

import io.github.muntashirakon.crypto.ed25519.Curve;
import io.github.muntashirakon.crypto.ed25519.Ed25519;
import io.github.muntashirakon.crypto.ed25519.Ed25519CurveParameterSpec;
import io.github.muntashirakon.crypto.ed25519.Ed25519FieldElement;
import io.github.muntashirakon.crypto.ed25519.FieldElement;
import io.github.muntashirakon.crypto.ed25519.GroupElement;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The curve arithmetic SPAKE2 stands on, checked against something other than
 * itself.
 *
 * This exists because of how the pairing bug actually presented. The vendored
 * ed25519 computed the WRONG point and said nothing: Alice and Bob simply
 * derived different keys, and every layer above - TLS, the pairing packets, the
 * AES-GCM channel - reported success. Alice-agrees-with-Bob is therefore not a
 * test at all; two identically wrong implementations agree perfectly. Each
 * check below compares against an independent authority instead: BigInteger for
 * the field, RFC 8032's published vectors for scalar multiplication, and the
 * SPAKE2 seed strings for the M and N points.
 *
 * The root cause was 32-bit overflow, so the field test deliberately feeds
 * limbs at twice the tight bound, in both signs - exactly the values add(),
 * subtract() and 2Z hand to multiply() during a group operation, and exactly
 * the ones a test built only from freshly reduced elements never produces.
 *
 * Lives in the spake2 package to reach the M/N precomputed tables.
 */
public final class Ed25519Verify {

    private static final BigInteger P =
            BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19));
    private static final int[] SHIFT = {0, 26, 51, 77, 102, 128, 153, 179, 204, 230};

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Ed25519CurveParameterSpec spec = Ed25519.getSpec();
        Curve curve = spec.getCurve();
        SecureRandom rnd = new SecureRandom();

        field(curve, rnd);
        scalarMultiply(spec);
        roundTrip(spec, curve, rnd);
        seeds(curve);

        System.out.println();
        System.out.println(failures == 0
                ? checks + " CHECKS PASSED"
                : failures + " OF " + checks + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- 1. the field, against BigInteger, on out-of-bound limbs -----------

    private static void field(Curve curve, SecureRandom rnd) {
        int mulBad = 0, sqBad = 0;
        for (int i = 0; i < 3000; i++) {
            int[] x = looseLimbs(rnd), y = looseLimbs(rnd);
            FieldElement fx = new Ed25519FieldElement(curve.getField(), x);
            FieldElement fy = new Ed25519FieldElement(curve.getField(), y);
            BigInteger bx = value(x), by = value(y);
            if (!value(fx.multiply(fy)).equals(bx.multiply(by).mod(P))) {
                mulBad++;
            }
            if (!value(fx.square()).equals(bx.multiply(bx).mod(P))) {
                sqBad++;
            }
            if (!value(fx.squareAndDouble())
                    .equals(bx.multiply(bx).multiply(BigInteger.valueOf(2)).mod(P))) {
                sqBad++;
            }
        }
        check("multiply agrees with BigInteger on 3000 out-of-bound limb pairs", mulBad == 0);
        check("square/squareAndDouble agree on the same inputs", sqBad == 0);
    }

    /** Twice the tight bound, both signs: what add/subtract/2Z actually emit. */
    private static int[] looseLimbs(SecureRandom rnd) {
        int[] t = new int[10];
        for (int i = 0; i < 10; i++) {
            int lim = (i % 2 == 0) ? (1 << 27) : (1 << 26);
            t[i] = rnd.nextInt(lim) - (rnd.nextBoolean() ? lim : 0);
        }
        return t;
    }

    // ---- 2. scalar multiplication, against RFC 8032 ------------------------

    private static void scalarMultiply(Ed25519CurveParameterSpec spec) throws Exception {
        // RFC 8032 section 7.1, tests 1, 2 and 3: secret key -> public key.
        String[][] vectors = {
                {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                 "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"},
                {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                 "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"},
                {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                 "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"},
        };
        int bad = 0;
        for (String[] v : vectors) {
            byte[] h = MessageDigest.getInstance("SHA-512").digest(unhex(v[0]));
            byte[] a = Arrays.copyOf(h, 32);
            a[0] &= (byte) 248;
            a[31] &= 127;
            a[31] |= 64;
            if (!hex(spec.getB().scalarMultiply(a).toByteArray()).equals(v[1])) {
                bad++;
            }
        }
        check("scalarMultiply reproduces RFC 8032 public keys (3 vectors)", bad == 0);
    }

    // ---- 3. encode/decode --------------------------------------------------

    private static void roundTrip(Ed25519CurveParameterSpec spec, Curve curve, SecureRandom rnd) {
        int nulls = 0, wrong = 0, signSet = 0;
        for (int i = 0; i < 200; i++) {
            byte[] k = new byte[64];
            rnd.nextBytes(k);
            io.github.muntashirakon.crypto.x25519.x25519Scalar.reduce(k);
            byte[] enc = spec.getB().scalarMultiply(Arrays.copyOf(k, 32)).toByteArray();
            if ((enc[31] & 0x80) != 0) {
                signSet++;
            }
            GroupElement p = curve.fromBytesNegateVarTime(enc);
            if (p == null) {
                nulls++;
            } else if (!Arrays.equals(enc, p.toByteArray())) {
                wrong++;
            }
        }
        // Both halves have to be exercised or the sign-bit bug hides: it only
        // ever affected encodings whose top bit was set.
        check("200 real points decode, none rejected (" + signSet + " with the sign bit set)",
                nulls == 0 && signSet > 50 && signSet < 150);
        check("every decoded point re-encodes to the same 32 bytes", wrong == 0);
    }

    // ---- 4. M and N, against their published seeds -------------------------

    private static void seeds(Curve curve) throws Exception {
        // draft-ietf-kitten-krb-spake-preauth appendix B: SHA-256 of the seed
        // string, read directly as a compressed point. Deriving them proves the
        // tables hold the right points AND are not swapped - a swap is
        // invisible to any test where both ends use the same tables, and would
        // show up only as an unexplained failure against adbd.
        String[] tags = {"M", "N"};
        GroupElement[] tables = {Spake2Context.SPAKE_M_SMALL_PRECOMP[0],
                                 Spake2Context.SPAKE_N_SMALL_PRECOMP[0]};
        for (int i = 0; i < 2; i++) {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(
                    ("edwards25519 point generation seed (" + tags[i] + ")")
                            .getBytes(StandardCharsets.US_ASCII));
            GroupElement derived = curve.fromBytesNegateVarTime(h);
            check(tags[i] + " in the precomputed table is the point its seed hashes to",
                    derived != null
                            && hex(derived.toByteArray()).equals(fromPrecomp(tables[i])));
        }
    }

    /** A precomp entry stores (y+x, y-x, 2dxy); the first two give the point. */
    private static String fromPrecomp(GroupElement e) {
        BigInteger inv2 = BigInteger.valueOf(2).modInverse(P);
        BigInteger ypx = value(e.getX()), ymx = value(e.getY());
        BigInteger y = ypx.add(ymx).multiply(inv2).mod(P);
        BigInteger x = ypx.subtract(ymx).multiply(inv2).mod(P);
        byte[] out = new byte[32];
        byte[] be = y.toByteArray();
        for (int i = 0; i < be.length && i < 32; i++) {
            out[i] = be[be.length - 1 - i];
        }
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return hex(out);
    }

    // ---- plumbing ----------------------------------------------------------

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
    }

    private static BigInteger value(int[] limbs) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < 10; i++) {
            v = v.add(BigInteger.valueOf(limbs[i]).shiftLeft(SHIFT[i]));
        }
        return v.mod(P);
    }

    private static BigInteger value(FieldElement f) {
        byte[] b = f.toByteArray();
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            be[i] = b[b.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
