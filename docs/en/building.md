# Building from source

OkCarLink 1.0.0 compiles with three shell scripts into one flashable zip, `build/okcarlink-1.0.0.zip`. The module id
is `okcarlink` and `module/module.prop` carries `version=1.0.0` / `versionCode=100`. There is no Gradle project and no
CMake project: the payload is `javac` plus `d8` (`tools/build-daemon.sh`) and the two native helpers are hand-written
AOSP-tree recipes (`native/uinputd/build.sh`, `native/ffs-usbmux/build.sh`); `tools/package-module.sh` runs all three
and packs the module directory into the zip. Everything here is derived from the sources in §6; §5 states which steps were actually executed and which are **untested**.

## 0. What the build produces

| Artifact | Produced by | Lands in the zip at |
| --- | --- | --- |
| `build/okcar-uinputd` | `native/uinputd/build.sh` | `system/bin/okcar-uinputd` |
| `build/okcar-ffs-usbmux` | `native/ffs-usbmux/build.sh` | `system/bin/okcar-ffs-usbmux` |
| `build/okcar-link.dex` | `tools/build-daemon.sh` | `payload/okcar-link.dex` |
| `build/okcarlink-1.0.0.zip` | `tools/package-module.sh` | — (the file you flash) |

The zip is the only artifact you install; the other three are intermediates the packager copies into place. A missing
intermediate is not fatal — the packager prints a `note: ... missing` line and still produces the zip, with that
capability disabled at runtime (§3).

Runtime names, unchanged from the internal kit: `okcar-linkd` (the supervisor that execs
`com.okcar.link.daemon.LinkDaemon` under `app_process` as root), `okcar-port-ctl`, `okcar-port-check`,
`okcar-port-panel`, `okcar-carkit`, `okcar-port-role`, `okcar-carwith-svc`, `okcar-uinputd`, `okcar-ffs-usbmux`.

## 1. Prerequisites

| Requirement | Why | How it is found |
| --- | --- | --- |
| Linux x86_64 host | the recipes consume `prebuilts/clang/host/linux-x86/...` and soong intermediates built there | — |
| `bash` + coreutils (`find`, `cp`, `chmod`, `mkdir`, `printf`, `rm`) | every build step is a bash script | on `PATH` |
| `zip`, `unzip` | the packager builds the release and prints an `unzip -l` listing | on `PATH` |
| A JDK — any modern one; the scripts locate it | `javac` compiles `daemon/src`, the tree's `d8.jar` dexes it. `tools/build-daemon.sh` names JDK 17 in its error text, but the only real requirement is a `javac` that accepts `--release 11` | `$JH` -> `$JDK` -> `$JAVA_HOME` -> `javac` on `PATH` (parent of its parent) -> `$HOME/*/tools/jdk-*` -> `/usr/lib/jvm/*` |
| An AOSP source tree | the platform framework jars including hidden APIs, the AOSP clang, the static bionic pieces and static BoringSSL all come from one tree | `$TREE`, else the first of `$HOME/aosp/*` / `$HOME/android/*` containing `out/target/common/obj/JAVA_LIBRARIES` |
| Rooted Android 11+ device with a KernelSU or Magisk manager | only for installing and verifying a real build, never for building | — |

**An AOSP source tree is not optional for a complete build.** Without one both native builds print their `SKIP` line
and exit 0, and the dex build prints its own `SKIP` line and exits 0 — the dex and both natives are then absent from
the package. The one-line build is therefore:

    TREE=/path/to/aosp tools/package-module.sh

The recipes expect a tree of the Android 13 / API 33 family — `prebuilts/sdk/33/public/android.jar`, clang from `prebuilts/clang/host/linux-x86/clang-r450784d/bin/clang`, soong intermediates built for platform 30/31 (`..._apex30`, `..._apex31`) — built far enough to produce `out/target/common/obj/JAVA_LIBRARIES`, the marker the auto-probe looks for. No NDK is downloaded and no
sysroot ships with this repository.

