#!/bin/bash
# Host self-test for the lockdown plist module of the wired CarPlay device side.
#
# okcar-ffs-usbmux itself needs a FunctionFS gadget, BoringSSL and an arm64 target, so it cannot be
# exercised here.  lockdown_proto.c is the dependency-free half - the part that decides which request
# we are looking at and what bytes we answer with - and that half is pinned below.  The replies are
# the ones the emulator's LockdownCarKitClient requires: EnableSessionSSL=true for StartSession, and an
# integer Port (no EnableServiceSSL) for StartService com.apple.carkit.service.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

CC=${CC:-}
if [ -z "$CC" ]; then
  for c in cc gcc clang; do command -v "$c" >/dev/null 2>&1 && { CC=$c; break; }; done
fi
if [ -z "$CC" ]; then
  echo "SKIP: no host C compiler (set CC=)" >&2
  exit 0
fi

"$CC" -std=c99 -Wall -Wextra -O1 -o "$OUT/lockdown_proto_test" \
      "$HERE/lockdown_proto.c" "$HERE/lockdown_proto_test.c" || {
  echo "LOCKDOWN PROTO SUITE FAILED (compile)" >&2
  exit 1
}
"$OUT/lockdown_proto_test"
