/*
 * OkcarOS link stack — L4 media: the AirPlay screen-stream cipher.
 *
 * Derived from the sender's own code and then proved against the capture
 * (the internal notes, the recorded reference session):
 *
 *   N    = the video SETUP *request*'s `streamConnectionID` (we choose it)
 *   key  = SHA512("AirPlayStreamKey" || decimal(N) || 16 zero bytes)[0:16]
 *   iv   = SHA512("AirPlayStreamIV"  || decimal(N) || 16 zero bytes)[0:16]
 *
 * Cipher is AES-128-CTR with **one context for the whole stream**: the 128-bit
 * big-endian counter runs continuously over the concatenated payloads of every
 * type==0 (plain frame) record.  There is no per-frame IV and the header
 * timestamp is not used.  Records with another type (the avcC configuration
 * frame 0x01140001, the all-zero type-5 heartbeat) are sent in the clear and
 * must NOT be fed to the cipher.
 *
 * `ekey`/`eiv` in the base SETUP are decorative: they are never a key, IV, salt
 * or secret — the KDF secret is 16 zero bytes.  A bare SHA-512, not HKDF.
 *
 * Pure Java (no android.*), so the host JVM can check it against the Python
 * reference values in .car-test/airplay-crypto/.
 */

package com.okcar.link.airplay;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AirPlayStreamCrypto {

    /** KDF label for the stream key (ASCII, no terminator). */
    public static final String KEY_LABEL = "AirPlayStreamKey";

    /** KDF label for the stream IV (ASCII, no terminator). */
    public static final String IV_LABEL = "AirPlayStreamIV";

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** The 16 zero bytes the sender app appends to the KDF input. */
    private static final byte[] SECRET_PAD = new byte[16];

    private final byte[] mKey;
    private final byte[] mIv;
    private final Cipher mCipher;
    private long mBytes;

    public AirPlayStreamCrypto(long streamConnectionId) {
        mKey = deriveKey(streamConnectionId);
        mIv = deriveIv(streamConnectionId);
        try {
            mCipher = Cipher.getInstance("AES/CTR/NoPadding");
            mCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(mKey, "AES"),
                    new IvParameterSpec(mIv));
        } catch (Exception e) {
            throw new IllegalStateException("AES/CTR unavailable", e);
        }
    }

    /** key = SHA512(label || decimal(N) || 16 zero bytes)[0:16]. */
    public static byte[] deriveKey(long streamConnectionId) {
        return derive(KEY_LABEL, streamConnectionId);
    }

    /** iv = SHA512(label || decimal(N) || 16 zero bytes)[0:16]. */
    public static byte[] deriveIv(long streamConnectionId) {
        return derive(IV_LABEL, streamConnectionId);
    }

    private static byte[] derive(String label, long streamConnectionId) {
        byte[] digest;
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(label.getBytes(ASCII));
            sha.update(Long.toString(streamConnectionId).getBytes(ASCII));
            sha.update(SECRET_PAD);
            digest = sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
        final byte[] out = new byte[16];
        System.arraycopy(digest, 0, out, 0, out.length);
        return out;
    }

    /**
     * Encrypts one plain-frame payload, continuing the same CTR keystream.
     * Must be called in stream order; the returned array has the same length.
     */
    public synchronized byte[] encrypt(byte[] payload) {
        final byte[] out = mCipher.update(payload);
        mBytes += payload.length;
        return out != null ? out : payload;
    }

    /** Total plaintext bytes fed to the cipher (the CTR offset is this / 16). */
    public synchronized long encryptedBytes() {
        return mBytes;
    }

    public byte[] key() {
        return mKey.clone();
    }

    public byte[] iv() {
        return mIv.clone();
    }
}