## 2. Build steps

### 2.1 Step 1 — clone

    git clone <repository-url> okcarlink
    cd okcarlink

Paths below are relative to that root; the scripts `cd` to their own directory and derive the root themselves, so they
work from any working directory.

### 2.2 Step 2 — generate the lockdown identity (optional; required for wired mode)

Wired CarPlay must present a certificate to the head unit's lockdown handshake, so a wired-capable build needs one key
pair you generate yourself: the repository ships no key material, because a root module whose private key is public in
every fork would be identical in every install. The rationale and failure modes are in `docs/en/crypto.md`; the commands repeat here so this page is self-contained, and they write only into the gitignored `lockdown/` (run from the repository root).

    mkdir -p lockdown
    openssl req -x509 -newkey rsa:2048 -keyout lockdown/lockdown-key.pem \
      -out lockdown/lockdown-cert.pem -days 3650 -nodes -subj "/CN=OkCarLink"

Confirm the two halves are one pair — the first digests must match each other, and the second pair must match each
other:

    openssl x509 -in lockdown/lockdown-cert.pem -noout -modulus | openssl md5
    openssl rsa  -in lockdown/lockdown-key.pem  -noout -modulus | openssl md5

    openssl x509 -in lockdown/lockdown-cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum
    openssl rsa  -in lockdown/lockdown-key.pem  -pubout      | openssl pkey -pubin -outform DER | sha256sum

The advertised public key is **not compiled in**. `okcar-ffs-usbmux` extracts the PKCS#1 public half from the
certificate it installs (`/data/local/tmp/lockdown-cert.pem`) and answers `GetValue DevicePublicKey` with that, so the
advertised key cannot disagree with the certificate the same binary presents — the classic wired-mode failure this
page used to warn about. Generating the pair above is therefore the whole step.

If you would rather keep a compiled-in value, `DEVICE_PUBKEY_PKCS1_PEM_B64` in
`native/ffs-usbmux/okcar-ffs-usbmux.c` is still honoured when non-empty; the PKCS#1 form is required (plain `-pubout`
emits SubjectPublicKeyInfo, which the head unit rejects):

    openssl rsa -in lockdown/lockdown-key.pem -RSAPublicKey_out | base64 -w0   # about 568 characters for RSA-2048

The packager copies `lockdown/lockdown-{cert,key}.pem` into `payload/`, because `/data/local/tmp` is wiped by a factory
reset and the pair has to come back with the module. A mirror-only build needs none of this: without the pair the
packager prints `note: lockdown/<file> missing -- wired mode cannot complete EnableSessionSSL` and continues, and the
wired native logs `no DevicePublicKey available` if a host asks for the key anyway.

### 2.3 Step 3 — build the native helpers

    TREE=/path/to/aosp native/uinputd/build.sh      # -> build/okcar-uinputd
    TREE=/path/to/aosp native/ffs-usbmux/build.sh   # -> build/okcar-ffs-usbmux

Both build fully static bionic arm64 binaries for Android 11+ from the same AOSP tree: the module must run on any
arm64 head-unit phone without shipping or installing a runtime, and a dynamic dependency on a vendor library is
exactly what a different ROM does not have. The shared recipe:

| Piece | Source |
| --- | --- |
| compiler | `$T/prebuilts/clang/host/linux-x86/clang-r450784d/bin/clang` |
| flags | `-target aarch64-linux-android21 -static -O2 -nostdinc -nostdlib -fuse-ld=lld` |
| headers | clang resource dir, `$T/bionic/libc/include`, `bionic/libc/arch-arm64/include`, `kernel/uapi`, `kernel/uapi/asm-arm64`, `kernel/android/uapi` |
| crt / libc | `crtbegin_static.o`, `libc.a`, `crtend.o` from `out/soong/.intermediates/bionic/libc` |
| builtins | `libclang_rt.builtins-aarch64-android.a` from the clang resource dir |

