#!/bin/bash
# Cross-compile okcar-uinputd for Android 11+ (arm64, fully static bionic).
#
# This repository ships no standalone NDK, so we assemble the classic static-bionic
# recipe from an AOSP source tree: AOSP clang + bionic headers + the soong-built
# static libc and crt objects (the same pieces soong links for system components).
#
# Output: build/okcar-uinputd  (tools/package-module.sh places it into system/bin/)
set -e
cd "$(dirname "$0")"
REPO=$(cd ../.. && pwd)
OUT=${OUT:-$REPO/build}
mkdir -p "$OUT"
T=${TREE:-}
# AOSP source tree: set TREE=/path/to/aosp, or place the checkout in one of the usual locations.
if [ -z "$T" ]; then
  for _c in "$HOME"/aosp/* "$HOME"/android/* "$HOME"/okcaros/*; do
    [ -d "$_c/out/target/common/obj/JAVA_LIBRARIES" ] && { T=$_c; break; }
  done
fi
if [ -z "$T" ] || [ ! -d "$T" ]; then
  echo "SKIP: needs an AOSP source tree (set TREE=/path/to/aosp); skipping this build" >&2
  exit 0
fi
CLANG=${CLANG:-$T/prebuilts/clang/host/linux-x86/clang-r450784d/bin/clang}
RES=$("$CLANG" -print-resource-dir)
CB=$T/out/soong/.intermediates/bionic/libc/crtbegin_static/android_arm64_armv8-a/crtbegin_static.o
CE=$T/out/soong/.intermediates/bionic/libc/crtend_android/android_arm64_armv8-a_apex31/obj/bionic/libc/arch-common/bionic/crtend.o
LIBC=$T/out/soong/.intermediates/bionic/libc/libc/android_arm64_armv8-a_static/libc.a

"$CLANG" -target aarch64-linux-android21 -static -O2 -nostdinc -nostdlib -fuse-ld=lld \
  -I"$RES/include" \
  -I"$T/bionic/libc/include" -I"$T/bionic/libc/arch-arm64/include" \
  -I"$T/bionic/libc/kernel/uapi" -I"$T/bionic/libc/kernel/uapi/asm-arm64" \
  -I"$T/bionic/libc/kernel/android/uapi" \
  -o "$OUT/okcar-uinputd" okcar-uinputd.c \
  "$CB" "$LIBC" "$CE" "$RES/lib/linux/libclang_rt.builtins-aarch64-android.a"

file "$OUT/okcar-uinputd"
echo "built $OUT/okcar-uinputd"
