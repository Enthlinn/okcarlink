/*
 * OkcarOS link stack — L4 wireless: AirPlay-2 (wireless CarPlay) *sender-side* pairing and
 * encrypted-channel crypto.  We play the iPhone/controller role; the accessory is the head
 * unit listening on TCP 7000.
 *
 * PURE JAVA on purpose: java.*, javax.crypto.* and java.security.* only — no android.*,
 * no BouncyCastle — so the same source compiles into OkCarLink (Android 13) and runs on a
 * host JVM for AirPlayPairingSelfTest.  Randomness is java.security.SecureRandom; hex is
 * hand-rolled; there is no android.util.Base64 anywhere.
 *
 * Uses the JCA provider for RFC 8410 Ed25519/X25519 and IETF ChaCha20-Poly1305 wherever it
 * provides them (OpenJDK 17: SunEC/SunJCE).  Android 13 / API 33 Conscrypt implements X25519
 * but NOT Ed25519, so the Ed25519 helpers fall back to Ed25519Pure, and X25519 falls back to
 * X25519Pure if a provider lacks XDH; both are plain-Java classes in this package (no
 * android.*, no BouncyCastle) and the JCA path always stays first where it exists.
 *
 * Authority: the internal notes (byte-level, each claim
 * carries a repo/path:line or URL), cross-checked against the real wire trace in
 * the internal notes  Every place the reference marks
 * something [UNCERTAIN] is called out inline with "UNCERTAIN:".
 *
 * The SRP formulas below were additionally diffed line-by-line against the accessory we are
 * aiming at, shilapi/xcertplay `shared/.../airplay/Srp6a.kt` @1.2.4 (the receiver that accepted
 * every real-iPhone M1 in wp3-*.pcap) and against postlund/pyatv `pyatv/auth/hap_srp.py` (the
 * controller side).  The one place those two disagree (integrity of S into K) is decided in
 * favour of xcertplay, with the reasoning in pairSetupM3.
 *
 * Out of scope here (deliberately, it is a separate message on the same channel): the MFi
 * POST /auth-setup round (reference §5), which must be sent over the control channel *after*
 * pair-verify M4.  pair-verify M4 is the point where this class hands over a sealed channel.
 *
 * ---------------------------------------------------------------------------------------
 * FINAL PUBLIC API
 *
 *   interface IdentityStore { byte[] load(); void save(byte[] privateKeyPkcs8AndMeta); }
 *   AirPlayPairing(IdentityStore store)          // store==null -> in-memory identity
 *
 *   // pair-setup (SRP-6a, RFC 5054 3072-bit group, SHA-512, "Pair-Setup"/"3939", TLV8)
 *   byte[] pairSetupM1()                         // -> body to POST /pair-setup
 *   byte[] pairSetupM3(byte[] m2)                // consume M2 -> body to POST
 *   byte[] pairSetupM5(byte[] m4)                // consume M4, verify accessory proof -> body
 *   void   pairSetupM6(byte[] m6)                // consume M6, verify + store accessory LTPK
 *
 *   // pair-verify (ephemeral X25519 + Ed25519; we are the controller)
 *   byte[] pairVerifyM1()                        // -> body to POST /pair-verify
 *   byte[] pairVerifyM3(byte[] m2)               // consume M2, verify accessory sig -> body
 *   void   pairVerifyM4(byte[] m4)               // consume M4 -> derive + install all keys
 *
 *   // sealed channels: [u16 LE len(ciphertext)][ciphertext][16-byte tag], AAD = the 2 len bytes,
 *   // nonce = 4 zero bytes || u64 LE counter from 0 per direction.  Plaintext > 0x4000 must be
 *   // chunked by the caller (or use the *Chunked helpers); the length field excludes the tag.
 *   byte[] sealControl(byte[] plaintext)         byte[] unsealControl(byte[] sealed)
 *   byte[] sealEvent(byte[] plaintext)           byte[] unsealEvent(byte[] sealed)
 *   List<byte[]> sealControlChunked(byte[] pt)   List<byte[]> sealEventChunked(byte[] pt)
 *   byte[] controlWriteKey() byte[] controlReadKey()   // send / receive on the control channel
 *   byte[] eventWriteKey()   byte[] eventReadKey()     // send / receive on the event channel;
 *                                                      // the Events-* labels are INVERTED
 *   byte[] dataStreamOutputKey(long streamOrSeed)      // media data-stream key (screen, audio,
 *                                                      // and the type-130 iAP2 tunnel); the value
 *                                                      // is rendered UNSIGNED into the salt
 *
 *   byte[] accessoryPairingId()                  byte[] accessoryLongTermPublicKey()
 *   byte[] clientPairingId()                     byte[] clientLongTermPublicKey()
 *   boolean isPaired()                           // pair-setup M6 verified an accessory
 *   boolean channelsReady()                      // pair-verify M4 installed the channel keys
 *   String describe()                            // log-safe: never prints secret material
 *   static byte[] infoRequest(int cseq)
 *
 *   // package-private (not integration API): per-direction frame counters used by the self-test
 *   long controlWriteCounter() controlReadCounter() eventWriteCounter() eventReadCounter()
 *
 *   // TLV8 (fragmentation at 255 bytes; 0xFF items are skipped as separators on parse)
 *   static byte[] tlv(int type, byte[] value)
 *   static byte[] tlvJoin(byte[]... encodedTlvs)
 *   static Map<Integer, byte[]> parseTlv(byte[] tlv8)
 *
 *   // primitives exposed for the session layer and for the self-test
 *   static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length)
 *   static byte[] hkdfSha512(byte[] ikm, String salt, String info, int length)
 *   static byte[] srpComputeX(byte[] salt, String username, String password)
 *   static final BigInteger SRP_N, SRP_G; static final int SRP_KEY_BYTES;
 *   static final String SRP_USERNAME, PAIR_SETUP_PIN;
 *   static final String USER_AGENT, CONTENT_TYPE_PAIR_SETUP, CONTENT_TYPE_PAIR_VERIFY;
 *   static final int HKP_PAIR_SETUP, HKP_PAIR_VERIFY, MAX_PLAINTEXT;
 *   static final int TLV_*;
 *   final class Identity { encode/decode/withAccessory/describe }
 * ---------------------------------------------------------------------------------------
 */

