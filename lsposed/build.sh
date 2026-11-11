#!/usr/bin/env bash
# Build the OkCarLink SDP-fix LSPosed module APK.
#
# Inputs (already available):
#   * build-tools 34 (aapt2 / d8 / apksigner / zipalign)  — set BT34
#   * framework jar with hidden APIs                      — set FRAMEWORK
#   * JDK (javac / keytool)
# Output: build/okcar-sdpfix-1.0.apk (debug-signed)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
BT34=${BT34:-/home/enthlinn/.dsh/state/superagent/work/merge-okcarlink-branch-5c90d4a58dd5/bt34/android-14}
DEPS=${DEPS:-/home/enthlinn/.dsh/state/superagent/work/merge-okcarlink-branch-5c90d4a58dd5}
FRAMEWORK="$DEPS/framework-jars/framework-minus-apex.jar"
AAPT2="$BT34/aapt2"
D8="$BT34/d8"
APKSIGNER="$BT34/apksigner"
ZIPALIGN="$BT34/zipalign"

BUILD=$HERE/build
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/stub-classes" "$BUILD/stage/assets"

echo "== javac =="
/usr/lib/jvm/java-17-openjdk-amd64/bin/javac -nowarn \
  -cp "$FRAMEWORK" \
  -d "$BUILD/stub-classes" \
  $(find "$HERE/app/src/stub" -name '*.java') 2>&1 | grep -v "^warning" || true
/usr/lib/jvm/java-17-openjdk-amd64/bin/javac -nowarn \
  -cp "$FRAMEWORK:$BUILD/stub-classes" \
  -d "$BUILD/classes" \
  "$HERE/app/src/com/okcar/sdpfix/OkCarSdpFix.java"

echo "== d8 =="
mkdir -p "$BUILD/dex"
"$D8" --release --min-api 29 --lib "$FRAMEWORK" \
  $(find "$BUILD/classes" -name '*.class') \
  --output "$BUILD/dex"
# 只有 hook 类进 dex；stub 不打包（运行期由 LSPosed 提供，避免遮蔽真实现）。

echo "== aapt2 =="

"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$DEPS/framework-jars/android.jar" \
  --manifest "$HERE/app/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 34


echo "== stage dex + assets =="
mkdir -p "$BUILD/stage"
cp "$BUILD/base.apk" "$BUILD/stage/base.apk"
mkdir -p "$BUILD/stage/assets"
cp "$HERE/app/assets/xposed_init" "$BUILD/stage/assets/xposed_init"
cp "$BUILD/dex/classes.dex" "$BUILD/stage/classes.dex"
( cd "$BUILD/stage" && zip -q base.apk classes.dex assets/xposed_init )

echo "== zipalign + sign =="
KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass okcarsdp -keypass okcarsdp \
    -alias okcar -dname "CN=OkCarLink SDP fix" -keyalg RSA -keysize 2048 \
    -validity 10000 >/dev/null 2>&1
fi
"$ZIPALIGN" -f 4 "$BUILD/stage/base.apk" "$BUILD/aligned.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:okcarsdp --key-pass pass:okcarsdp \
  --out "$BUILD/okcar-sdpfix-1.0.apk" "$BUILD/aligned.apk"
ls -la "$BUILD/okcar-sdpfix-1.0.apk"
echo "built: $BUILD/okcar-sdpfix-1.0.apk"