`okcar-ffs-usbmux` adds the FunctionFS gadget, the usbmux side and the lockdown server, so it also links the tree's
static `libssl.a` and `libcrypto_static.a` with the BoringSSL headers — the lockdown handshake requires TLS.
`okcar-uinputd` needs none of that: it is the touch-injection backend for the virtual device declared by
`system/usr/idc/okcar-uinput-touch.idc`.

**Overrides.** `TREE=/path/to/aosp` selects the tree; otherwise the same `$HOME/aosp/*` and `$HOME/android/*`
auto-probe as the dex build applies. `OUT=/some/directory` redirects output; it defaults to `<repo>/build` and is
created with `mkdir -p`. With no tree each script prints `SKIP: needs an AOSP source tree (set TREE=/path/to/aosp);
skipping this build` on stderr and exits 0, after which the packager reports the binary as missing (§3).

**Confirm the result is static** — these expectations follow from the link flags above and were **not** executed on
the authoring host, which has no AOSP tree (§5):

    file build/okcar-uinputd       # expect: ELF 64-bit LSB executable, ARM aarch64, statically linked
    readelf -d build/okcar-uinputd # expect: "There is no dynamic section in this file"
    readelf -l build/okcar-uinputd | grep -c INTERP    # expect 0
    # repeat all three for build/okcar-ffs-usbmux

### 2.4 Step 4 — build the payload dex

    TREE=/path/to/aosp tools/build-daemon.sh           # -> build/okcar-link.dex

This is the whole port stack as one dex that `module/system/bin/okcar-linkd` runs under `app_process` as root. There
is no APK and no manifest — see §4.

*JDK resolution.* `$JH` first, then `$JDK`, then `$JAVA_HOME`, then `javac` on `PATH` (the JDK root is the parent of
its parent), then `$HOME/*/tools/jdk-*` and `/usr/lib/jvm/*`. If none of those yields a `javac` the script prints
`needs JDK 17: set JH=/path/to/jdk-17 (or JDK/JAVA_HOME), or put javac on PATH` and exits **2** — a hard stop, because
without the dex `okcar-linkd` has nothing to launch.

*SKIP behaviour.* With no tree it prints `SKIP: needs an AOSP source tree for the framework classes (set
TREE=/path/to/aosp); skipping this build` and exits 0.

*What it does.* `find` collects every `*.java` under `daemon/src` (including the shared wire codec `com.okcar.cast`,
which sits beside `com.okcar.link` so the app and daemon halves cannot drift apart) into a source list; `javac` compiles them, `jar` packs the classes, the tree's d8 dexes them:

    "$JH/bin/javac" --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" -cp "$CP" "@$OUT/sources.list"
    jar cf classes.jar
    java -cp "$TOOLS/../lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib <android.jar> --output "$OUT/dex" classes.jar
    cp "$OUT/dex/classes.dex" build/okcar-link.dex

*Classpath.* `$T/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar` plus
`$T/prebuilts/sdk/33/public/android.jar`, plus the hidden wifi, connectivity, tethering, graphics and media framework
jars when the tree has them. A port needs platform internals that `android.jar` does not expose, so the public SDK
alone cannot compile it.

*Why `--release 11` and `--min-api 29`.* d8 warns that class file version 56 and above is not officially supported, and the script records that an earlier build shipped such classes and the daemon died twice with ART-internal SIGSEGVs (a `ClassLinker::FindResolvedMethod` NULL deref on the wireless thread, and the `throw_dex_pc` check in `StackVisitor` on the same stack); the build therefore ships classes ART actually supports. `--min-api 29` is what makes Android 10 the floor. Intermediate output goes to `build/linkd-build/`; the final output is one `build/okcar-link.dex`, printed with `ls -l` by the script.

### 2.5 Step 5 — package the module

    tools/package-module.sh                       # -> build/okcarlink-1.0.0.zip

