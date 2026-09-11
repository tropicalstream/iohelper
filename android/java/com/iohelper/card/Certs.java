package com.iohelper.card;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A self-signed X.509 certificate, written out by hand.
 *
 * Wireless debugging authenticates with a TLS CLIENT CERTIFICATE, not with the
 * classic ADB token exchange - so the RSA key adbd already trusts has to be
 * wrapped in a certificate before it can be used. Android ships no certificate
 * builder: the JDK's generator lives in the sun.security.x509 internals, which
 * are not on Android, and the usual answer is to pull in BouncyCastle for ~8 MB.
 *
 * A self-signed certificate is a fixed ASN.1 shape, so it is cheaper to emit the
 * DER directly and hand the bytes to CertificateFactory. adbd only ever reads
 * the public key out of it and compares that against the keys it has been told
 * to trust, so the certificate carries nothing but identity.
 */
public final class Certs {

    // sha256WithRSAEncryption -- 1.2.840.113549.1.1.11
    private static final byte[] OID_SHA256_RSA =
            {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};              // 2.5.4.3
    private static final byte[] OID_BASIC_CONSTRAINTS = {0x55, 0x1d, 0x13}; // 2.5.29.19

    private Certs() {
    }

    public static X509Certificate selfSigned(KeyPair kp, String cn, int years)
            throws Exception {
        long now = System.currentTimeMillis();
        // Backdate a little: phones with a lagging clock otherwise reject a
        // certificate that is valid but not yet born.
        byte[] notBefore = time(new Date(now - 24L * 3600 * 1000));
        byte[] notAfter = time(new Date(now + years * 365L * 24 * 3600 * 1000));

        byte[] serial = tlv(0x02, new BigInteger(64, new SecureRandom())
                .add(BigInteger.ONE).toByteArray());
        byte[] algId = seq(cat(tlv(0x06, OID_SHA256_RSA), tlv(0x05, new byte[0])));
        byte[] name = seq(tlv(0x31, seq(cat(tlv(0x06, OID_CN),
                tlv(0x0c, cn.getBytes("UTF-8"))))));
        byte[] validity = seq(cat(notBefore, notAfter));
        byte[] spki = kp.getPublic().getEncoded();   // already a DER SubjectPublicKeyInfo

        // basicConstraints = CA:TRUE, marked critical
        byte[] bc = seq(cat(tlv(0x06, OID_BASIC_CONSTRAINTS),
                tlv(0x01, new byte[]{(byte) 0xff}),
                tlv(0x04, seq(tlv(0x01, new byte[]{(byte) 0xff})))));
        byte[] extensions = tlv(0xa3, seq(bc));

        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));   // v3
        byte[] tbs = seq(cat(version, serial, algId, name, validity, name, spki, extensions));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(kp.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();

        // BIT STRING carries a leading "unused bits" octet, always zero here
        byte[] cert = seq(cat(tbs, algId, tlv(0x03, cat(new byte[]{0}, sig))));

        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }

    // ---- the small amount of DER we need ----------------------------------

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 0x80) {
            o.write(n);
        } else {
            byte[] len = BigInteger.valueOf(n).toByteArray();
            int off = (len.length > 1 && len[0] == 0) ? 1 : 0;   // drop sign padding
            o.write(0x80 | (len.length - off));
            o.write(len, off, len.length - off);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    /**
     * A certificate time, in whichever of the two encodings is legal for it.
     *
     * UTCTime carries a TWO-DIGIT year, and RFC 5280 reads 50-99 as 19xx. So a
     * 30-year certificate written as UTCTime expires in 1955 - valid DER, parses
     * cleanly, and is dead on arrival. RFC 5280 requires UTCTime through 2049 and
     * GeneralizedTime from 2050 on, so switch at that boundary rather than
     * capping how long the certificate may live.
     */
    private static byte[] time(Date d) throws Exception {
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.US);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        boolean generalized = Integer.parseInt(year.format(d)) >= 2050;
        SimpleDateFormat f = new SimpleDateFormat(
                generalized ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(generalized ? 0x18 : 0x17, f.format(d).getBytes("UTF-8"));
    }
}
