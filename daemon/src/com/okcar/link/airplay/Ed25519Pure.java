/*
 * Ed25519Pure — RFC 8032 Ed25519 (PureEdDSA over edwards25519) in pure Java.
 *
 * WHY THIS EXISTS: Android 13 / API 33 ships Conscrypt, whose JCA provider implements X25519
 * (XDH) but NOT Ed25519.  AirPlayPairing therefore cannot rely on KeyPairGenerator/Signature
 * "Ed25519" on that device and falls back to this class.  Only java.* is used: BigInteger for
 * the field arithmetic and MessageDigest("SHA-512").  No android.*, no BouncyCastle, no
 * reflection.
 *
 * SECURITY POSTURE: this is an interoperability path, not a security boundary.  The
 * arithmetic below is deliberately NOT constant time (BigInteger, data-dependent branches in
 * point decoding); do not use it to protect secrets from a local timing attacker.  The
 * platform provider stays the first choice wherever it exists — AirPlayPairing keeps the JCA
 * code path and only falls back here when the provider is missing.
 *
 * Implements the RFC 8032 §5.1 rules directly:
 *   p = 2^255 - 19, twisted Edwards curve -x^2 + y^2 = 1 + d x^2 y^2, d = -121665/121666.
 *   Key generation: s = clamp(SHA-512(seed)[0..31]); A = [s]B; ENC(A) = y || sign(x).
 *   Sign:  r = SHA-512(prefix || M) mod L; R = [r]B;
 *          k = SHA-512(ENC(R) || ENC(A) || M) mod L; S = (r + k*s) mod L;
 *          signature = ENC(R) || S (little-endian).
 *   Verify: parse A, R, S — rejecting a non-canonical y >= p, the "negative zero" x encoding
 *           and S >= L — then check [S]B == R + [k]A.
 *
 * The nested PurePrivateKey/PurePublicKey types implement java.security.PrivateKey/PublicKey
 * with the exact RFC 8410 PKCS#8 and SubjectPublicKeyInfo encodings AirPlayPairing already
 * asserts on the JCA path, so the identity blob format is byte-identical in both backends.
 */