The packager runs the three build scripts from §2.3–§2.4 with stdout silenced (each still prints its `SKIP` line on
stderr when it cannot build); stages `module/` into `build/stage` (`cp -r` after `rm -rf`, so a stale stage never
leaks into a release); copies the built artifacts, printing a note when one is absent:

| Note | Meaning |
| --- | --- |
| `note: build/okcar-uinputd missing -- the matching feature reports its daemon as unavailable at runtime` | mirroring works, touches do not reach the car screen; with `REQUIRE_ARTIFACTS=1` this aborts instead |
| `note: build/okcar-ffs-usbmux missing -- ...` (same wording) | the wired switch cannot bind the gadget |
| `note: build/okcar-link.dex missing -- ...` (same wording) | the module installs but the daemon never starts |
| `note: lockdown/<file> missing -- wired mode cannot complete EnableSessionSSL` | wired mode fails at the TLS handshake instead of at startup |

Then: write the distribution variant marker `lite` into `webroot/variant`; apply explicit modes, because a workspace usually stores everything `0600` and a `0600` helper is not executable while a
`0600` `.idc` misclassifies the injected touchscreen as a plain pointer — `0755` on `post-fs-data.sh`, `service.sh`,
`customize.sh`, `uninstall.sh`, the `system/bin` helpers, the CGI directories and their `ctl` scripts, and the two
staged natives; `0644` on `module.prop`, `sepolicy.rule`, `action.sh`, `system/usr/idc/okcar-uinput-touch.idc` and the
payload/webroot files; run the leak gate; then zip `module.prop post-fs-data.sh service.sh customize.sh uninstall.sh
sepolicy.rule system webroot payload panel action.sh` (plus `README.md` when present) into `build/okcarlink-1.0.0.zip`
and print an `unzip -l` listing.

Two packaging details are load-bearing and documented in the script itself: `sepolicy.rule` must ship, because without
it the supplicant that owns the Wi-Fi Direct interface refuses to answer a client outside the framework's domain; and
`uninstall.sh` must ship, because the manager runs it before removing the module directory, to hand USB back and clear
the runtime state.

