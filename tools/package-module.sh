#!/bin/bash
# Package the okcarlink KernelSU/Magisk module: build/okcarlink-1.0.0.zip
#
#   bash tools/package-module.sh           -> build/okcarlink-1.0.0.zip (id okcarlink)
#
# The phone side needs no companion app: the port daemon is ONE dex run with app_process as
# root, and the car side uses the head unit's own iPhone-link app
# (module/system/bin/okcar-linkd -> payload/okcar-link.dex).  What it does need are
# two native binaries, built here first:
#   build/okcar-uinputd     touch injection (native/uinputd/build.sh)
#   build/okcar-ffs-usbmux  wired-mode FunctionFS/mux/lockdown server (native/ffs-usbmux/build.sh)
# Both need an AOSP tree (TREE=...); without one the script still packages the module,
# and the affected switches report the missing binary at runtime -- unless REQUIRE_ARTIFACTS=1
# (used by CI), which turns a missing artifact into a build failure instead.  A build can also be
# fed prebuilt artifacts: FRAMEWORK_JARS=... for the dex (see tools/build-daemon.sh) and files
# already present in build/ for the natives (tools/make-aosp-deps.sh produces both).
#
# The wired switch additionally needs a lockdown identity (a matching cert/key PEM
# pair).  This repository deliberately ships no key material: put your own pair in
# lockdown/lockdown-cert.pem + lockdown/lockdown-key.pem (gitignored) and it is packed
# into payload/.  docs/en/architecture.md / docs/zh/architecture.md explain how to
# generate a matching pair.  Wired mode stays off until the pair is readable at
# /data/local/tmp/lockdown-{cert,key}.pem, so a missing pair degrades gracefully.
set -e
cd "$(dirname "$0")"
REPO=$(cd .. && pwd)
OUT=$REPO/build
mkdir -p "$OUT"

bash "$REPO/native/uinputd/build.sh" >/dev/null
bash "$REPO/native/ffs-usbmux/build.sh" >/dev/null
# Same classpath recipe as the daemon build: framework classes incl. hidden APIs.
bash "$REPO/tools/build-daemon.sh" >/dev/null

STAGE=$OUT/stage
chmod -R u+rwX "$STAGE" 2>/dev/null || true
rm -rf "$STAGE"
mkdir -p "$STAGE/payload"
cp -r "$REPO/module/." "$STAGE/"