package com.okcar.link.airplay;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class Ed25519Pure {

    private Ed25519Pure() {
    }

    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger P = TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger TWO_D = D.add(D).mod(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** RFC 8032 §5.1 base point, x even. */
    private static final BigInteger BX = new BigInteger(
            "15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger BY = new BigInteger(
            "46316835694926478169428394003475163141307993866256225615783033603165251855960");
    private static final Point BASE = new Point(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P));

    // RFC 8410 DER wrappers (same bytes AirPlayPairing uses).
    private static final byte[] PKCS8_PREFIX = fromHex("302E020100300506032B657004220420");
    private static final byte[] SPKI_PREFIX = fromHex("302A300506032B6570032100");

    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------ public API

    /** Fresh 32-byte seed for a new identity. */
    public static byte[] generateSeed() {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        return seed;
    }

    /** RFC 8032 key generation: raw 32-byte public key ENC([clamp(SHA-512(seed))]B). */
    public static byte[] publicKeyFromSeed(byte[] seed) {
        checkSeed(seed);
        return encode(scalarMult(scalarFromSeed(seed), BASE));
    }

    /** RFC 8032 PureEdDSA signature (R || S, 64 bytes) of {@code message} under {@code seed}. */
    public static byte[] sign(byte[] message, byte[] seed) {
        checkSeed(seed);
        if (message == null) {
            throw new IllegalArgumentException("Ed25519 message must not be null");
        }
        byte[] h = sha512(seed);
        byte[] a = Arrays.copyOfRange(h, 0, 32);
        clamp(a);
        BigInteger s = leToInt(a);
        byte[] prefix = Arrays.copyOfRange(h, 32, 64);
        BigInteger r = leToInt(sha512(prefix, message)).mod(L);
        byte[] rEnc = encode(scalarMult(r, BASE));
        byte[] aEnc = encode(scalarMult(s, BASE));
        BigInteger k = leToInt(sha512(rEnc, aEnc, message)).mod(L);
        BigInteger S = r.add(k.multiply(s)).mod(L);
        byte[] out = new byte[64];
        System.arraycopy(rEnc, 0, out, 0, 32);
        System.arraycopy(intToLe(S, 32), 0, out, 32, 32);
        return out;
    }

    /**
     * RFC 8032 §5.1.7 verification.  Returns false for a malformed signature, an invalid or
     * non-canonical public key, or an out-of-range S — it never accepts one silently.
     */
    public static boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
        if (message == null || signature == null || publicKey == null) {
            return false;
        }
        if (signature.length != 64 || publicKey.length != 32) {
            return false;
        }
        byte[] rEnc = Arrays.copyOfRange(signature, 0, 32);
        Point a;
        Point r;
        try {
            a = decode(publicKey);
            r = decode(rEnc);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        BigInteger S = leToInt(Arrays.copyOfRange(signature, 32, 64));
        if (S.compareTo(L) >= 0) {
            return false;
        }
        BigInteger k = leToInt(sha512(rEnc, publicKey, message)).mod(L);
        return equal(scalarMult(S, BASE), add(r, scalarMult(k, a)));
    }

    /** Seed hidden inside an RFC 8410 Ed25519 PKCS#8 blob (prefix || 32-byte seed). */
    public static byte[] seedFromPkcs8(byte[] pkcs8) {
        byte[] seed = Arrays.copyOfRange(requirePkcs8(pkcs8), PKCS8_PREFIX.length, PKCS8_PREFIX.length + 32);
        return seed;
    }

    // ------------------------------------------------------------------ java.security keys

    /** PKCS#8-backed Ed25519 private key (encoded exactly as the JCA providers emit it). */
    public static final class PurePrivateKey implements java.security.PrivateKey {
        private static final long serialVersionUID = 1L;
        private final byte[] seed;

        PurePrivateKey(byte[] seed) {
            this.seed = seed.clone();
        }

        /** The 32-byte RFC 8032 seed (private key) behind this key. */
        public byte[] seed() {
            return seed.clone();
        }

        public String getAlgorithm() {
            return "Ed25519";
        }

        public String getFormat() {
            return "PKCS#8";
        }

        public byte[] getEncoded() {
            return concat(PKCS8_PREFIX, seed);
        }
    }

    /** X.509/SPKI-backed Ed25519 public key. */
    public static final class PurePublicKey implements java.security.PublicKey {
        private static final long serialVersionUID = 1L;
        private final byte[] raw;

        PurePublicKey(byte[] raw) {
            this.raw = raw.clone();
        }

        /** The raw 32-byte ENC(A) encoding. */
        public byte[] raw() {
            return raw.clone();
        }

        public String getAlgorithm() {
            return "Ed25519";
        }

        public String getFormat() {
            return "X.509";
        }

        public byte[] getEncoded() {
            return concat(SPKI_PREFIX, raw);
        }
    }

    /** Wrap a 32-byte seed as a private key; validates nothing beyond the length. */
    public static PurePrivateKey privateKey(byte[] seed) {
        checkSeed(seed);
        return new PurePrivateKey(seed);
    }

    /** Wrap a raw 32-byte public key, rejecting an encoding that is not a canonical point. */
    public static PurePublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        decode(raw); // throws IllegalArgumentException on a non-canonical / off-curve encoding
        return new PurePublicKey(raw);
    }

    /**
     * Rebuild a private key from its PKCS#8 encoding.  Accepts the exact 48-byte RFC 8410
     * encoding and any longer OneAsymmetricKey form that appends the public key.
     */
    public static PurePrivateKey privateKeyFromPkcs8(byte[] pkcs8) {
        byte[] seed = Arrays.copyOfRange(requirePkcs8(pkcs8), PKCS8_PREFIX.length, PKCS8_PREFIX.length + 32);
        return new PurePrivateKey(seed);
    }

    // ------------------------------------------------------------------ field / curve internals

    private static final class Point {
        final BigInteger x;
        final BigInteger y;
        final BigInteger z;
        final BigInteger t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    /** Complete addition for a = -1 twisted Edwards points in extended coordinates. */
    private static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = p.t.multiply(TWO_D).mod(P).multiply(q.t).mod(P);
        BigInteger d = p.z.multiply(q.z).mod(P).multiply(TWO).mod(P);
        BigInteger e = b.subtract(a).mod(P);
        BigInteger f = d.subtract(c).mod(P);
        BigInteger g = d.add(c).mod(P);
        BigInteger h = b.add(a).mod(P);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    private static Point scalarMult(BigInteger k, Point p) {
        Point r = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) {
                r = add(r, p);
            }
        }
        return r;
    }

    private static boolean equal(Point p, Point q) {
        return p.x.multiply(q.z).mod(P).equals(q.x.multiply(p.z).mod(P))
                && p.y.multiply(q.z).mod(P).equals(q.y.multiply(p.z).mod(P));
    }

    /** ENC(P) = little-endian(y) with the low bit of x copied into the top bit. */
    private static byte[] encode(Point p) {
        BigInteger zInv = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zInv).mod(P);
        BigInteger y = p.y.multiply(zInv).mod(P);
        byte[] out = intToLe(y, 32);
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /**
     * RFC 8032 §5.1.3 decoding.  Rejects y >= p, a non-square x^2, and the non-canonical
     * encoding of x = 0 with the sign bit set.
     */
    private static Point decode(byte[] enc) {
        if (enc.length != 32) {
            throw new IllegalArgumentException("Ed25519 point must be 32 bytes");
        }
        byte[] yBytes = enc.clone();
        int sign = (yBytes[31] >> 7) & 1;
        yBytes[31] &= 0x7F;
        BigInteger y = leToInt(yBytes);
        if (y.compareTo(P) >= 0) {
            throw new IllegalArgumentException("non-canonical Ed25519 point: y >= p");
        }
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        BigInteger x = sqrtRatio(u, v);
        if (x == null) {
            throw new IllegalArgumentException("Ed25519 point is not on the curve");
        }
        if (x.signum() == 0 && sign != 0) {
            throw new IllegalArgumentException("non-canonical Ed25519 point: negative zero x");
        }
        if (x.testBit(0) != (sign == 1)) {
            x = P.subtract(x);
        }
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    /** sqrt(u/v) mod p (p = 5 mod 8), or null when u/v is not a square. */
    private static BigInteger sqrtRatio(BigInteger u, BigInteger v) {
        BigInteger uu = u.mod(P);
        BigInteger v3 = v.modPow(BigInteger.valueOf(3), P);
        BigInteger v7 = v.modPow(BigInteger.valueOf(7), P);
        BigInteger exponent = P.subtract(BigInteger.valueOf(5)).shiftRight(3);
        BigInteger x = uu.multiply(v3).mod(P)
                .multiply(uu.multiply(v7).mod(P).modPow(exponent, P)).mod(P);
        BigInteger vxx = v.multiply(x).mod(P).multiply(x).mod(P);
        if (vxx.equals(uu)) {
            return x;
        }
        if (vxx.equals(P.subtract(uu).mod(P))) {
            return x.multiply(SQRT_M1).mod(P);
        }
        return null;
    }

    private static BigInteger scalarFromSeed(byte[] seed) {
        byte[] h = sha512(seed);
        byte[] a = Arrays.copyOfRange(h, 0, 32);
        clamp(a);
        return leToInt(a);
    }

    /** RFC 8032 §5.1.5 scalar clamping. */
    private static void clamp(byte[] a) {
        a[0] &= (byte) 248;
        a[31] &= (byte) 127;
        a[31] |= (byte) 64;
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] requirePkcs8(byte[] pkcs8) {
        if (pkcs8 == null || pkcs8.length < PKCS8_PREFIX.length + 32
                || !startsWith(pkcs8, PKCS8_PREFIX)) {
            throw new IllegalArgumentException("not a usable Ed25519 PKCS#8 key");
        }
        return pkcs8;
    }

    private static void checkSeed(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("Ed25519 seed must be 32 bytes, got "
                    + (seed == null ? "absent" : seed.length));
        }
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is unavailable", e);
        }
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

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
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