**The leak gate.** It scans every file under `build/stage` — the staged module tree, the dex, the native binaries and
the builder's lockdown pair — against two built-in pattern groups: private-key material markers (`BEGIN ... PRIVATE
KEY`, `ssh-rsa`, `ssh-ed25519`, `authorized_keys`) and credential shapes (tunnel tokens, upload tokens and URLs,
`api_key`-style names, `Bearer <token>` literals). A third, lab-specific group — private host names, internal CIDR
blocks, staff ids, and the accessory AP's SSID and passphrase — is deliberately *not* written into the script: it is
read at build time from an untracked `tools/lab-patterns.local` (one extended regex per line; override the path with
`LEAK_EXTRA_FILE`), so the repository never publishes the very strings the gate exists to keep out. Without that
file the gate still runs on the two built-in groups. The only exemption is deliberate and narrow:
`stage/payload/lockdown-cert.pem` and `stage/payload/lockdown-key.pem` are scanned **without** the private-key group,
because every install must present a real pair and a private-key hit there would fail every release. Any other hit
prints `LEAK: <file>` plus up to five matched literals and aborts with `release package contains lab
information/credentials; aborted.` and exit 1.

*Known gap (verified by replicating its patterns):* the scan uses `grep -I`, which skips files the utility considers binary, so the dex and the two native binaries are named in the comment but not actually searched as text. All staged text files are covered, and the gate's own patterns live in `tools/package-module.sh` (plus the optional untracked `tools/lab-patterns.local`), outside the scanned tree; do not rely on the gate for binary payloads.

*Stale-artifact trap.* The packager reuses whatever already exists in `build/`, so if a build skipped this run while `build/` still holds a previous artifact, no missing-artifact note is printed and the zip is built with the stale binary; delete the whole `build/` directory for a clean build. `REQUIRE_ARTIFACTS=1` catches the other half of that trap — an artifact that is absent entirely — by failing the run instead of shipping a degraded zip, which is what CI uses.

### 2.6 Step 6 — flash

Install `build/okcarlink-1.0.0.zip` from the KernelSU or Magisk manager (Modules -> install from storage); the manager
reports the module by its id, `okcarlink`. `post-fs-data.sh` runs early, then `service.sh` starts the daemon and
applies the switches.

First-boot expectations worth knowing before judging whether it worked:

*  `/data/local/tmp` is recreated with mode `0771 shell:shell` on every boot, so nothing under it survives a reboot.
*  `touch $MODDIR/disable-linkd` keeps the daemon from starting, and a `linkd` switch set to `off`
does the same for one boot; the WebUI starts it again when you switch it back on.
*  If the payload is missing, `service.sh` logs `payload missing` and does not start the daemon —
the expected look of a tree-less build (§3).
*  The mode defaults to `mirror`: `okcar-linkd` writes `/data/local/tmp/okcar-port-mode` when absent and injects it into the daemon's arguments.
*  The `wired` switch is **not** applied at boot. It reconfigures USB, which has taken field devices
into reboot loops, so boot leaves USB alone and you start wired mode deliberately (§2.7).

Where the control surface appears: a manager that offers a WebUI gets the module's action (`action.sh`) and its `module.prop` description; on any install the local panel is available:

    okcar-port-panel start        # busybox httpd -f on 127.0.0.1:8088 (PORT / OKCAR_PANEL_PORT override)
    okcar-port-panel url          # prints http://127.0.0.1:8088/?token=<token>

The address is always loopback; the token is 16 random hex bytes in `/data/local/tmp/okcar-port-panel.token` and the URL in `/data/local/tmp/okcar-port-panel.url`. `okcar-port-ctl` drives the same switches from a shell.
8088 is the only port literal in `module/`; use the panel commands above (`docs/en/webui.md`).

### 2.7 Step 7 — verification checklist

    okcar-port-check where     # module dir, module id, the ctl/linkd paths it resolved
    okcar-port-check caps      # what this kernel offers: FunctionFS, UDC, USB role, .ko loading
    okcar-port-check start     # start the daemon, then print all of the above

`okcar-port-check` resolves the module directory from its own location, then probes the installed layouts —
`/data/adb/modules/{okcar-port,okcar-port-lite,okcarlink}` and the same paths under `/data/adb/modules_update` — so it
works on a first install and after an in-place update. It reports install/activation state (warning while the zip
still sits under `modules_update`, because the module has not booted yet) and exits 1 when it cannot find the module
directory, `okcar-linkd` or `okcar-port-ctl` — a hard error meaning the package is incomplete.

Then check the logs and state, all under `/data/local/tmp`:

| Path | What it tells you |
| --- | --- |
| `okcar-linkd.log` | supervisor and daemon log — the last five lines also print from `okcar-linkd status` |
| `okcar-port.log` / `okcar-port-net.log` | the module log / network bring-up for the wireless path |
| `okcar-port-panel.log`, `.url`, `.token` | the local panel's log, URL and token |
| `okcar-port-*` and the `okcar-linkd.status` report | the state contract between helpers, daemon and WebUI: `okcar-port-switches.conf`, `okcar-port-mode`, `okcar-port-app.conf`, `okcar-carkit.state`, the `okcar-linkd.*` supervisor bookkeeping and the rest (`docs/en/architecture.md` §9.1) |

For the wireless path, the protocol self-tests in `daemon/src` are pure Java: `com.okcar.link.wireless.WirelessSelfTest` and its siblings have a `main()` and run on a host JVM with plain `javac`/`java` — no device and no AOSP tree. The optional Wi-Fi self-test reads `okcar-wifi-selftest.conf` (`ssid=`/`passphrase=`) when present; keep those values out of committed files.

For wired mode, only **after** the lockdown pair exists:

    okcar-port-ctl switch wired on        # deliberately reconfigures USB and binds the Apple gadget

`okcar-carkit` installs the pair to `/data/local/tmp/lockdown-cert.pem` (mode `0644`) and
`/data/local/tmp/lockdown-key.pem` (mode `0600`), skipping the copy when they already match; the native server listens on lockdownd port `62078` and talks to the Java side over the abstract socket `okcar-carkit`. A newly
installed pair takes effect only after the wired switch is cycled, because each process builds its TLS context once; a
mismatch is reported once as `tls: cert/key do not match`. Without a pair the handshake fails at `EnableSessionSSL`
with `NO_CERTIFICATE_SET` — full row set in `docs/en/crypto.md` §6.

### 2.8 Step 8 — CI on a hosted runner

A hosted runner cannot build this module from scratch: the hidden-API framework jars and the static bionic/BoringSSL
natives do not come from the public SDK, and a full AOSP checkout does not fit a build job. The repository therefore
splits the work (`.github/workflows/build.yml`):

*  **A machine with an AOSP tree** produces the parts CI cannot, once per change to `daemon/src` or `native/`:

       TREE=/path/to/aosp bash tools/make-aosp-deps.sh    # -> build/okcarlink-aosp-deps.tar.gz
       gh release create aosp-deps build/okcarlink-aosp-deps.tar.gz --prerelease \
         --title "AOSP build dependencies" --notes "framework jars + prebuilt arm64 natives for CI"

   The bundle holds `framework-jars/` (`framework-minus-apex.jar`, `android.jar`, plus the wifi / connectivity /
   graphics / media jars when the tree has them), `prebuilt/` (both natives, as built by §2.3) and `deps-manifest.txt`
   with a SHA-256 per file.

*  **CI** downloads that bundle from the release tag (`AOSP_DEPS_TAG`, default `aosp-deps`), checks it with
   `sha256sum -c`, installs JDK 17 and the SDK (`build-tools`, `platforms;android-33`), and assembles:

       FRAMEWORK_JARS=/tmp/deps/framework-jars \
       ANDROID_JAR=$ANDROID_SDK_ROOT/platforms/android-33/android.jar \
       D8=$ANDROID_SDK_ROOT/build-tools/34.0.0/d8 REQUIRE_ARTIFACTS=1 \
       bash tools/package-module.sh

   So `daemon/src` is compiled by CI on every run — the part worth building in public — while the framework jars and
   the two natives ride along as pinned inputs. `REQUIRE_ARTIFACTS=1` makes the packager fail rather than ship a zip
   that is missing an artifact. The job then verifies the zip (both natives, the dex and its `dex` magic, `webroot`,
   `module.prop` version), uploads it as a build artifact, and on a `v*` tag attaches the zip plus its `.sha256` to a
   release.

   The tradeoff is deliberate: the natives CI ships are copied, not rebuilt, so `tools/make-aosp-deps.sh` must be
   re-run and re-published whenever `native/` changes, or a release carries an older binary than its sources.

Either way the entry points are the same: `TREE=` for a local AOSP checkout (§2.1–§2.5), `FRAMEWORK_JARS=` for the
bundle above, and one `tools/package-module.sh` for both.

## 3. Troubleshooting

| Symptom | Cause | What to do |
| --- | --- | --- |
| `SKIP: needs an AOSP source tree for the framework classes (set TREE=/path/to/aosp); skipping this build`, exit 0 | `tools/build-daemon.sh` found no tree | set `TREE` or put the checkout where the probe looks; without it there is no dex and no daemon |
| `SKIP: needs an AOSP source tree (set TREE=/path/to/aosp); skipping this build`, exit 0, from either native build | same, for `native/uinputd/build.sh` / `native/ffs-usbmux/build.sh` | same; the packager then prints the matching `note: ... missing` line and the zip loses that capability |
| `needs JDK 17: set JH=...` and exit 2 | no `javac` in `$JH`, `$JDK`, `$JAVA_HOME`, `PATH`, `$HOME/*/tools/jdk-*`, `/usr/lib/jvm/*` | install a JDK or set `$JH`; the only hard stop among toolchain failures |
| `javac` rejects the target release, or d8 warns about class file version 56 | a JDK too old for `--release 11`, or classes above the supported version | use a `javac` that accepts `--release 11`; the script passes no preview flags, so a mismatch shows up immediately. Class files at or above 56 are the recorded crash case |
| `cannot find symbol` on platform-internal APIs | the compile classpath comes from the tree, not the public SDK | build the tree far enough to produce the hidden framework jars; wifi/connectivity/graphics/media jars are picked up only when present |
| `lockdownd: WARNING: no DevicePublicKey available - install the lockdown pair first (/data/local/tmp/lockdown-cert.pem; docs/en/building.md)` | a host asked for `GetValue DevicePublicKey` and no certificate is installed yet: a mirror-only build, or the pair was wiped | wired pairing cannot complete until the pair is there — generate it (§2.2), reinstall so `payload/` carries it, cycle the wired switch. Emitted at runtime by `native/ffs-usbmux/okcar-ffs-usbmux.c` |
| `okcar-port-check` exits 1 with a "not found / not executable" message | the zip lacks `okcar-linkd` or `okcar-port-ctl`, or the module directory was renamed again | rebuild from a complete `module/` staging; the message names which file was missing |
| Wired switch reports the daemon missing; UDC never binds | `build/okcar-ffs-usbmux` was skipped | rebuild with `TREE` set and re-flash (§2.3) |
| Mirroring on the head unit but no touches on the car screen | `build/okcar-uinputd` was skipped | same |
| `NO_CERTIFICATE_SET` at `EnableSessionSSL` — "no certificate set" | the pair was never installed, is unreadable or empty, including right after a factory reset because `/data/local/tmp` is wiped | cycle the wired switch so `okcar-carkit` re-installs `/data/local/tmp/lockdown-{cert,key}.pem`; full row set and the mismatch case in `docs/en/crypto.md` §6 |
| `tls: cert/key do not match` in the wired log | `lockdown/lockdown-cert.pem` and `lockdown/lockdown-key.pem` are not one pair | regenerate one matching pair (§2.2) and reinstall |
| A newly installed pair has no effect | each process builds its TLS context once | cycle the wired switch, or restart the module |
| `LEAK: <file>` ... `aborted`, exit 1, no zip produced | a staged file matches one of the three pattern groups | the output names the file and up to five matched literals; remove the content and re-run |
| A build "succeeded" but the zip contains last week's binaries | a skipped build was silently satisfied by a leftover `build/` artifact | `rm -rf build/` and rebuild (§2.5) |
| `REQUIRE_ARTIFACTS=1: refusing to package an incomplete module`, exit 1 | one of the three artifacts was absent and CI-style strictness is on | build the missing piece with `TREE` set, or fetch the dependency bundle (§2.8) |

## 4. What this repository does not build

**No Android app, no APK.** There is no Gradle project, no manifest and no install signature to check — by design: the stack that used to ship as an app (an activity for the UI plus a foreground service) paid for it with background
activity starts, per-network MAC randomisation, MediaProjection consent dialogs, app-scoped capture, and
install-signature checks on OEM ROMs. The same classes therefore run as a root daemon: `module/system/bin/okcar-linkd`
execs `CLASSPATH=<module>/payload/okcar-link.dex app_process com.okcar.link.daemon.LinkDaemon`, after filling in the
`ANDROID_*`/`BOOTCLASSPATH` environment `app_process` expects and locating the module directory from its own `$0`.
That is also why the build produces a dex rather than an APK: nothing ever installs it through the package manager.

**No prebuilt binaries.** Nothing under `native/` and no dex is committed; both native binaries and the payload dex
are produced by the three scripts in this repository, from source, against your own AOSP tree.

**No NDK, no vendor image.** The natives are cross-compiled with the AOSP tree's own clang and soong intermediates
rather than the NDK; no vendor partition or prebuilt sysroot is involved — the tree is used read-only for compiler, headers and static libraries.

**No companion apps from the internal kit.** The internal CarWith test application and the other companion packages
that shipped with the full kit are not part of this release; what ships is the module tree under `module/` and the
documents under `docs/` (see `docs/en/module.md` for what is and is not included, and `docs/en/architecture.md` §0 for
the public-rename notes).

## 5. What was verified while writing this page

Stated precisely, because a build page that overclaims is worse than none. Everything below was run on the authoring
host — Linux x86_64, JDK 17, an AOSP tree at `$HOME/okcaros/okcar-1.0`, `zip`, `unzip`, `file`, `openssl` — except
where a phone is named.

*  `bash -n` on all five scripts: `tools/build-daemon.sh`, `tools/package-module.sh`, `tools/make-aosp-deps.sh` and
both native builders.
*  A real build ran: `TREE=... bash tools/package-module.sh` produced a single 804,120-byte `build/okcar-link.dex`,
`build/okcar-uinputd` (1,964,472 B) and `build/okcar-ffs-usbmux` (9,397,744 B) — both natives confirmed by `file` as
`ELF 64-bit LSB executable, ARM aarch64, statically linked, for Android 33` — and a 36-file `build/okcarlink-1.0.0.zip`.
*  The CI path was run locally, without an AOSP checkout: `FRAMEWORK_JARS=<bundle>/framework-jars ANDROID_JAR=...
D8_JAR=... REQUIRE_ARTIFACTS=1 bash tools/package-module.sh` recompiled `daemon/src` against the bundle's framework
jars and produced the same 36-file zip from the bundle's prebuilt natives.
*  A real install ran on a KernelSU device (Android 15, arm64): `ksud module install` accepted the zip, the module came
back after a reboot as `okcarlink 1.0.0 (100)`, `service.sh` started `app_process64 ... com.okcar.link.daemon.LinkDaemon`
(recorded in `/data/local/tmp/okcar-linkd.status`), the wired helper bound `/dev/usb-ffs/okcar/ep0`, and the device-side
SHA-256 of the dex and both natives matched the host build byte for byte.
*  The leak gate and its `LEAK: <file>` failure mode were exercised against the staged tree while preparing the release.

Not verified, therefore still **untested**: the lockdown handshake against a real head unit end to end. The
`DevicePublicKey` reply is now extracted from the installed certificate at runtime; that code path is compiled and
ships, but it has not been observed completing a pairing against a car, and neither has the older compiled-in form.
The remaining wired-mode statements in §2.7 and `docs/en/crypto.md` come from the protocol implementation and recorded
sessions, not from a fresh measurement.

## 6. Sources

| Path | Authority for |
| --- | --- |
| `tools/build-daemon.sh` | the dex build: tree probe, JDK resolution order, `--release 11` / `--min-api 29`, output path |
| `native/uinputd/build.sh`, `native/ffs-usbmux/build.sh` | the static-bionic recipes, `OUT`/`TREE` overrides, BoringSSL for the TLS handshake |
| `tools/package-module.sh` | staging, artifact notes, mode discipline, the leak gate, the zip name |
| `docs/en/{crypto,architecture,module,webui}.md` | the lockdown identity and why no key material ships, module layout and the no-APK rationale, the build table, the runtime state contract (§9.1), what ships in `module/`, and the 8088 panel port |
| `module/module.prop` and the helpers `module/system/bin/{okcar-linkd,okcar-carkit,okcar-port-check,okcar-port-ctl,okcar-port-panel}` | the runtime names, verbs and state files a built module exhibits |
| `native/ffs-usbmux/okcar-ffs-usbmux.c` | the runtime-resolved `DevicePublicKey` (read from the installed certificate, with the compiled-in constant as an override), lockdownd port `62078`, the `okcar-carkit` abstract socket, the no-key-material warning |
| `README.md`, `README.zh-CN.md` | quick-start expectations this page must not contradict |
