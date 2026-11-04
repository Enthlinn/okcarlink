# Lockdown identity (wired mode)

Wired CarPlay needs a device identity that exists only as files on the unit: a TLS certificate and
its private key. This document explains what they are for, why they must match a constant compiled
into the native binary, why this repository does not contain one, and how a builder produces their
own.

For the general architecture see `docs/en/architecture.md`.

## 1. What the lockdown handshake is

When the wired switch is on, `module/system/bin/okcar-carkit` brings up the FunctionFS gadget and
starts `native/ffs-usbmux/okcar-ffs-usbmux`. The head unit sees an iPhone-compatible USB device,
speaks usbmux to it (16-byte big-endian frames, a version handshake, then TCP-over-usbmux), and dials
the lockdown service on port 62078 (`LOCKDOWN_PORT`, `native/ffs-usbmux/okcar-ffs-usbmux.c:94`).

The lockdown conversation is short and its order matters:

1. **Plaintext first.** The host opens with `GetValue`/`SetValue` plists. Framing is a 4-byte
   big-endian length followed by UTF-8 XML (`native/ffs-usbmux/lockdown_proto.h`, bounded by
   `LOCKDOWN_MAX_PLIST`). If these are ignored the host tears the session down and retries
   forever, so they are answered before anything else.
2. **`StartSession`** is answered with `EnableSessionSSL=true`. From that point the conversation
   is TLS, served by a BoringSSL SSL object per connection, driven through a BIO pair
   (`tls_begin()`, `native/ffs-usbmux/okcar-ffs-usbmux.c:614`).
3. **`GetValue DevicePublicKey`** is answered as a plist `<data>` payload — base64 of a PKCS#1
   RSA public-key PEM, extracted at runtime from the certificate this binary installs
   (`LOCKDOWN_CERT` / `device_pubkey_from_cert()`, `native/ffs-usbmux/okcar-ffs-usbmux.c`). A
   compiled-in `DEVICE_PUBKEY_PKCS1_PEM_B64` still overrides it when set. The host builds its pair
   record from this value and rejects anything that is not `<data>`.
4. **`Pair` / `ValidatePair`** are always accepted. A fake accessory verifies and stores nothing:
   the pair record lives in the host's own app data. This is deliberate, not a shortcut — the
   device has nowhere to store it that would survive the host's expectations.
5. **`StartService com.apple.carkit.service`** is answered with a port and **no**
   `EnableServiceSSL`, so the host opens a raw byte pipe (6-byte marker + SYN) that the native
   binary bridges to the abstract socket `okcar-carkit` (`CARKIT_SOCKET_NAME`,
   `native/ffs-usbmux/okcar-ffs-usbmux.c:107`). The Java side (`CarkitServiceHost`,
   `UsbIap2Transport`, `com.okcar.link.iap2`) owns the iAP2 device role from there.

Steps 3–4 are why the identity files exist: without them the TLS server has no certificate, there is
nothing to advertise as `DevicePublicKey`, and the session dies exactly at `EnableSessionSSL`.

## 2. Where the advertised public key comes from

The device has exactly one public identity in the whole protocol: the public half of the certificate
installed at `/data/local/tmp/lockdown-cert.pem` (`LOCKDOWN_CERT`). `GetValue DevicePublicKey`
returns that key, and the TLS server presents that same certificate, so what the host records and
what the device proves cannot disagree — the mismatch this document used to describe is designed
out rather than documented away.

Three properties still matter:

- the value must be answered as plist `<data>` — the PKCS#1 public-key PEM, base64 — because the
  pairing client rejects any other shape;
- the certificate must be installed before a host asks: with none there, the reply is an empty
  `<data>` and the native logs `lockdown: WARNING: no DevicePublicKey available - install the
  lockdown pair first`;
- the advertised form is specifically **PKCS#1** (`BEGIN RSA PUBLIC KEY`), not the more common
  SubjectPublicKeyInfo form (`BEGIN PUBLIC KEY`) that `openssl rsa -pubout` prints; see §5 step 4.

`DEVICE_PUBKEY_PKCS1_PEM_B64` is still honoured when a builder compiles one in — it wins over the
certificate. That is a compatibility path for deployments that pinned a key, and it puts the
mismatch risk back: prefer leaving it empty.

## 3. Why this repository ships no key material

- The module runs as root on the head unit. A private key committed to the repository would be
  public in every fork and identical in every install — anyone could build a module presenting the
  same device identity.
- `tools/package-module.sh` therefore takes the pair from the builder, never from the tree: it
  copies `lockdown/lockdown-cert.pem` and `lockdown/lockdown-key.pem` (gitignored, builder
  supplied) into the staged `payload/` directory, and prints a note and continues when they are
  absent — wired mode simply cannot complete `EnableSessionSSL`.