package com.okcar.link.airplay;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AirPlayPairing {

    // ------------------------------------------------------------------ protocol constants

    public static final String USER_AGENT = "AirPlay/710.79.1";
    public static final String CONTENT_TYPE_PAIR_SETUP = "application/x-apple-binary-plist";
    public static final String CONTENT_TYPE_PAIR_VERIFY = "application/octet-stream";
    /** X-Apple-HKP value on the three /pair-setup requests of a real iPhone. */
    public static final int HKP_PAIR_SETUP = 0;
    /** X-Apple-HKP value on both /pair-verify requests of a real iPhone. */
    public static final int HKP_PAIR_VERIFY = 2;

    // TLV8 types (reference §1 "TLV8 codec").
    public static final int TLV_METHOD = 0x00;
    public static final int TLV_IDENTIFIER = 0x01;
    public static final int TLV_SALT = 0x02;
    public static final int TLV_PUBLIC_KEY = 0x03;
    public static final int TLV_PROOF = 0x04;
    public static final int TLV_ENCRYPTED_DATA = 0x05;
    public static final int TLV_STATE = 0x06;
    public static final int TLV_ERROR = 0x07;
    public static final int TLV_SIGNATURE = 0x0A;
    /** Not a real type: the `0xFF,0x00` separator some encoders put between fragments. */
    public static final int TLV_SEPARATOR = 0xFF;

    public static final int METHOD_PAIR_SETUP = 0x00;
    public static final int METHOD_PAIR_VERIFY = 0x02;

    public static final int STATE_M1 = 1;
    public static final int STATE_M2 = 2;
    public static final int STATE_M3 = 3;
    public static final int STATE_M4 = 4;
    public static final int STATE_M5 = 5;
    public static final int STATE_M6 = 6;

    public static final String SRP_USERNAME = "Pair-Setup";
    public static final String PAIR_SETUP_PIN = "3939";

    /**
     * SRP-6a modulus: the RFC 5054 3072-bit group, which is RFC 3526 MODP group 15.
     * Verified byte-for-byte against `openssl genpkey -genparam -algorithm DH
     * -pkeyopt group:modp_3072` (see the recorded SRP vectors).
     */
    public static final BigInteger SRP_N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
          + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406"
          + "B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8"
          + "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C329"
          + "05E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956"
          + "AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA7157"
          + "5D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8"
          + "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD1"
          + "08E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    public static final BigInteger SRP_G = BigInteger.valueOf(5);
    /** SRP public keys A/B, and PAD(N)/PAD(g), are 384 bytes for the 3072-bit group. */
    public static final int SRP_KEY_BYTES = 384;
    /** SHA-512 output: K, M1, M2, x, u are all 64 bytes (never minimal-length encoded). */
    public static final int SRP_HASH_BYTES = 64;

    /** 16 zero bytes || u64 LE counter. */
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;

    /**
     * Largest plaintext we will put in one sealed frame.  The real iPhone chunks at 16384
     * (0x4000) — its 30613-byte /info response arrives as L=16384 + L=14229 — and xcertplay's
     * receiver uses the same MAX_PAYLOAD.  UNCERTAIN (reference §4): HAP's "book" value is
     * 1024 and airplay2-receiver/pyatv use 1024, so a receiver may be stricter than Apple.
     * If a head unit rejects 0x4000-byte frames, lower this (or send <=1024) — the framing is
     * length-driven, so any chunk size on the wire is decodable by the peer.
     */
    public static final int MAX_PLAINTEXT = 0x4000;

    // HKDF labels (reference §2/§3/§4).  All ASCII, no terminators.
    private static final String PAIR_SETUP_ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt";
    private static final String PAIR_SETUP_ENCRYPT_INFO = "Pair-Setup-Encrypt-Info";
    private static final String PAIR_SETUP_CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt";
    private static final String PAIR_SETUP_CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info";
    private static final String PAIR_SETUP_ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt";
    private static final String PAIR_SETUP_ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info";
    private static final String PAIR_VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt";
    private static final String PAIR_VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info";
    private static final String CONTROL_SALT = "Control-Salt";
    private static final String CONTROL_WRITE_INFO = "Control-Write-Encryption-Key";
    private static final String CONTROL_READ_INFO = "Control-Read-Encryption-Key";
    private static final String EVENTS_SALT = "Events-Salt";
    private static final String EVENTS_WRITE_INFO = "Events-Write-Encryption-Key";
    private static final String EVENTS_READ_INFO = "Events-Read-Encryption-Key";

    // ChaCha20-Poly1305 nonces for the pairing messages: 4 zero bytes || 8 ASCII bytes.
    private static final String NONCE_PS_MSG05 = "PS-Msg05";
    private static final String NONCE_PS_MSG06 = "PS-Msg06";
    private static final String NONCE_PV_MSG02 = "PV-Msg02";
    private static final String NONCE_PV_MSG03 = "PV-Msg03";

    // RFC 8410 DER wrappers.  Constructing keys from these instead of the API-33
    // EdECPoint/XECPublicKey interfaces keeps the raw-key handling explicit and identical on
    // Conscrypt and SunEC, and lets us assert that a provider returned the exact encoding.
    private static final byte[] PKCS8_ED25519_PREFIX = fromHex("302E020100300506032B657004220420");
    private static final byte[] PKCS8_X25519_PREFIX = fromHex("302E020100300506032B656E04220420");
    private static final byte[] SPKI_ED25519_PREFIX = fromHex("302A300506032B6570032100");
    private static final byte[] SPKI_X25519_PREFIX = fromHex("302A300506032B656E032100");

    private static final byte[] MAGIC = {'O', 'K', 'P', '1'};
    private static final int IDENTITY_VERSION = 1;
    private static final int PAIRING_ID_BYTES = 36; // UUID string, as the real iPhone uses

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Resolved once; null when the JCA provider offers neither XDH nor the X25519 alias. */
    private static final String XDH_ALGORITHM = firstAvailable("KeyAgreement", "XDH", "X25519");

    /**
     * Which backend each primitive is using.  The JCA path is always preferred and is what the
     * host self-test exercises; the pure implementations below are the fallback for providers
     * without the algorithm (Android 13 Conscrypt has X25519 but no Ed25519).  Reported by
     * {@link #describe()} as ed25519=jca|pure, x25519=jca|pure.
     */
    private static final boolean ED25519_JCA = jcaEd25519Available();
    private static final boolean X25519_JCA = XDH_ALGORITHM != null;

    /** Self-test hook: force the pure backends even where the provider also has them. */
    private static volatile boolean sForcePureBackends;

    static void forcePureBackendsForTest(boolean force) {
        sForcePureBackends = force;
    }

    private static boolean useJcaEd25519() {
        return ED25519_JCA && !sForcePureBackends;
    }

    private static boolean useJcaX25519() {
        return X25519_JCA && !sForcePureBackends;
    }

    /** "jca" or "pure" — log-safe. */
    static String ed25519Backend() {
        return useJcaEd25519() ? "jca" : "pure";
    }

    /** "jca" or "pure" — log-safe. */
    static String x25519Backend() {
        return useJcaX25519() ? "jca" : "pure";
    }

    /**
     * True only when the JCA provider can actually <em>use</em> Ed25519 end to end: the
     * algorithms exist <em>and</em> a real key pair comes back in the PKCS#8 shape the pairing
     * identity persists.
     *
     * <p>Checking {@code getInstance} alone is not enough.  On 217 (MIUI/Android 15) the
     * AndroidKeyStore provider answers {@code getInstance("Ed25519")} and then throws
     * {@code IllegalStateException: Not initialized} from {@code generateKeyPair()} — a
     * RuntimeException, so it escaped the {@code GeneralSecurityException} fallback and killed
     * the handshake right after a successful {@code /info} (2026-09-29 field run).  A throwaway
     * key pair here costs a few milliseconds once and stops every later call from rediscovering
     * the same failure mid-session.
     */
    private static boolean jcaEd25519Available() {
        try {
            KeyPairGenerator.getInstance("Ed25519");
            Signature.getInstance("Ed25519");
            KeyFactory.getInstance("Ed25519");
            final KeyPair probe = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            final byte[] pkcs8 = probe.getPrivate().getEncoded();
            if (pkcs8 == null || !startsWith(pkcs8, PKCS8_ED25519_PREFIX)) {
                return false;       /* a provider that cannot encode what Identity persists */
            }
            return true;
        } catch (GeneralSecurityException unavailable) {
            return false;
        } catch (RuntimeException broken) {
            /* A provider that advertises the algorithm and then refuses to run (AndroidKeyStore
             * on this build): use RFC 8032 in plain Java instead of failing the session. */
            return false;
        }
    }

    // ------------------------------------------------------------------ persistence hook

    /** Caller-supplied persistence for our long-term Ed25519 key + pairing id. */
    public interface IdentityStore {
        /** @return a blob previously returned by {@link Identity#encode()}, or null/empty. */
        byte[] load();

        /** Persist the blob; called once for a freshly generated identity and again after M6. */
        void save(byte[] privateKeyPkcs8AndMeta);
    }

    /**
     * Our long-term identity plus what we learned about the accessory.
     *
     * Serialised form (all integers big-endian):
     *   "OKP1" | u8 version=1 | u8 idLen | pairingId | u16 pkcs8Len | pkcs8 | u8 ltpkLen | ltpk
     *         | u8 accIdLen | accId | u8 accLtpkLen | accLtpk
     * accIdLen/accLtpkLen are 0 until pair-setup M6 has been verified.
     */
    public static final class Identity {
        public final byte[] pairingId;
        /** PKCS#8 DER of our Ed25519 long-term signing key. */
        public final byte[] privateKeyPkcs8;
        /** Raw 32-byte Ed25519 long-term *public* key (stored so we never have to derive it). */
        public final byte[] longTermPublicKey;
        public final byte[] accessoryPairingId;         // null until paired
        public final byte[] accessoryLongTermPublicKey; // null until paired

        Identity(byte[] pairingId, byte[] privateKeyPkcs8, byte[] longTermPublicKey,
                 byte[] accessoryPairingId, byte[] accessoryLongTermPublicKey) {
            this.pairingId = pairingId;
            this.privateKeyPkcs8 = privateKeyPkcs8;
            this.longTermPublicKey = longTermPublicKey;
            this.accessoryPairingId = accessoryPairingId;
            this.accessoryLongTermPublicKey = accessoryLongTermPublicKey;
        }

        static Identity generate() {
            KeyPair pair = ed25519KeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            if (pkcs8 == null || !startsWith(pkcs8, PKCS8_ED25519_PREFIX)) {
                throw new IllegalStateException("Ed25519 provider returned an unexpected PKCS#8 encoding");
            }
            byte[] ltpk = rawFromSpki(pair.getPublic(), SPKI_ED25519_PREFIX, "Ed25519");
            byte[] id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            if (id.length != PAIRING_ID_BYTES) {
                throw new IllegalStateException("unexpected pairing-id length " + id.length);
            }
            return new Identity(id, pkcs8, ltpk, null, null);
        }

        Identity withAccessory(byte[] accId, byte[] accLtpk) {
            return new Identity(pairingId, privateKeyPkcs8, longTermPublicKey, accId, accLtpk);
        }

        public byte[] encode() {
            require(accessoryPairingId == null || accessoryPairingId.length <= 0xFF,
                    "accessory pairing id too long");
            require(accessoryLongTermPublicKey == null || accessoryLongTermPublicKey.length <= 0xFF,
                    "accessory long-term key too long");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC, 0, MAGIC.length);
            out.write(IDENTITY_VERSION);
            require(pairingId.length <= 0xFF, "pairing id too long");
            out.write(pairingId.length);
            out.write(pairingId, 0, pairingId.length);
            out.write((privateKeyPkcs8.length >>> 8) & 0xFF);
            out.write(privateKeyPkcs8.length & 0xFF);
            out.write(privateKeyPkcs8, 0, privateKeyPkcs8.length);
            require(longTermPublicKey.length <= 0xFF, "long-term public key too long");
            out.write(longTermPublicKey.length);
            out.write(longTermPublicKey, 0, longTermPublicKey.length);
            byte[] accId = accessoryPairingId == null ? new byte[0] : accessoryPairingId;
            byte[] accLtpk = accessoryLongTermPublicKey == null ? new byte[0] : accessoryLongTermPublicKey;
            out.write(accId.length);
            out.write(accId, 0, accId.length);
            out.write(accLtpk.length);
            out.write(accLtpk, 0, accLtpk.length);
            return out.toByteArray();
        }

        public static Identity decode(byte[] blob) {
            if (blob == null || blob.length == 0) {
                return null;
            }
            require(blob.length >= MAGIC.length + 2, "identity blob too short");
            for (int i = 0; i < MAGIC.length; i++) {
                require(blob[i] == MAGIC[i], "identity blob has the wrong magic");
            }
            int off = MAGIC.length;
            require((blob[off] & 0xFF) == IDENTITY_VERSION,
                    "unsupported identity version " + (blob[off] & 0xFF));
            off++;
            int idLen = blob[off++] & 0xFF;
            require(idLen > 0 && off + idLen <= blob.length, "bad pairing-id length in identity blob");
            byte[] id = Arrays.copyOfRange(blob, off, off + idLen);
            off += idLen;
            require(off + 2 <= blob.length, "truncated identity blob (pkcs8 length)");
            int pkLen = ((blob[off] & 0xFF) << 8) | (blob[off + 1] & 0xFF);
            off += 2;
            require(pkLen > 0 && off + pkLen <= blob.length, "bad PKCS#8 length in identity blob");
            byte[] pkcs8 = Arrays.copyOfRange(blob, off, off + pkLen);
            off += pkLen;
            require(off + 1 <= blob.length, "truncated identity blob (long-term public key length)");
            int ltpkLen = blob[off++] & 0xFF;
            require(ltpkLen == 32 && off + ltpkLen <= blob.length,
                    "identity blob must hold a 32-byte Ed25519 long-term public key");
            byte[] ltpk = Arrays.copyOfRange(blob, off, off + ltpkLen);
            off += ltpkLen;
            require(off + 1 <= blob.length, "truncated identity blob (accessory id length)");
            int accIdLen = blob[off++] & 0xFF;
            require(off + accIdLen + 1 <= blob.length, "truncated identity blob (accessory id)");
            byte[] accId = accIdLen == 0 ? null : Arrays.copyOfRange(blob, off, off + accIdLen);
            off += accIdLen;
            int accLtpkLen = blob[off++] & 0xFF;
            require(off + accLtpkLen == blob.length, "truncated identity blob (accessory LTPK)");
            byte[] accLtpk = accLtpkLen == 0 ? null : Arrays.copyOfRange(blob, off, off + accLtpkLen);
            require(accLtpk == null || accLtpk.length == 32,
                    "accessory long-term public key must be 32 bytes, got " + accLtpkLen);
            require((accId == null) == (accLtpk == null),
                    "identity blob has one half of the accessory identity");
            require(startsWith(pkcs8, PKCS8_ED25519_PREFIX),
                    "identity blob does not hold an Ed25519 PKCS#8 key");
            // Fail loudly here rather than at the first signature if the key is unusable.
            ed25519Private(pkcs8);
            ed25519Public(ltpk);
            return new Identity(id, pkcs8, ltpk, accId, accLtpk);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Identity[clientId=");
            sb.append(ascii(pairingId));
            sb.append(", clientLtPub=").append(longTermPublicKey.length).append("B");
            if (accessoryPairingId == null) {
                sb.append(", accessory=<not paired>");
            } else {
                sb.append(", accessoryId=").append(ascii(accessoryPairingId));
                sb.append(", accessoryLtPub=").append(accessoryLongTermPublicKey.length).append("B");
            }
            return sb.append(']').toString();
        }
    }

    // ------------------------------------------------------------------ instance state

    private enum State { NEW, SETUP_M1, SETUP_M3, SETUP_M5, VERIFY_M1, VERIFY_M3, PAIRED }

    private final IdentityStore mStore;
    private Identity mIdentity;
    private State mState = State.NEW;

    // pair-setup / SRP
    private BigInteger mSrpSecret;     // our secret exponent a
    private byte[] mSrpM2;             // the proof we expect back
    private byte[] mSrpSessionKey;     // K = H(minimal(S)), the SRP session key
    private byte[] mPairSetupEncryptKey;

    // pair-verify
    private KeyPair mEphemeralX25519;
    private byte[] mVerifyShared;
    private byte[] mVerifyEncryptKey;
    /**
     * Copy of {@link #mVerifyShared} kept past pair-verify M4.  The media/data-stream keys
     * (screen, audio and the type-130 iAP2 tunnel of the wireless sender) are derived from the
     * same shared secret, but only *after* M4 has wiped the handshake copy.  This is the
     * "stored pair-verify shared secret" the session layer needs for
     * {@link #dataStreamOutputKey(long)}; memory-only, wiped by {@link #resetHandshake()} and
     * by the next {@link #pairVerifyM1()}, never printed.
     */
    private byte[] mDataStreamShared;

    // sealed channels (null until pair-verify M4)
    private Channel mControl;
    private Channel mEvent;

    public AirPlayPairing(IdentityStore store) {
        mStore = store;
        byte[] blob = store == null ? null : store.load();
        if (blob == null || blob.length == 0) {
            mIdentity = Identity.generate();
            if (store != null) {
                store.save(mIdentity.encode());
            }
        } else {
            mIdentity = Identity.decode(blob);
            if (mIdentity == null) {
                throw new IllegalArgumentException("identity store returned an empty blob after all");
            }
        }
    }

    // ------------------------------------------------------------------ pair-setup

    /**
     * M1: TLV Method=PairSetup, State=1 — six bytes, exactly `00 01 00 06 01 01` on the wire,
     * as the real iPhone sends it (reference §8 step 2).
     */
    public byte[] pairSetupM1() {
        requireState("pairSetupM1", State.NEW, State.PAIRED);
        resetHandshake();
        mState = State.SETUP_M1;
        return tlvJoin(tlv(TLV_METHOD, new byte[] {METHOD_PAIR_SETUP}),
                       tlv(TLV_STATE, new byte[] {STATE_M1}));
    }

    /**
     * M3: consume the accessory's M2 (State=2, Salt, PublicKey=B) and produce
     * State=3, PublicKey=A, Proof=M1.  M1/M2 are the SRP proofs; a is fresh per handshake.
     */
    public byte[] pairSetupM3(byte[] m2) {
        requireState("pairSetupM3", State.SETUP_M1);
        Map<Integer, byte[]> tlv = requireTlv("pair-setup M2", m2);
        requireStateValue("pair-setup M2", tlv, STATE_M2);
        byte[] salt = tlv.get(TLV_SALT);
        byte[] bBytes = tlv.get(TLV_PUBLIC_KEY);
        if (salt == null || salt.length == 0 || salt.length > 64) {
            // 16 bytes is the norm (xcertplay SALT_BYTES = 16; every captured session too).  We
            // accept any 1..64 bytes and use them verbatim because an accessory that minimally
            // encodes its 128-bit salt can legitimately put fewer bytes on the wire, and its own
            // x = H(salt || H(I||":"||P)) then used exactly those bytes.
            throw new IllegalArgumentException("pair-setup M2 Salt must be 1..64 bytes, got "
                    + (salt == null ? "absent" : String.valueOf(salt.length)));
        }
        if (bBytes == null || bBytes.length != SRP_KEY_BYTES) {
            throw new IllegalArgumentException("pair-setup M2 PublicKey(B) must be "
                    + SRP_KEY_BYTES + " bytes, got " + (bBytes == null ? "absent" : bBytes.length));
        }

        // k = H(PAD(N) || PAD(g)); x = H(s || H(I || ":" || P)); u = H(PAD(A) || PAD(B)).
        byte[] nPad = pad(SRP_N);
        byte[] gPad = pad(SRP_G);
        byte[] kHash = sha512(nPad, gPad);
        BigInteger k = new BigInteger(1, kHash);
        byte[] xHash = srpComputeX(salt, SRP_USERNAME, PAIR_SETUP_PIN);
        BigInteger x = new BigInteger(1, xHash);
        BigInteger v = SRP_G.modPow(x, SRP_N);

        BigInteger b = new BigInteger(1, bBytes);
        if (b.signum() <= 0 || b.compareTo(SRP_N) >= 0) {
            throw new IllegalArgumentException("pair-setup M2 PublicKey(B) is not in [1, N)");
        }

        BigInteger a;
        BigInteger aPublic;
        BigInteger u;
        int attempts = 0;
        while (true) {
            if (++attempts > 16) {
                // Astronomically unlikely: a == 0 or u == 0 sixteen times in a row.
                throw new IllegalStateException("could not derive a usable SRP exponent");
            }
            byte[] secret = new byte[32];
            RANDOM.nextBytes(secret);
            a = new BigInteger(1, secret);
            if (a.signum() == 0) {
                continue;
            }
            aPublic = SRP_G.modPow(a, SRP_N);
            u = new BigInteger(1, sha512(pad(aPublic), pad(b)));
            if (u.signum() != 0) {
                break;
            }
        }

        BigInteger base = b.subtract(k.multiply(v)).mod(SRP_N);
        if (base.signum() == 0) {
            // B == k*g^x: the accessory is not following SRP (or is malicious). Never divide.
            throw new IllegalArgumentException("pair-setup M2 gives B - k*g^x == 0 mod N");
        }
        BigInteger exponent = a.add(u.multiply(x));
        BigInteger s = base.modPow(exponent, SRP_N);
        // K = H(minimal(S)), NOT H(PAD(S)).  The two differ only when S's top byte is zero
        // (~1/256 of handshakes) and every reference we could read uses the minimal form:
        // xcertplay Srp6a.kt (`sha512(toBytes(s))`), airplay2-receiver srp.py (`H(self.S)` with
        // minimal int->bytes) and srptools context.py (`K = H(S)`, minimal).  The accessory
        // verifies *our* M1 with its own convention, so we must match its formula; if an
        // accessory ever turns out to use PAD(S) the M4 reply is an Error TLV and the caller
        // simply restarts pair-setup (fresh salt, fresh a).
        byte[] k64 = sha512(minimal(s));

        // M1 = H( H(N) XOR H(g) || H(I) || s || PAD(A) || PAD(B) || K ).
        //
        // Field-by-field this mirrors the accessory that accepted every real-iPhone M1 in
        // wp3-*.pcap, xcertplay Srp6a.kt:verify():
        //     hashN    = SHA512(toBytes(N))            -> 384 bytes (N has no leading zero byte)
        //     hashG    = SHA512(toBytes(g))            -> SHA512(0x05), ONE byte of input!
        //     hashXor  = hashN XOR hashG               -> 64 bytes
        //     H(I)     = SHA512("Pair-Setup")          -> 64 bytes
        //     salt     = the raw bytes off the wire
        //     A, B     = pad(A), pad(B)                -> 384 bytes each
        //     K        = SHA512(toBytes(S))            -> 64 bytes
        // Note the asymmetry: the *multiplier* k above uses pad(g) (384 bytes), because that is
        // what xcertplay/airplay2-receiver/srptools all do, but M1's H(g) hashes the minimal
        // encoding of g.  Using pad(g) here instead fails 100% of handshakes.
        // UNCERTAIN (reference §2 note): the same three references disagree about minimal-vs-fixed
        // width for the other integer fields; pyatv's srptools and airplay2-receiver use minimal
        // encodings for A/B/H(I)/K too, which differs from this ~1/256 of the time per field.
        // We follow xcertplay because it is the accessory we must satisfy.
        byte[] xor = xor64(sha512(minimal(SRP_N)), sha512(minimal(SRP_G)));
        byte[] aPad = pad(aPublic);
        byte[] bPad = pad(b);
        byte[] m1 = sha512(xor, sha512(ascii(SRP_USERNAME)), salt, aPad, bPad, k64);
        byte[] m2Proof = sha512(aPad, m1, k64);

        mSrpSecret = a;
        mSrpM2 = m2Proof;
        mSrpSessionKey = k64;
        mState = State.SETUP_M3;
        return tlvJoin(tlv(TLV_STATE, new byte[] {STATE_M3}),
                       tlv(TLV_PUBLIC_KEY, aPad),
                       tlv(TLV_PROOF, m1));
    }

    /**
     * M5: consume the accessory's M4, verify its Proof is our own M2, then sign our identity
     * with our long-term Ed25519 key and seal {id, ltpk, signature} with the pair-setup key.
     */
    public byte[] pairSetupM5(byte[] m4) {
        requireState("pairSetupM5", State.SETUP_M3);
        Map<Integer, byte[]> tlv = requireTlv("pair-setup M4", m4);
        requireStateValue("pair-setup M4", tlv, STATE_M4);
        byte[] proof = tlv.get(TLV_PROOF);
        if (proof == null || proof.length != SRP_HASH_BYTES) {
            throw new IllegalArgumentException("pair-setup M4 Proof must be "
                    + SRP_HASH_BYTES + " bytes, got " + (proof == null ? "absent" : proof.length));
        }
        if (!MessageDigest.isEqual(proof, mSrpM2)) {
            throw new IllegalArgumentException(
                    "pair-setup M4 Proof does not match our M2: the accessory does not know the PIN, "
                            + "or the SRP exchange was tampered with");
        }

        mPairSetupEncryptKey = hkdfSha512(mSrpSessionKey, PAIR_SETUP_ENCRYPT_SALT,
                PAIR_SETUP_ENCRYPT_INFO, 32);
        byte[] iosDeviceX = hkdfSha512(mSrpSessionKey, PAIR_SETUP_CONTROLLER_SIGN_SALT,
                PAIR_SETUP_CONTROLLER_SIGN_INFO, 32);

        byte[] signature = ed25519Sign(ed25519Private(mIdentity.privateKeyPkcs8),
                concat(iosDeviceX, mIdentity.pairingId, mIdentity.longTermPublicKey));
        byte[] inner = tlvJoin(tlv(TLV_IDENTIFIER, mIdentity.pairingId),
                               tlv(TLV_PUBLIC_KEY, mIdentity.longTermPublicKey),
                               tlv(TLV_SIGNATURE, signature));
        byte[] sealed = chachaSeal(mPairSetupEncryptKey, nonceForLabel(NONCE_PS_MSG05), inner, null);

        mState = State.SETUP_M5;
        // The real iPhone puts EncryptedData first and State last in M5 (capture: `05 9a .. 06 01 05`);
        // we reproduce that byte order.  Accessory parsers are type-keyed, so it is not load-bearing.
        return tlvJoin(tlv(TLV_ENCRYPTED_DATA, sealed), tlv(TLV_STATE, new byte[] {STATE_M5}));
    }

    /**
     * M6: decrypt the accessory's {identifier, ltpk, signature}, verify the Ed25519 signature
     * against the SRP session key (this is the trust anchor for pair-verify — do not skip it)
     * and persist the accessory's long-term public key.
     */
    public void pairSetupM6(byte[] m6) {
        requireState("pairSetupM6", State.SETUP_M5);
        Map<Integer, byte[]> tlv = requireTlv("pair-setup M6", m6);
        requireStateValue("pair-setup M6", tlv, STATE_M6);
        byte[] sealed = tlv.get(TLV_ENCRYPTED_DATA);
        if (sealed == null) {
            throw new IllegalArgumentException("pair-setup M6 has no EncryptedData");
        }
        byte[] inner = chachaOpenOrFail(mPairSetupEncryptKey, nonceForLabel(NONCE_PS_MSG06), sealed, null,
                "pair-setup M6 EncryptedData");
        Map<Integer, byte[]> sub = parseTlv(inner);
        byte[] accId = sub.get(TLV_IDENTIFIER);
        byte[] accLtpk = sub.get(TLV_PUBLIC_KEY);
        byte[] accSig = sub.get(TLV_SIGNATURE);
        if (accId == null || accId.length == 0) {
            throw new IllegalArgumentException("pair-setup M6 inner TLV has no identifier");
        }
        if (accLtpk == null || accLtpk.length != 32) {
            throw new IllegalArgumentException("pair-setup M6 accessory LTPK must be 32 bytes, got "
                    + (accLtpk == null ? "absent" : accLtpk.length));
        }
        if (accSig == null || accSig.length != 64) {
            throw new IllegalArgumentException("pair-setup M6 signature must be 64 bytes, got "
                    + (accSig == null ? "absent" : accSig.length));
        }
        byte[] accX = hkdfSha512(mSrpSessionKey, PAIR_SETUP_ACCESSORY_SIGN_SALT,
                PAIR_SETUP_ACCESSORY_SIGN_INFO, 32);
        if (!ed25519Verify(ed25519Public(accLtpk), concat(accX, accId, accLtpk), accSig)) {
            throw new IllegalArgumentException(
                    "pair-setup M6 accessory signature does not verify: refusing to trust this LTPK");
        }

        mIdentity = mIdentity.withAccessory(accId, accLtpk);
        if (mStore != null) {
            mStore.save(mIdentity.encode());
        }
        mSrpSecret = null;
        wipe(mSrpSessionKey);
        mSrpSessionKey = null;
        wipe(mPairSetupEncryptKey);
        mPairSetupEncryptKey = null;
        mSrpM2 = null;
        mState = State.NEW;
    }

    // ------------------------------------------------------------------ pair-verify

    /**
     * pair-verify M1: State=1 plus our ephemeral X25519 public key.  Requires a verified
     * accessory LTPK from pair-setup M6.
     */
    public byte[] pairVerifyM1() {
        requireState("pairVerifyM1", State.NEW, State.PAIRED);
        if (mIdentity.accessoryLongTermPublicKey == null) {
            throw new IllegalArgumentException(
                    "pairVerifyM1 needs the accessory LTPK from a verified pair-setup M6");
        }
        mEphemeralX25519 = x25519KeyPair();
        mVerifyShared = null;
        mVerifyEncryptKey = null;
        // A new pair-verify invalidates every data-stream key derived from the previous one.
        wipe(mDataStreamShared);
        mDataStreamShared = null;
        // A new pair-verify means a new connection: the old keys and their counters are dead.
        mControl = null;
        mEvent = null;
        mState = State.VERIFY_M1;
        return tlvJoin(tlv(TLV_STATE, new byte[] {STATE_M1}),
                       tlv(TLV_PUBLIC_KEY, rawFromSpki(mEphemeralX25519.getPublic(),
                               SPKI_X25519_PREFIX, "X25519")));
    }

    /**
     * pair-verify M3: consume M2 (State=2, accessory ephemeral PublicKey, EncryptedData),
     * verify the accessory's Ed25519 signature over accEphPub||accId||clientEphPub, then sign
     * clientEphPub||clientId||accEphPub with our long-term key and seal it.
     */
    public byte[] pairVerifyM3(byte[] m2) {
        requireState("pairVerifyM3", State.VERIFY_M1);
        Map<Integer, byte[]> tlv = requireTlv("pair-verify M2", m2);
        requireStateValue("pair-verify M2", tlv, STATE_M2);
        byte[] accPub = tlv.get(TLV_PUBLIC_KEY);
        byte[] sealed = tlv.get(TLV_ENCRYPTED_DATA);
        if (accPub == null || accPub.length != 32) {
            throw new IllegalArgumentException("pair-verify M2 PublicKey must be 32 bytes, got "
                    + (accPub == null ? "absent" : accPub.length));
        }
        if (sealed == null) {
            throw new IllegalArgumentException("pair-verify M2 has no EncryptedData");
        }

        mVerifyShared = x25519(mEphemeralX25519.getPrivate(), accPub);
        mVerifyEncryptKey = hkdfSha512(mVerifyShared, PAIR_VERIFY_ENCRYPT_SALT,
                PAIR_VERIFY_ENCRYPT_INFO, 32);

        byte[] inner = chachaOpenOrFail(mVerifyEncryptKey, nonceForLabel(NONCE_PV_MSG02), sealed, null,
                "pair-verify M2 EncryptedData");
        Map<Integer, byte[]> sub = parseTlv(inner);
        byte[] accId = sub.get(TLV_IDENTIFIER);
        byte[] accSig = sub.get(TLV_SIGNATURE);
        if (accId == null || accId.length == 0) {
            throw new IllegalArgumentException("pair-verify M2 inner TLV has no identifier");
        }
        if (accSig == null || accSig.length != 64) {
            throw new IllegalArgumentException("pair-verify M2 signature must be 64 bytes, got "
                    + (accSig == null ? "absent" : accSig.length));
        }
        if (!MessageDigest.isEqual(accId, mIdentity.accessoryPairingId)) {
            // Deliberately strict: we trust one accessory pairing identity and will not silently
            // accept a different one presenting the same LTPK.  (Not flagged UNCERTAIN in the
            // reference; relax only if a real accessory is observed to rotate its id.)
            throw new IllegalArgumentException(
                    "pair-verify M2 accessory id " + ascii(accId) + " differs from the id learned at "
                            + "pair-setup " + ascii(mIdentity.accessoryPairingId));
        }
        byte[] clientEphPub = rawFromSpki(mEphemeralX25519.getPublic(), SPKI_X25519_PREFIX, "X25519");
        if (!ed25519Verify(ed25519Public(mIdentity.accessoryLongTermPublicKey),
                concat(accPub, accId, clientEphPub), accSig)) {
            throw new IllegalArgumentException(
                    "pair-verify M2 accessory signature does not verify against the stored LTPK");
        }

        byte[] ourSig = ed25519Sign(ed25519Private(mIdentity.privateKeyPkcs8),
                concat(clientEphPub, mIdentity.pairingId, accPub));
        byte[] out = tlvJoin(tlv(TLV_IDENTIFIER, mIdentity.pairingId), tlv(TLV_SIGNATURE, ourSig));
        byte[] outSealed = chachaSeal(mVerifyEncryptKey, nonceForLabel(NONCE_PV_MSG03), out, null);

        mState = State.VERIFY_M3;
        // Real iPhone order for pair-verify M3 is EncryptedData then State (`05 .. 06 01 03`).
        return tlvJoin(tlv(TLV_ENCRYPTED_DATA, outSealed), tlv(TLV_STATE, new byte[] {STATE_M3}));
    }

    /**
     * pair-verify M4: the accessory answers State=4 only (3 bytes, `06 01 04`).  From here the
     * whole control connection is sealed, and the event channel keys exist too.  Encryption
     * starts at M4, *not* at pair-setup M6 (reference §8 gotcha 4).
     */
    public void pairVerifyM4(byte[] m4) {
        requireState("pairVerifyM4", State.VERIFY_M3);
        Map<Integer, byte[]> tlv = requireTlv("pair-verify M4", m4);
        requireStateValue("pair-verify M4", tlv, STATE_M4);
        for (Integer type : tlv.keySet()) {
            if (type != TLV_STATE) {
                throw new IllegalArgumentException(String.format(
                        "pair-verify M4 must carry State only, but it carries TLV 0x%02X", type));
            }
        }

        byte[] controlWrite = hkdfSha512(mVerifyShared, CONTROL_SALT, CONTROL_WRITE_INFO, 32);
        byte[] controlRead = hkdfSha512(mVerifyShared, CONTROL_SALT, CONTROL_READ_INFO, 32);
        // INVERTED mapping: we *send* with Events-Read-Encryption-Key and *receive* with
        // Events-Write-Encryption-Key, because the event connection originates from us but the
        // labels are written from the accessory's point of view (reference §4, gotcha 3).
        byte[] eventWrite = hkdfSha512(mVerifyShared, EVENTS_SALT, EVENTS_READ_INFO, 32);
        byte[] eventRead = hkdfSha512(mVerifyShared, EVENTS_SALT, EVENTS_WRITE_INFO, 32);

        mControl = new Channel(controlWrite, controlRead);
        mEvent = new Channel(eventWrite, eventRead);
        // The data-stream keys (media streams and the type-130 iAP2 tunnel) are derived from the
        // same pair-verify shared secret but only *after* M4, so keep a memory-only copy before
        // the handshake copy is wiped below.
        mDataStreamShared = mVerifyShared.clone();
        wipe(controlWrite);
        wipe(controlRead);
        wipe(eventWrite);
        wipe(eventRead);
        wipe(mVerifyShared);
        wipe(mVerifyEncryptKey);
        mVerifyShared = null;
        mVerifyEncryptKey = null;
        mEphemeralX25519 = null;
        mState = State.PAIRED;
    }

    // ------------------------------------------------------------------ data-stream keys

    /**
     * AirPlay **DataStream** output key: the ChaCha20-Poly1305 key for everything we send to the
     * accessory on a media stream.  Salt is {@code "DataStream-Salt"} plus the **unsigned**
     * decimal rendering of the value: screen/audio streams pass their {@code streamConnectionID},
     * the type-130 iAP2 tunnel passes the {@code seed} it announced in its SETUP.
     *
     * The unsigned rendering is not cosmetic: the real iPhone sent {@code streamConnectionID} /
     * {@code seed} as a *signed* plist integer while the accessory derived the key from
     * {@code Long.toUnsignedString(...)} (the pairing-crypto notes §6/§7, confirmed on the
     * wire).  Getting it wrong yields a different key and the accessory cannot authenticate
     * the stream.
     *
     * Requires pair-verify M4, which retains the shared secret this is derived from.
     */
    public byte[] dataStreamOutputKey(long streamOrSeed) {
        if (mDataStreamShared == null) {
            throw new IllegalStateException("pair-verify has not completed");
        }
        return hkdfSha512(mDataStreamShared, "DataStream-Salt" + Long.toUnsignedString(streamOrSeed),
                "DataStream-Output-Encryption-Key", 32);
    }

    // ------------------------------------------------------------------ sealed channels

    /** Encrypts our→accessory control data; throws if the plaintext exceeds MAX_PLAINTEXT. */
    public byte[] sealControl(byte[] plaintext) {
        return control().seal(plaintext);
    }

    /** Decrypts accessory→us control data; throws IllegalArgumentException on any AEAD failure. */
    public byte[] unsealControl(byte[] sealed) {
        return control().unseal(sealed);
    }

    /** Encrypts our→accessory event data (uses the inverted Events-Read key). */
    public byte[] sealEvent(byte[] plaintext) {
        return event().seal(plaintext);
    }

    /** Decrypts accessory→us event data (uses the inverted Events-Write key). */
    public byte[] unsealEvent(byte[] sealed) {
        return event().unseal(sealed);
    }

    /**
     * Splits a control plaintext into MAX_PLAINTEXT-byte frames (the real iPhone's 0x4000 rule)
     * and seals each one separately.  An empty plaintext yields exactly one empty frame.
     */
    public List<byte[]> sealControlChunked(byte[] plaintext) {
        return chunked(control(), plaintext);
    }

    /** As {@link #sealControlChunked} for the event channel. */
    public List<byte[]> sealEventChunked(byte[] plaintext) {
        return chunked(event(), plaintext);
    }

    private static List<byte[]> chunked(Channel channel, byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is null");
        }
        List<byte[]> out = new ArrayList<byte[]>();
        if (plaintext.length == 0) {
            out.add(channel.seal(plaintext));
            return out;
        }
        for (int off = 0; off < plaintext.length; off += MAX_PLAINTEXT) {
            int end = Math.min(off + MAX_PLAINTEXT, plaintext.length);
            out.add(channel.seal(Arrays.copyOfRange(plaintext, off, end)));
        }
        return out;
    }

    /** Key we encrypt our control-channel requests with (Control-Write-Encryption-Key). */
    public byte[] controlWriteKey() {
        control();
        return mControl.writeKey();
    }

    /** Key we decrypt the accessory's control-channel replies with (Control-Read-…). */
    public byte[] controlReadKey() {
        control();
        return mControl.readKey();
    }

    /** Key we encrypt our event-channel messages with — Events-**Read**-Encryption-Key. */
    public byte[] eventWriteKey() {
        event();
        return mEvent.writeKey();
    }

    /** Key we decrypt the accessory's event-channel messages with — Events-**Write**-…. */
    public byte[] eventReadKey() {
        event();
        return mEvent.readKey();
    }

    /**
     * Per-direction frame counters, exposed to this package (diagnostics and the self-test).
     * Not part of the integration API: callers should never need to touch them.
     */
    long controlWriteCounter() {
        control();
        return mControl.writeCounter();
    }

    long controlReadCounter() {
        control();
        return mControl.readCounter();
    }

    long eventWriteCounter() {
        event();
        return mEvent.writeCounter();
    }

    long eventReadCounter() {
        event();
        return mEvent.readCounter();
    }

    private Channel control() {
        if (mControl == null) {
            throw new IllegalArgumentException("the control channel is not established yet; "
                    + "call pairVerifyM4 first (state " + mState + ")");
        }
        return mControl;
    }

    private Channel event() {
        if (mEvent == null) {
            throw new IllegalArgumentException("the event channel keys are not derived yet; "
                    + "call pairVerifyM4 first (state " + mState + ")");
        }
        return mEvent;
    }

    // ------------------------------------------------------------------ accessors / logging

    public byte[] accessoryPairingId() {
        return mIdentity.accessoryPairingId == null ? null : mIdentity.accessoryPairingId.clone();
    }

    public byte[] accessoryLongTermPublicKey() {
        return mIdentity.accessoryLongTermPublicKey == null
                ? null : mIdentity.accessoryLongTermPublicKey.clone();
    }

    public byte[] clientPairingId() {
        return mIdentity.pairingId.clone();
    }

    public byte[] clientLongTermPublicKey() {
        return mIdentity.longTermPublicKey.clone();
    }

    /** True once pair-setup M6 verified an accessory and its key is persisted. */
    public boolean isPaired() {
        return mIdentity.accessoryLongTermPublicKey != null;
    }

    /** True once pair-verify M4 installed the channel keys. */
    public boolean channelsReady() {
        return mControl != null && mEvent != null;
    }

    /**
     * One-line diagnostic.  Log-safe by construction: it reports state, byte counts, pairing
     * ids, per-direction frame counters and SHA-512 *fingerprints of public keys only*.  It
     * never prints a private key, a session key, an SRP secret or a derived channel key.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("AirPlayPairing[state=").append(mState);
        // Which implementation backs each primitive: the JCA provider or the pure fallback.
        sb.append(", ed25519=").append(ed25519Backend());
        sb.append(", x25519=").append(x25519Backend());
        sb.append(", ").append(mIdentity.describe());
        sb.append(", clientLtPubFp=").append(fingerprint(mIdentity.longTermPublicKey));
        if (mIdentity.accessoryLongTermPublicKey != null) {
            sb.append(", accessoryLtPubFp=").append(fingerprint(mIdentity.accessoryLongTermPublicKey));
        }
        if (mControl != null) {
            sb.append(", control[").append(mControl.describe()).append(']');
        }
        if (mEvent != null) {
            sb.append(", event[").append(mEvent.describe()).append(']');
        }
        return sb.append(']').toString();
    }

    /**
     * Convenience builder for `GET /info`.  Only headers the real-iPhone capture confirms are
     * included; the session layer owns CSeq sequencing and may add its own headers.
     */
    public static byte[] infoRequest(int cseq) {
        return ascii("GET /info RTSP/1.0\r\n"
                + "X-Apple-ProtocolVersion: 1\r\n"
                + "CSeq: " + cseq + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Content-Length: 0\r\n\r\n");
    }

    // ------------------------------------------------------------------ TLV8 codec

    /**
     * Encodes one TLV8 item, splitting values longer than 255 bytes into consecutive
     * fragments of the same type.  A length that is an exact multiple of 255 produces
     * 255-byte fragments only (e.g. 255 -> one fragment of 255); an empty value encodes as
     * `type,0x00`.
     */
    public static byte[] tlv(int type, byte[] value) {
        if (type < 0 || type > 0xFF) {
            throw new IllegalArgumentException("TLV type out of range: " + type);
        }
        if (value == null) {
            throw new IllegalArgumentException("TLV value is null (use an empty array for an empty value)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        if (value.length == 0) {
            out.write(type);
            out.write(0);
            return out.toByteArray();
        }
        while (off < value.length) {
            int chunk = Math.min(255, value.length - off);
            out.write(type);
            out.write(chunk);
            out.write(value, off, chunk);
            off += chunk;
        }
        return out.toByteArray();
    }

    /** Concatenates already-encoded TLVs, e.g. tlvJoin(tlv(State,..), tlv(PublicKey,..)). */
    public static byte[] tlvJoin(byte[]... encodedTlvs) {
        int total = 0;
        for (byte[] part : encodedTlvs) {
            if (part == null) {
                throw new IllegalArgumentException("tlvJoin got a null part");
            }
            total += part.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] part : encodedTlvs) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }

    /**
     * Decodes TLV8, concatenating consecutive fragments of the same type.  Zero-length 0xFF
     * items (the optional separator some encoders emit) are skipped and do not break a merge.
     *
     * @return a LinkedHashMap keyed by TLV type, in first-appearance order; values are copies.
     * @throws IllegalArgumentException on a truncated item or a length that overruns the buffer.
     */
    public static Map<Integer, byte[]> parseTlv(byte[] tlv8) {
        if (tlv8 == null) {
            throw new IllegalArgumentException("TLV body is null");
        }
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        int off = 0;
        while (off < tlv8.length) {
            if (off + 2 > tlv8.length) {
                throw new IllegalArgumentException("truncated TLV8 at offset " + off + " of " + tlv8.length);
            }
            int type = tlv8[off] & 0xFF;
            int len = tlv8[off + 1] & 0xFF;
            if (off + 2 + len > tlv8.length) {
                throw new IllegalArgumentException("TLV8 item 0x" + Integer.toHexString(type)
                        + " at offset " + off + " claims " + len + " bytes but only "
                        + (tlv8.length - off - 2) + " remain");
            }
            if (type == TLV_SEPARATOR && len == 0) {
                off += 2;
                continue;
            }
            byte[] value = Arrays.copyOfRange(tlv8, off + 2, off + 2 + len);
            byte[] previous = out.get(type);
            out.put(type, previous == null ? value : concat(previous, value));
            off += 2 + len;
        }
        return out;
    }

    private static Map<Integer, byte[]> requireTlv(String what, byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException(what + " body is empty");
        }
        Map<Integer, byte[]> tlv = parseTlv(body);
        byte[] error = tlv.get(TLV_ERROR);
        if (error != null && error.length > 0) {
            throw new IllegalArgumentException(what + " reports accessory error " + (error[0] & 0xFF)
                    + " (" + tlvErrorName(error[0] & 0xFF) + ")");
        }
        return tlv;
    }

    private static void requireStateValue(String what, Map<Integer, byte[]> tlv, int expected) {
        byte[] state = tlv.get(TLV_STATE);
        if (state == null || state.length != 1) {
            throw new IllegalArgumentException(what + " has no single-byte State TLV");
        }
        int got = state[0] & 0xFF;
        if (got != expected) {
            throw new IllegalArgumentException(what + " State is " + got + ", expected " + expected);
        }
    }

    /** Error TLV names, reference §1. */
    public static String tlvErrorName(int error) {
        switch (error) {
            case 1: return "Unknown";
            case 2: return "Authentication";
            case 3: return "BackOff";
            case 4: return "MaxPeers";
            case 5: return "MaxTries";
            case 6: return "Unavailable";
            case 7: return "Busy";
            default: return "unrecognised";
        }
    }

    // ------------------------------------------------------------------ HKDF-SHA-512

    public static byte[] hkdfSha512(byte[] ikm, String salt, String info, int length) {
        return hkdfSha512(ikm, ascii(salt), ascii(info), length);
    }

    /** RFC 5869 HKDF with HMAC-SHA-512 (extract then expand; salt may be empty = HashLen zeros). */
    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (ikm == null) {
            throw new IllegalArgumentException("HKDF ikm is null");
        }
        if (length <= 0 || length > 255 * SRP_HASH_BYTES) {
            throw new IllegalArgumentException("HKDF length out of range: " + length);
        }
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[SRP_HASH_BYTES] : salt;
        byte[] effectiveInfo = info == null ? new byte[0] : info;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA512"));
            byte[] prk = mac.doFinal(ikm);

            byte[] out = new byte[length];
            byte[] block = new byte[0];
            int pos = 0;
            int counter = 1;
            while (pos < length) {
                mac.init(new SecretKeySpec(prk, "HmacSHA512"));
                mac.update(block);
                mac.update(effectiveInfo);
                mac.update((byte) counter);
                block = mac.doFinal();
                int n = Math.min(block.length, length - pos);
                System.arraycopy(block, 0, out, pos, n);
                pos += n;
                counter++;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ SRP helpers

    /**
     * x = H( salt || H( username || ":" || password ) ), the only password-dependent SRP input.
     * Public so the self-test can cross-check the captured salt against an independent path.
     */
    public static byte[] srpComputeX(byte[] salt, String username, String password) {
        if (salt == null) {
            throw new IllegalArgumentException("SRP salt is null");
        }
        return sha512(salt, sha512(ascii(username + ":" + password)));
    }

    /** Big-endian, left-zero-padded to SRP_KEY_BYTES (never minimal-length). */
    static byte[] pad(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("cannot pad a negative integer");
        }
        byte[] raw = value.toByteArray();
        int start = (raw.length == SRP_KEY_BYTES + 1 && raw[0] == 0) ? 1 : 0;
        int len = raw.length - start;
        if (len > SRP_KEY_BYTES) {
            throw new IllegalArgumentException("SRP value does not fit in " + SRP_KEY_BYTES + " bytes");
        }
        byte[] out = new byte[SRP_KEY_BYTES];
        System.arraycopy(raw, start, out, SRP_KEY_BYTES - len, len);
        return out;
    }

    /**
     * Big-endian minimal encoding (no leading-zero bytes), mirroring BigInteger.toByteArray()
     * with the sign byte removed.  Used only where the references do (the input of K = H(S)).
     */
    static byte[] minimal(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("cannot minimally encode a negative integer");
        }
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    // ------------------------------------------------------------------ crypto primitives

    /** First alias the provider accepts, or null when it offers none (pure fallback then). */
    private static String firstAvailable(String service, String... names) {
        for (String name : names) {
            try {
                if ("KeyAgreement".equals(service)) {
                    KeyAgreement.getInstance(name);
                } else if ("Signature".equals(service)) {
                    Signature.getInstance(name);
                } else {
                    throw new IllegalArgumentException("unknown service " + service);
                }
                return name;
            } catch (GeneralSecurityException ignored) {
                // try the next alias
            }
        }
        return null;
    }

    private static KeyPair x25519KeyPair() {
        if (useJcaX25519()) {
            try {
                return KeyPairGenerator.getInstance(XDH_ALGORITHM).generateKeyPair();
            } catch (GeneralSecurityException | RuntimeException e) {
                // fall through to the pure implementation
            }
        }
        // Fallback: RFC 7748 in plain Java, for a device whose provider has no XDH/X25519.
        X25519Pure.XKeyPair pair = X25519Pure.generateKeyPair();
        return new KeyPair(pair.asPublicKey(), pair.asPrivateKey());
    }

    private static KeyPair ed25519KeyPair() {
        if (useJcaEd25519()) {
            try {
                return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } catch (GeneralSecurityException | RuntimeException e) {
                // fall through to the pure implementation
            }
        }
        // Fallback: Android 13 Conscrypt has no Ed25519, so generate with RFC 8032 in plain
        // Java.  The PKCS#8/SPKI encodings are byte-identical to the JCA ones, so the persisted
        // identity blob stays compatible across both backends.
        byte[] seed = Ed25519Pure.generateSeed();
        return new KeyPair(Ed25519Pure.publicKey(Ed25519Pure.publicKeyFromSeed(seed)),
                Ed25519Pure.privateKey(seed));
    }

    private static PrivateKey ed25519Private(byte[] pkcs8) {
        if (useJcaEd25519()) {
            try {
                return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (GeneralSecurityException | RuntimeException e) {
                // fall through to the pure implementation
            }
        }
        // Fallback: same PKCS#8 shape; the blob is validated here so a corrupt one still fails
        // at decode time rather than at the first signature.
        return Ed25519Pure.privateKeyFromPkcs8(pkcs8);
    }

    private static PublicKey ed25519Public(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        if (useJcaEd25519()) {
            try {
                return KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(concat(SPKI_ED25519_PREFIX, raw)));
            } catch (GeneralSecurityException | RuntimeException e) {
                // fall through to the pure implementation
            }
        }
        // Fallback; rejects a non-canonical / off-curve encoding rather than wrapping it.
        return Ed25519Pure.publicKey(raw);
    }

    private static byte[] ed25519Sign(PrivateKey key, byte[] message) {
        if (key instanceof Ed25519Pure.PurePrivateKey) {
            // Pure fallback: the key object carries the RFC 8032 seed from its PKCS#8 blob.
            return Ed25519Pure.sign(message, ((Ed25519Pure.PurePrivateKey) key).seed());
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    private static boolean ed25519Verify(PublicKey key, byte[] message, byte[] signature) {
        if (key instanceof Ed25519Pure.PurePublicKey) {
            // Pure fallback; a malformed signature is a verification failure, not an error.
            return Ed25519Pure.verify(message, signature, ((Ed25519Pure.PurePublicKey) key).raw());
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            // A malformed signature/point is a verification failure, not an internal error.
            return false;
        }
    }

    /** X25519 ECDH.  Rejects an all-zero shared secret (low-order / identity peer key). */
    static byte[] x25519(PrivateKey privateKey, byte[] peerRaw) {
        if (peerRaw == null || peerRaw.length != 32) {
            throw new IllegalArgumentException("X25519 peer key must be 32 bytes, got "
                    + (peerRaw == null ? "absent" : peerRaw.length));
        }
        byte[] shared;
        if (privateKey instanceof X25519Pure.PurePrivateKey) {
            // Pure fallback: the key object carries the RFC 7748 scalar.
            shared = X25519Pure.sharedSecret(((X25519Pure.PurePrivateKey) privateKey).scalar(), peerRaw);
        } else {
            try {
                PublicKey peer = KeyFactory.getInstance(XDH_ALGORITHM)
                        .generatePublic(new X509EncodedKeySpec(concat(SPKI_X25519_PREFIX, peerRaw)));
                KeyAgreement agreement = KeyAgreement.getInstance(XDH_ALGORITHM);
                agreement.init(privateKey);
                agreement.doPhase(peer, true);
                shared = agreement.generateSecret();
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("X25519 key agreement failed", e);
            }
        }
        if (shared.length != 32) {
            throw new IllegalStateException("X25519 produced a " + shared.length + "-byte secret");
        }
        boolean allZero = true;
        for (byte b : shared) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            throw new IllegalArgumentException("X25519 shared secret is all zeros "
                    + "(the accessory sent a low-order point)");
        }
        return shared;
    }

    /** Raw 32-byte key from a provider PublicKey, asserting the exact RFC 8410 SPKI shape. */
    static byte[] rawFromSpki(PublicKey key, byte[] prefix, String what) {
        if (key == null || prefix == null || what == null) {
            throw new IllegalArgumentException("rawFromSpki arguments are incomplete");
        }
        byte[] der = key.getEncoded();
        if (der == null || der.length != prefix.length + 32 || !startsWith(der, prefix)) {
            throw new IllegalStateException(what + " provider returned an unexpected SubjectPublicKeyInfo"
                    + " (" + (der == null ? "null" : der.length + " bytes") + "); cannot extract the raw key");
        }
        return Arrays.copyOfRange(der, prefix.length, der.length);
    }

    /** ChaCha20-Poly1305 seal; returns ciphertext || 16-byte tag. */
    static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 key must be 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 nonce must be " + NONCE_BYTES + " bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 sealing failed", e);
        }
    }

    /**
     * ChaCha20-Poly1305 open.  {@code sealed} must be ciphertext followed by the 16-byte tag,
     * exactly as {@link #chachaSeal} produced it.
     *
     * @throws AEADBadTagException if the tag does not authenticate the ciphertext + AAD.
     */
    static byte[] chachaOpen(byte[] key, byte[] nonce, byte[] sealed, byte[] aad)
            throws AEADBadTagException {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 key must be 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 nonce must be " + NONCE_BYTES + " bytes");
        }
        if (sealed == null || sealed.length < TAG_BYTES) {
            throw new IllegalArgumentException("sealed data is shorter than the 16-byte tag");
        }
        try {
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(sealed);
        } catch (AEADBadTagException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 opening failed", e);
        }
    }

    /**
     * Opens a pairing-message ciphertext.  Unlike the channel codec, an authentication failure
     * here means the peer proved the wrong SRP/verify key, so it surfaces as a protocol error.
     */
    static byte[] chachaOpenOrFail(byte[] key, byte[] nonce, byte[] sealed, byte[] aad, String what) {
        try {
            return chachaOpen(key, nonce, sealed, aad);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException(what + " failed ChaCha20-Poly1305 authentication"
                    + " (wrong session key, wrong nonce label, or a tampered message)", e);
        }
    }

    static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private static byte[] xor64(byte[] a, byte[] b) {
        if (a.length != SRP_HASH_BYTES || b.length != SRP_HASH_BYTES) {
            throw new IllegalArgumentException("xor operands must be " + SRP_HASH_BYTES + " bytes");
        }
        byte[] out = new byte[SRP_HASH_BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /** 4 zero bytes || an 8-byte ASCII label, the pairing-message nonce shape. */
    static byte[] nonceForLabel(String label) {
        if (label == null || label.length() != 8) {
            throw new IllegalArgumentException("pairing nonce label must be exactly 8 ASCII characters");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(ascii(label), 0, nonce, 4, 8);
        return nonce;
    }

    /** 4 zero bytes || u64 little-endian counter. */
    static byte[] nonceForCounter(long counter) {
        byte[] nonce = new byte[NONCE_BYTES];
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (counter >>> (8 * i));
        }
        return nonce;
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] u16le(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("value does not fit in u16: " + value);
        }
        return new byte[] {(byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF)};
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            if (part == null) {
                throw new IllegalArgumentException("concat got a null part");
            }
            total += part.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >>> 4) & 0x0F];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() % 2) != 0) {
            throw new IllegalArgumentException("hex string must have an even length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("not a hex string at index " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** First 8 bytes of SHA-512 over a *public* key, for log correlation.  Never called on secrets. */
    private static String fingerprint(byte[] publicKey) {
        return toHex(Arrays.copyOf(sha512(publicKey), 8));
    }

    private static void wipe(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireState(String what, State... allowed) {
        for (State state : allowed) {
            if (mState == state) {
                return;
            }
        }
        throw new IllegalArgumentException(what + " called in state " + mState
                + "; expected " + Arrays.toString(allowed));
    }

    private void resetHandshake() {
        mSrpSecret = null;
        mSrpM2 = null;
        wipe(mSrpSessionKey);
        mSrpSessionKey = null;
        wipe(mPairSetupEncryptKey);
        mPairSetupEncryptKey = null;
        mEphemeralX25519 = null;
        wipe(mVerifyShared);
        mVerifyShared = null;
        wipe(mVerifyEncryptKey);
        mVerifyEncryptKey = null;
        wipe(mDataStreamShared);
        mDataStreamShared = null;
        mControl = null;
        mEvent = null;
    }

    // ------------------------------------------------------------------ channel framing

    /**
     * One direction-pair of the AirPlay/HAP sealed channel:
     * {@code [u16 LE len(ciphertext)][ciphertext][16-byte tag]}, AAD = the two length bytes,
     * nonce = 4 zero bytes || u64 LE counter that starts at 0 per direction.  The length field
     * counts ciphertext only, never the tag (reference §4).  Counters live with the keys, i.e.
     * per TCP connection — a reconnect performs a fresh pair-verify and starts again at 0.
     */
    private static final class Channel {
        private final byte[] mWriteKey;
        private final byte[] mReadKey;
        private long mWriteCounter;
        private long mReadCounter;

        Channel(byte[] writeKey, byte[] readKey) {
            mWriteKey = writeKey.clone();
            mReadKey = readKey.clone();
        }

        synchronized byte[] seal(byte[] plaintext) {
            if (plaintext == null) {
                throw new IllegalArgumentException("plaintext is null");
            }
            if (plaintext.length > MAX_PLAINTEXT) {
                throw new IllegalArgumentException("plaintext of " + plaintext.length
                        + " bytes exceeds the " + MAX_PLAINTEXT + "-byte frame limit; chunk it"
                        + " (sealControlChunked / sealEventChunked)");
            }
            byte[] header = u16le(plaintext.length);
            byte[] sealed = chachaSeal(mWriteKey, nonceForCounter(mWriteCounter), plaintext, header);
            mWriteCounter++;
            return concat(header, sealed);
        }

        synchronized byte[] unseal(byte[] frame) {
            if (frame == null || frame.length < 2 + TAG_BYTES) {
                throw new IllegalArgumentException("sealed frame is shorter than 2 + 16 bytes: "
                        + (frame == null ? "null" : String.valueOf(frame.length)));
            }
            byte[] header = new byte[] {frame[0], frame[1]};
            int declared = (frame[0] & 0xFF) | ((frame[1] & 0xFF) << 8);
            int expected = 2 + declared + TAG_BYTES;
            if (frame.length != expected) {
                throw new IllegalArgumentException("sealed frame length mismatch: the u16 length field"
                        + " says " + declared + " ciphertext bytes, so the frame must be " + expected
                        + " bytes, but it is " + frame.length);
            }
            byte[] body = Arrays.copyOfRange(frame, 2, frame.length);
            byte[] plain;
            try {
                plain = chachaOpen(mReadKey, nonceForCounter(mReadCounter), body, header);
            } catch (AEADBadTagException e) {
                // Fatal for the connection: xcertplay closes the whole session on a control-decrypt
                // failure, so we leave the counter where it is and let the caller tear down.
                throw new IllegalArgumentException("sealed frame #" + mReadCounter + " failed"
                        + " ChaCha20-Poly1305 authentication (wrong key, counter desync, or tampering)", e);
            }
            mReadCounter++;
            return plain;
        }

        long writeCounter() {
            return mWriteCounter;
        }

        long readCounter() {
            return mReadCounter;
        }

        byte[] writeKey() {
            return mWriteKey.clone();
        }

        byte[] readKey() {
            return mReadKey.clone();
        }

        String describe() {
            return "wLen=" + mWriteKey.length + ",rLen=" + mReadKey.length
                    + ",wCount=" + mWriteCounter + ",rCount=" + mReadCounter;
        }
    }
}