# REQUIRE_ARTIFACTS=1 (CI) refuses to package a module that is missing any of the three
# artifacts: a degraded zip built from a partial environment must not look like a release.
missing=0
for _a in okcar-uinputd:system/bin/okcar-uinputd okcar-ffs-usbmux:system/bin/okcar-ffs-usbmux okcar-link.dex:payload/okcar-link.dex; do
  _src=$OUT/${_a%%:*}; _dst=$STAGE/${_a#*:}
  if [ -f "$_src" ]; then
    cp "$_src" "$_dst"
  else
    echo "note: build/${_a%%:*} missing -- the matching feature reports its daemon as unavailable at runtime" >&2
    missing=1
  fi
done
if [ "$missing" = "1" ] && [ "${REQUIRE_ARTIFACTS:-0}" = "1" ]; then
  echo "REQUIRE_ARTIFACTS=1: refusing to package an incomplete module" >&2
  exit 1
fi

# Builder-supplied lockdown identity (see docs); never committed, copied when present.
for _f in lockdown-cert.pem lockdown-key.pem; do
  if [ -f "$REPO/lockdown/$_f" ]; then
    cp "$REPO/lockdown/$_f" "$STAGE/payload/$_f"
  else
    echo "note: lockdown/$_f missing -- wired mode cannot complete EnableSessionSSL; see docs" >&2
  fi
done

# The WebUI keys its layout off this marker ("lite" = the distribution layout).
printf %s lite > "$STAGE/webroot/variant"

# Explicit modes: the workspace often stores everything 0600 (a 0600 helper is not
# executable, an unreadable .idc classifies the virtual device as a plain pointer,
# a 0600 webroot page is unreadable by the manager's WebView).
chmod 0755 "$STAGE/post-fs-data.sh" "$STAGE/service.sh" "$STAGE/customize.sh" "$STAGE/uninstall.sh" \
           "$STAGE/system/bin/okcar-carkit" "$STAGE/system/bin/okcar-carwith-svc" \
           "$STAGE/system/bin/okcar-linkd" "$STAGE/system/bin/okcar-port-check" \
           "$STAGE/system/bin/okcar-port-ctl" "$STAGE/system/bin/okcar-port-panel" \
           "$STAGE/system/bin/okcar-role" 2>/dev/null || true
find "$STAGE/payload" "$STAGE/webroot" -type f -exec chmod 0644 {} + 2>/dev/null || true
chmod 0644 "$STAGE/module.prop" "$STAGE/system/usr/idc/okcar-uinput-touch.idc" \
           "$STAGE/sepolicy.rule" "$STAGE/action.sh" 2>/dev/null || true
chmod 0755 "$STAGE/webroot/cgi-bin" "$STAGE/panel/cgi-bin" "$STAGE/webroot/cgi-bin/ctl" "$STAGE/panel/cgi-bin/ctl" 2>/dev/null || true
[ -f "$STAGE/system/bin/okcar-uinputd" ] && chmod 0755 "$STAGE/system/bin/okcar-uinputd"
[ -f "$STAGE/system/bin/okcar-ffs-usbmux" ] && chmod 0755 "$STAGE/system/bin/okcar-ffs-usbmux"
[ -f "$STAGE/README.md" ] && chmod 0644 "$STAGE/README.md"

# ── Outbound-package leak gate ─────────────────────────────────────────────────────
# A published package must carry no private lab information: private server addresses,
# internal subnets, credentials/tokens/keys, or the on-site accessory AP's identity.
# Fail during the build instead of finding out after it shipped.  The scan covers
# binaries (the dex) too.
#
# The lab-specific identifiers this gate exists to catch -- private host names, internal
# CIDRs, staff ids, the accessory AP's SSID and passphrase -- are deliberately NOT written
# here: committing them would publish the very strings the gate keeps out.  Keep them in
# an untracked file, one extended-regex alternation per line (default
# tools/lab-patterns.local, git-ignored; override with LEAK_EXTRA_FILE).  Without that file
# the gate still runs on the generic credential and key rules below.
LEAK_LAB=''
LEAK_EXTRA_FILE=${LEAK_EXTRA_FILE:-$(dirname "$0")/lab-patterns.local}
if [ -f "$LEAK_EXTRA_FILE" ]; then
  LEAK_LAB=$(sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d' "$LEAK_EXTRA_FILE" | tr '\n' '|' | sed -e 's/|$//')
  [ -n "$LEAK_LAB" ] && LEAK_LAB="|$LEAK_LAB"
fi
LEAK_BASE='TUNNEL_TOKEN|TUNNEL_SERVER|UPLOAD_TOKEN|UPLOAD_URL|api[_-]?key|Bearer [A-Za-z0-9._-]+'
LEAK_KEYS='|BEGIN [A-Z ]*PRIVATE KEY|ssh-rsa|ssh-ed25519|authorized_keys'
LEAK_TAIL="$LEAK_LAB"
LEAK_PATTERNS="$LEAK_BASE$LEAK_KEYS$LEAK_TAIL"
LEAK_PATTERNS_NO_KEY="$LEAK_BASE$LEAK_TAIL"
# The builder's own lockdown identity is a deliberate exception to the private-key rule
# (every install presents it as the fake iPhone's certificate); it must be a real
# key pair to work at all.  Scan it with the other rules only.  Everything else still
# fails the build.
LEAK_OK="$STAGE/payload/lockdown-cert.pem $STAGE/payload/lockdown-key.pem"
leak=0
for f in $(find "$STAGE" -type f); do
  _pat=$LEAK_PATTERNS
  case " $LEAK_OK " in *" $f "*) _pat=$LEAK_PATTERNS_NO_KEY ;; esac
  if grep -qIE "$_pat" "$f" 2>/dev/null; then
    echo "LEAK: $f" >&2
    grep -aoIE "$_pat" "$f" | sort -u | head -5 >&2
    leak=1
  fi
done
if [ "$leak" = "1" ]; then
  echo "release package contains lab information/credentials; aborted." >&2
  exit 1
fi

cd "$STAGE"
# sepolicy.rule must be in the zip: without the rule the wpa_supplicant that owns p2p0
# refuses to answer a client outside the framework's domain.
# uninstall.sh must be in the zip: the root manager runs it before removing the module
# directory to hand USB back and clear the runtime state.
ZIP_FILES="module.prop post-fs-data.sh service.sh customize.sh uninstall.sh sepolicy.rule system webroot payload panel action.sh"
[ -f "$STAGE/README.md" ] && ZIP_FILES="$ZIP_FILES README.md"
zip -qr "$OUT/okcarlink-1.0.0.zip" $ZIP_FILES
echo "built $OUT/okcarlink-1.0.0.zip (id okcarlink)"
unzip -l "$OUT/okcarlink-1.0.0.zip"