- The private-key leak gate that scans every staged file exempts exactly these two files: every
  install must present a real pair, so their presence is intentional while their content is not
  public.
- The module **does** ship a pair in the payload, because `/data/local/tmp` is wiped by a factory
  reset and a head unit with a wiped `/data/local/tmp` cannot run wired mode at all. Shipping it
  in `payload/` makes reinstall (or the helper's own install step) a one-command recovery.

## 4. Where the pair is read from

| What | Where |
| --- | --- |
| On device (certificate) | `/data/local/tmp/lockdown-cert.pem`, mode `0644` — `LOCKDOWN_CERT`, `native/ffs-usbmux/okcar-ffs-usbmux.c:100` |
| On device (private key) | `/data/local/tmp/lockdown-key.pem`, mode `0600` — `LOCKDOWN_KEY`, `native/ffs-usbmux/okcar-ffs-usbmux.c:101` |
| Source in the module zip | `payload/lockdown-cert.pem`, `payload/lockdown-key.pem` |
| Builder input | `lockdown/lockdown-cert.pem`, `lockdown/lockdown-key.pem` (repository root, gitignored) |

`install_lockdown_identity()` in `module/system/bin/okcar-carkit` does the copy
(`module/system/bin/okcar-carkit:771`, called from the `start` verb at `:1281`). It preserves the
two modes above, skips the copy when the destination is already identical (`cmp`), and says
plainly at start time that wired will fail at `EnableSessionSSL` when the pair is missing. It
does not cross-check the pair against the advertised constant — a mismatch is only visible in the
handshake.

### 4.1 Caveat: the SSL context is built once per process

`tls_begin()` builds one `SSL_CTX` the first time a TLS session starts and reuses it for the
lifetime of the `okcar-ffs-usbmux` process (the static context is created at
`native/ffs-usbmux/okcar-ffs-usbmux.c:155`). The certificate and key files are read at that
moment and never re-read:

- A pair installed while a wired session is live only takes effect **after the wired switch is
  cycled** (`okcar-carkit stop` then `okcar-carkit start`, or the WebUI switch off/on).
- `SSL_CTX_check_private_key` runs at context build, so a cert/key mismatch is reported once, at
  the first handshake, as `tls: cert/key do not match`.

### 4.2 Caveat: a factory reset erases the pair

`/data/local/tmp` is wiped by a factory reset. The failure afterwards has no hint that a file is
missing: usbmux still enumerates, the TLS server still starts, and then every handshake dies at
`EnableSessionSSL` (this is a recorded field incident, not a hypothetical). Recovery is to
reinstall the module or copy the pair from its `payload/` directory, then cycle the wired switch.
This is the whole reason the module ships the pair.

## 5. Generate your own pair

All commands run from the repository root and write only into the gitignored `lockdown/` directory
(`tools/package-module.sh` picks it up from there). Everything below is plain `openssl`; nothing
from this repository is required to make the identity.

**1. Generate a self-signed certificate and its key.**

````sh
mkdir -p lockdown
openssl req -x509 -newkey rsa:2048 \
  -keyout lockdown/lockdown-key.pem \
  -out lockdown/lockdown-cert.pem \
  -days 3650 -nodes -subj "/CN=OkCarLink"
```

RSA-2048 and PKCS#1 match what the advertised constant is documented to hold
(`native/ffs-usbmux/okcar-ffs-usbmux.c:96`). The subject is free — nothing in the protocol checks
it, the head unit reads the key. A single self-signed certificate is enough: the loader
(`SSL_CTX_use_certificate_chain_file`) accepts a chain file containing one certificate, and the
host never validates the chain of a device that is expected to be an iPhone accessory.

**2. Verify the two files are one keypair.**

````sh
openssl x509 -in lockdown/lockdown-cert.pem -noout -modulus | openssl md5
openssl rsa  -in lockdown/lockdown-key.pem  -noout -modulus | openssl md5
```

Both digests must be equal. If they are not, you are looking at a mismatched pair — regenerate both
in one command (step 1) rather than mixing halves from different runs.

**3. Confirm the certificate really carries that key.**

````sh
openssl x509 -in lockdown/lockdown-cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform DER | sha256sum
openssl rsa -in lockdown/lockdown-key.pem -pubout \
  | openssl pkey -pubin -outform DER | sha256sum
```

Equal digests mean the certificate`s public key and the private key`s public key are the same key.

**4. Extract the public key in the advertised (PKCS#1) form.**

````sh
openssl rsa -in lockdown/lockdown-key.pem -RSAPublicKey_out -out lockdown/lockdown-pub.pem
```

`-RSAPublicKey_out` prints `BEGIN RSA PUBLIC KEY`, the PKCS#1 form the runtime extraction produces
and the form the head unit's pair record needs. Do **not** use `-pubout` here: that prints the
SubjectPublicKeyInfo form (`BEGIN PUBLIC KEY`), a different encoding of the same key, and it is not
what the device advertises.

**5. (Optional) base64-encode the key.**

````sh
base64 -w0 lockdown/lockdown-pub.pem
```

For a 2048-bit key this is one line of about 568 characters. It is the base64 of the whole PEM
file — header, wrapped key body, and footer included — which is the shape
`device_pubkey_from_cert()` produces at runtime, and therefore the shape a pinned constant must hold.

By default you stop here: nothing is edited and the binary derives the value from the certificate it
installs. This step and the next exist only for a deployment that wants the key pinned in the build.

**6. (Optional) pin it instead of deriving it.**

Edit `DEVICE_PUBKEY_PKCS1_PEM_B64` at `native/ffs-usbmux/okcar-ffs-usbmux.c:100-101`: keep it a
single string initializer (or adjacent literals if you prefer to wrap it), and change nothing else.
A non-empty constant wins over the certificate, so it must be the public half of the pair you
install — that is how the mismatch failure below comes back.

**7. Rebuild the module.**

````sh
tools/package-module.sh
```

It runs `native/ffs-usbmux/build.sh` (the runtime derivation, and any constant you pinned, live in
the resulting `build/okcar-ffs-usbmux`), the dex build, stages `module/`, copies the `lockdown/` pair into `stage/payload/`, stamps the
WebUI variant, runs the leak gate, and writes the release zip. The native build needs the AOSP tree
(see `docs/en/architecture.md`) because it links static BoringSSL and static bionic from the tree.

**8. Check the shipped artifact.**

After packaging, confirm the pair inside the staged payload is the one you built: repeat the two
digest comparisons from steps 2–3 against `stage/payload/lockdown-cert.pem` and
`lockdown/lockdown-key.pem`. If you pinned a constant in step 6, compare it with that same key — a
pinned constant is the only way the advertised key and the certificate can still disagree, and the
failure would only show up in a car.

## 6. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Every wired connection dies at `EnableSessionSSL` with a log line like `tls: handshake r=-1 err=1 errq=… NO_CERTIFICATE_SET` | No usable certificate/key at `/data/local/tmp`: the pair was never installed, is unreadable, or is empty | Install the pair (the `okcar-carkit` `start` verb does it) or copy it from `payload/`; keep `0644`/`0600`; cycle the wired switch |
| Same failure immediately after a factory reset | `/data/local/tmp` was wiped; nothing in the log mentions a missing file | Reinstall the module or copy the pair from its `payload/` directory, then cycle the wired switch |
| `tls: cert/key do not match` at the first handshake | The installed certificate and private key are not a pair | Reinstall one matching pair, then restart `okcar-ffs-usbmux` (stop/start the wired switch) |
| A newly installed pair does not take effect; handshakes keep failing with the old error until the switch is toggled | `okcar-ffs-usbmux` built its `SSL_CTX` once per process and never re-reads the files | Cycle the wired switch (or reboot); see §4.1 |
| The head unit never finishes pairing; it keeps re-asking for pairing, or TLS succeeds and the pair record is still rejected | a `DEVICE_PUBKEY_PKCS1_PEM_B64` pinned at build time, or an older flashed binary, no longer matches the installed certificate | leave the constant empty so the key is derived from the certificate, rebuild and reinstall; or regenerate both in one step (§5 steps 4–7) |
| The session is torn down before `EnableSessionSSL` and the host retries in a loop | Not key material: the host opens with plaintext `GetValue`/`SetValue` and drops a session that ignores them | Confirm the wired binary is the one this repository builds and read the helper/native logs under `/data/local/tmp` (`okcar-carkit.log`, `ffs-usbmux.log`); if that path was edited, keep replies in the framing the request came in |

## 7. Do not confuse this with the AirPlay identity

The pair above is the **wired USB** identity and lives in two files on the unit. Wireless mode
carries its own, unrelated identity: AirPlay SRP pairing with Ed25519 long-term keys, stored in the
device's own app preferences (the AirPlay identity store used by
`com.okcar.link.airplay.AirPlayPairing`). Different keys, different protocol, different storage —
regenerating the lockdown identity has no effect on wireless pairing, and vice versa.

## 8. Notes and honest gaps

- Whether the head unit inspects anything in the certificate besides the public key (subject,
  validity dates, extensions) is not asserted here beyond the observed behaviour: a single
  self-signed RSA-2048 leaf is what the field setups ran with.
- The error text in the table quotes what the handshake logging prints (`ERR_error_string_n`
  output); the numeric part of `errq` varies with the installed BoringSSL.
