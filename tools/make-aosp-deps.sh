#!/bin/bash
# Produce the build dependencies that a hosted runner cannot obtain by itself (CI plan C):
#
#   build/okcarlink-aosp-deps.tar.gz    framework-jars/ + prebuilt/ + deps-manifest.txt
#
# Why: the payload compiles against the platform framework INCLUDING hidden APIs, and the two
# native binaries link static bionic (and static BoringSSL).  None of that exists in the public
# SDK, so a hosted runner cannot build it from scratch.  A machine with an AOSP tree builds it
# once and publishes this bundle as a release asset; .github/workflows/build.yml downloads it,
# compiles daemon/src against framework-jars/, and only assembles the module.
#
#   TREE=/path/to/aosp bash tools/make-aosp-deps.sh
#
# Publish it under the tag the workflow looks for (AOSP_DEPS_TAG, default "aosp-deps"):
#   gh release create aosp-deps build/okcarlink-aosp-deps.tar.gz --prerelease \
#     --title "AOSP build dependencies" \
#     --notes "Framework jars + prebuilt arm64 natives consumed by .github/workflows/build.yml."
#
# Re-run this and re-publish whenever daemon/src or the native sources change: the bundle pins
# the native binaries the release ships.
set -e
cd "$(dirname "$0")"
REPO=$(cd .. && pwd)
OUT=$REPO/build
DEPS=$OUT/aosp-deps
T=${TREE:-}
if [ -z "$T" ]; then
  for _c in "$HOME"/aosp/* "$HOME"/android/* "$HOME"/okcaros/*; do
    [ -d "$_c/out/target/common/obj/JAVA_LIBRARIES" ] && { T=$_c; break; }
  done
fi
if [ -z "$T" ] || [ ! -d "$T" ]; then
  echo "needs an AOSP source tree (set TREE=/path/to/aosp)" >&2
  exit 2
fi
echo "== framework jars from $T =="
rm -rf "$DEPS"
mkdir -p "$DEPS/framework-jars" "$DEPS/prebuilt"
FW=$T/out/target/common/obj/JAVA_LIBRARIES
cp "$FW/framework-minus-apex_intermediates/classes.jar" "$DEPS/framework-jars/framework-minus-apex.jar"
cp "$T/prebuilts/sdk/33/public/android.jar" "$DEPS/framework-jars/android.jar"
# Optional extras: present in every full tree, kept because the classpath recipe uses them.
for m in framework-wifi.com.android.wifi:framework-wifi framework-connectivity.com.android.tethering:framework-connectivity framework-graphics:framework-graphics framework-media:framework-media; do
  _src=$(ls "$FW"/${m%%:*}_intermediates/classes.jar 2>/dev/null | head -1)
  [ -n "$_src" ] && cp "$_src" "$DEPS/framework-jars/${m#*:}.jar"
done
ls -l "$DEPS/framework-jars"

echo "== native binaries =="
bash "$REPO/native/uinputd/build.sh"
bash "$REPO/native/ffs-usbmux/build.sh"
cp "$OUT/okcar-uinputd" "$OUT/okcar-ffs-usbmux" "$DEPS/prebuilt/"
echo "== manifest =="
{
  echo "okcarlink AOSP build dependencies (CI plan C): framework jars for the dex classpath plus the"
  echo "two static arm64 natives.  Consumed by .github/workflows/build.yml; nothing here is key"
  echo "material and nothing here is private to a builder."
  echo "generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "aosp tree: $(basename "$T")"
  echo
  (cd "$DEPS" && find framework-jars prebuilt -type f | sort | xargs sha256sum)
} > "$DEPS/deps-manifest.txt"
cat "$DEPS/deps-manifest.txt"

echo "== bundle =="
tar -czf "$OUT/okcarlink-aosp-deps.tar.gz" -C "$DEPS" .
ls -l "$OUT/okcarlink-aosp-deps.tar.gz"
echo
echo "publish it as a release asset named okcarlink-aosp-deps.tar.gz (workflow tag: aosp-deps)"
