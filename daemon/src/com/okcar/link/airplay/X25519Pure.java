/*
 * X25519Pure — RFC 7748 X25519 (Curve25519 Diffie-Hellman) in pure Java.
 *
 * Companion to Ed25519Pure: Android 13's Conscrypt does implement X25519 (XDH), so
 * AirPlayPairing normally keeps the JCA KeyAgreement path.  This class is the documented
 * fallback for any device where the JCA XDH/X25519 lookup or key construction fails, and it
 * is what the self-test cross-checks against the provider.  Only java.* is used: BigInteger
 * for the field arithmetic and SecureRandom for key generation.  No android.*, no
 * BouncyCastle, no reflection.
 *
 * SECURITY POSTURE: interoperability path, not a security boundary — the Montgomery ladder
 * below is not constant time (BigInteger).  Prefer the platform provider where it exists.
 *
 * Implements RFC 7748 §5 exactly:
 *   p = 2^255 - 19, u-coordinate arithmetic mod p, scalar clamped as
 *   k[0] &= 248; k[31] &= 127; k[31] |= 64, the Montgomery ladder with a24 = 121665, the
 *   unused top bit of the peer u-coordinate masked off (non-canonical u accepted), and the
 *   final x_2 * z_2^(p-2).
 */
package com.okcar.link.airplay;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

public final class X25519Pure {

    private X25519Pure() {
    }

    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger P = TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE_POINT = new byte[32];
    static {
        BASE_POINT[0] = 9; // RFC 7748 base point u = 9
    }

    // RFC 8410 DER wrappers (same bytes AirPlayPairing uses).
    private static final byte[] PKCS8_PREFIX = fromHex("302E020100300506032B656E04220420");
    private static final byte[] SPKI_PREFIX = fromHex("302A300506032B656E032100");

    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------ public API

    /** A random scalar plus its public key, both copied out defensively. */
    public static final class XKeyPair {
        private final byte[] scalar;
        private final byte[] publicKey;

        XKeyPair(byte[] scalar, byte[] publicKey) {
            this.scalar = scalar.clone();
            this.publicKey = publicKey.clone();
        }

        /** The clamped 32-byte scalar (private key). */
        public byte[] privateScalar() {
            return scalar.clone();
        }

        /** The 32-byte u-coordinate of [k]B. */
        public byte[] publicKey() {
            return publicKey.clone();
        }

        public java.security.PrivateKey asPrivateKey() {
            return new PurePrivateKey(scalar);
        }

        public java.security.PublicKey asPublicKey() {
            return new PurePublicKey(publicKey);
        }
    }

    /** RFC 7748 key generation: a random 32-byte scalar and its public key. */
    public static XKeyPair generateKeyPair() {
        byte[] scalar = new byte[32];
        RANDOM.nextBytes(scalar);
        return new XKeyPair(scalar, publicKeyFromSecret(scalar));
    }

    /** X25519(secret, 9): the public key for a scalar.  The scalar is clamped here. */
    public static byte[] publicKeyFromSecret(byte[] privateScalar) {
        return scalarMult(clamp(require32(privateScalar, "X25519 private scalar")), BASE_POINT);
    }

    /**
     * RFC 7748 X25519 shared secret.  The private scalar is clamped here (so a caller need not
     * have done it) and the peer's unused high bit is masked off; a non-canonical peer value is
     * accepted, as the RFC requires.  A low-order peer yields the all-zero secret — callers
     * MUST reject it, exactly as AirPlayPairing.x25519 does.
     */
    public static byte[] sharedSecret(byte[] privateScalar, byte[] peerPublic) {
        byte[] scalar = clamp(require32(privateScalar, "X25519 private scalar"));
        byte[] u = require32(peerPublic, "X25519 peer public key").clone();
        u[31] &= 0x7F;
        return scalarMult(scalar, u);
    }

    // ------------------------------------------------------------------ java.security keys

    /** PKCS#8-backed X25519 private key (RFC 8410 encoding). */
    public static final class PurePrivateKey implements java.security.PrivateKey {
        private static final long serialVersionUID = 1L;
        private final byte[] scalar;

        PurePrivateKey(byte[] scalar) {
            this.scalar = scalar.clone();
        }

        /** The clamped 32-byte scalar. */
        public byte[] scalar() {
            return scalar.clone();
        }

        public String getAlgorithm() {
            return "X25519";
        }

        public String getFormat() {
            return "PKCS#8";
        }

        public byte[] getEncoded() {
            return concat(PKCS8_PREFIX, scalar);
        }
    }

    /** X.509/SPKI-backed X25519 public key (RFC 8410 encoding). */
    public static final class PurePublicKey implements java.security.PublicKey {
        private static final long serialVersionUID = 1L;
        private final byte[] raw;

        PurePublicKey(byte[] raw) {
            this.raw = raw.clone();
        }

        /** The raw 32-byte u-coordinate. */
        public byte[] raw() {
            return raw.clone();
        }

        public String getAlgorithm() {
            return "X25519";
        }

        public String getFormat() {
            return "X.509";
        }

        public byte[] getEncoded() {
            return concat(SPKI_PREFIX, raw);
        }
    }

    // ------------------------------------------------------------------ RFC 7748 core

    /** RFC 7748 §5 scalar clamping, applied to a copy. */
    static byte[] clamp(byte[] k) {
        byte[] out = k.clone();
        out[0] &= (byte) 248;
        out[31] &= (byte) 127;
        out[31] |= (byte) 64;
        return out;
    }

    /**
     * The RFC 7748 Montgomery ladder.  {@code k} must be clamped (32 bytes), {@code uBytes}
     * is the peer's little-endian u-coordinate with its top bit already masked.
     */
    private static byte[] scalarMult(byte[] k, byte[] uBytes) {
        BigInteger x1 = leToInt(uBytes).mod(P);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int bit = (k[t >> 3] >> (t & 7)) & 1;
            swap ^= bit;
            if (swap == 1) {
                BigInteger tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = bit;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger daPlusCb = da.add(cb).mod(P);
            BigInteger daMinusCb = da.subtract(cb).mod(P);
            x3 = daPlusCb.multiply(daPlusCb).mod(P);
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp = x2;
            x2 = x3;
            x3 = tmp;
            tmp = z2;
            z2 = z3;
            z3 = tmp;
        }
        return intToLe(x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P), 32);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] require32(byte[] value, String what) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(what + " must be 32 bytes, got "
                    + (value == null ? "absent" : value.length));
        }
        return value;
    }

    private static BigInteger leToInt(byte[] little) {
        byte[] big = new byte[little.length];
        for (int i = 0; i < little.length; i++) {
            big[i] = little[little.length - 1 - i];
        }
        return new BigInteger(1, big);
    }

    private static byte[] intToLe(BigInteger value, int length) {
        byte[] out = new byte[length];
        BigInteger mask = BigInteger.valueOf(0xFF);
        for (int i = 0; i < length; i++) {
            out[i] = value.shiftRight(8 * i).and(mask).byteValue();
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
