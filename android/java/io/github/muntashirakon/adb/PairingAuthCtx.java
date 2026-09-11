// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
//
// Adapted from libadb-android (MuntashirAkon), taken under Apache-2.0.
//
// Only the crypto backing changed: upstream reaches for BouncyCastle to get
// HKDF and AES-GCM, which would add megabytes to the APK for two primitives
// the platform already ships. AES-128-GCM now comes from javax.crypto, and
// HKDF-SHA256 is short enough to write out (RFC 5869). Everything visible on
// the wire is unchanged - same NUL-terminated peer names, same info string,
// same 128-bit tag, same little-endian IV counter - because adbd on the other
// end is not negotiable.

package io.github.muntashirakon.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Destroyable;

import io.github.muntashirakon.crypto.spake2.Spake2Context;
import io.github.muntashirakon.crypto.spake2.Spake2Role;

class PairingAuthCtx implements Destroyable {
    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/pairing_auth.cpp
    private static final byte[] CLIENT_NAME = StringCompat.getBytes("adb pair client\u0000", "UTF-8");
    private static final byte[] SERVER_NAME = StringCompat.getBytes("adb pair server\u0000", "UTF-8");

    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/aes_128_gcm.cpp
    private static final byte[] INFO = StringCompat.getBytes("adb pairing_auth aes-128-gcm key", "UTF-8");
    private static final int HKDF_KEY_LENGTH = 128 / 8;
    public static final int GCM_IV_LENGTH = 12;     // in bytes
    private static final int GCM_TAG_LENGTH = 128;  // in bits

    private final byte[] mMsg;
    private final Spake2Context mSpake2Ctx;
    private final byte[] mSecretKey = new byte[HKDF_KEY_LENGTH];
    private long mDecIv = 0;
    private long mEncIv = 0;
    private boolean mIsDestroyed = false;

    public static PairingAuthCtx createAlice(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    public static PairingAuthCtx createBob(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Bob, SERVER_NAME, CLIENT_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    private PairingAuthCtx(Spake2Context spake25519, byte[] password)
            throws IllegalArgumentException, IllegalStateException {
        mSpake2Ctx = spake25519;
        mMsg = mSpake2Ctx.generateMessage(password);
    }

    public byte[] getMsg() {
        return mMsg;
    }

    public boolean initCipher(byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) return false;
        byte[] keyMaterial = mSpake2Ctx.processMessage(theirMsg);
        if (keyMaterial == null) return false;
        try {
            byte[] key = hkdfSha256(keyMaterial, INFO, HKDF_KEY_LENGTH);
            System.arraycopy(key, 0, mSecretKey, 0, HKDF_KEY_LENGTH);
            Arrays.fill(key, (byte) 0);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public byte[] encrypt(byte[] in) {
        return encryptDecrypt(true, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mEncIv++).array());
    }

    public byte[] decrypt(byte[] in) {
        return encryptDecrypt(false, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mDecIv++).array());
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public void destroy() {
        mIsDestroyed = true;
        Arrays.fill(mSecretKey, (byte) 0);
        mSpake2Ctx.destroy();
    }

    private byte[] encryptDecrypt(boolean forEncryption, byte[] in, byte[] iv) {
        if (mIsDestroyed) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(mSecretKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(in);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /**
     * HKDF-SHA256, RFC 5869. Matches the BouncyCastle generator this replaced:
     * an absent salt is HashLen ZERO BYTES, not an empty one - get that wrong
     * and the derived key silently differs from the daemon's.
     */
    private static byte[] hkdfSha256(byte[] ikm, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        int hashLen = mac.getMacLength();
        mac.init(new SecretKeySpec(new byte[hashLen], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);                       // extract

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));      // expand
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        Arrays.fill(prk, (byte) 0);
        return out;
    }
}
