/*
 * OkcarOS — crypto capability probe.
 *
 * Why: on lmi (Android 13, API 33) `KeyPairGenerator.getInstance("Ed25519")` throws
 * NoSuchAlgorithmException even though the platform tree ships BouncyCastle
 * (com.android.org.bouncycastle, security provider #3).  Before writing our own
 * Ed25519 we need to know whether the algorithm is simply *not registered* or merely
 * *not reachable through the default provider order* — the latter would be a one-line
 * fix (pass the provider explicitly).
 *
 * The probe walks every provider for Ed25519 / EdDSA / X25519 / XDH / ChaCha20-Poly1305
 * and also exercises a real key exchange, logging one line per answer.  Output goes to
 * logcat (tag OkCarLink/Crypto) and to LinkState, so it can be read back with
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action cryptoProbe
 */

package com.okcar.link.airplay;

import android.util.Log;

import com.okcar.link.LinkState;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;

public final class CryptoProbe {

    public static final String TAG = "OkCarLink/Crypto";

    private CryptoProbe() {
    }

    /** Runs the whole matrix and returns a human readable report. */
    public static String run() {
        final StringBuilder sb = new StringBuilder();
        final TreeMap<String, String> providers = new TreeMap<String, String>();
        for (Provider provider : Security.getProviders()) {
            providers.put(provider.getName(), String.valueOf(provider.getVersion()));
        }
        line(sb, "providers: " + providers);
        line(sb, "default order: " + java.util.Arrays.toString(
                java.util.Arrays.stream(Security.getProviders())
                        .map(Provider::getName).toArray()));

        for (String provider : new String[]{null, "AndroidOpenSSL", "Conscrypt", "BC"}) {
            probe(sb, provider, "KeyPairGenerator", "Ed25519");
            probe(sb, provider, "KeyPairGenerator", "EdDSA");
            probe(sb, provider, "KeyPairGenerator", "X25519");
            probe(sb, provider, "KeyPairGenerator", "XDH");
            probe(sb, provider, "Signature", "Ed25519");
            probe(sb, provider, "Signature", "EdDSA");
            probe(sb, provider, "KeyAgreement", "X25519");
            probe(sb, provider, "KeyAgreement", "XDH");
            probe(sb, provider, "Cipher", "ChaCha20-Poly1305");
        }

        /* A real exchange, so we know the working path end to end. */
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
            final KeyPair a = generator.generateKeyPair();
            final KeyPair b = generator.generateKeyPair();
            final KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(a.getPrivate());
            ka.doPhase(b.getPublic(), true);
            final byte[] secret = ka.generateSecret();
            line(sb, "X25519 exchange: ok, shared=" + secret.length + "B, spki="
                    + a.getPublic().getEncoded().length + "B");
        } catch (Throwable t) {
            line(sb, "X25519 exchange FAILED: " + t);
        }

        /* If the platform has any Ed25519 signature at all, say which provider serves it. */
        try {
            for (Provider provider : Security.getProviders()) {
                if (provider.getService("Signature", "Ed25519") != null) {
                    line(sb, "Ed25519 signature served by: " + provider.getName());
                }
            }
        } catch (Throwable t) {
            line(sb, "scanning for an Ed25519 signature provider failed: " + t);
        }
        return sb.toString();
    }

    private static void probe(StringBuilder sb, String provider, String type, String algorithm) {
        try {
            final Object instance;
            switch (type) {
                case "KeyPairGenerator":
                    instance = provider == null
                            ? KeyPairGenerator.getInstance(algorithm)
                            : KeyPairGenerator.getInstance(algorithm, provider);
                    break;
                case "Signature":
                    instance = provider == null
                            ? Signature.getInstance(algorithm)
                            : Signature.getInstance(algorithm, provider);
                    break;
                case "KeyAgreement":
                    instance = provider == null
                            ? KeyAgreement.getInstance(algorithm)
                            : KeyAgreement.getInstance(algorithm, provider);
                    break;
                default:
                    instance = provider == null
                            ? Cipher.getInstance(algorithm)
                            : Cipher.getInstance(algorithm, provider);
                    break;
            }
            final String used = instance instanceof java.security.KeyPairGenerator
                    ? ((KeyPairGenerator) instance).getProvider().getName()
                    : instance.toString();
            line(sb, (provider == null ? "default" : provider) + " " + type + " " + algorithm
                    + " -> OK (" + used + ")");
        } catch (Throwable t) {
            line(sb, (provider == null ? "default" : provider) + " " + type + " " + algorithm
                    + " -> " + t.getClass().getSimpleName());
        }
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text).append('\n');
        Log.i(TAG, text);
        LinkState.get().log("crypto", text);
    }
}
