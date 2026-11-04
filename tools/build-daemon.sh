#!/bin/bash
# Build the module payload: every com.okcar.link class (including the headless
# daemon entry point) as ONE dex that the module runs with app_process as root.
#
# There is no APK and no manifest: the stack runs inside a root daemon, not an app
# (see module/system/bin/okcar-linkd for why it starts that way).  The compile
# classpath is the platform framework (including hidden framework jars), so the
# payload still uses the platform internals a port needs.
#
# Output: build/okcar-link.dex  (tools/package-module.sh copies it to payload/)
set -e
cd "$(dirname "$0")"
REPO=$(cd .. && pwd)
T=${TREE:-}
JARS=${FRAMEWORK_JARS:-}
# Two ways to get the framework classes the payload compiles against:
#   TREE=/path/to/aosp            use that tree's soong output (a full local build);
#   FRAMEWORK_JARS=/path/to/jars  a flat directory with framework-minus-apex.jar and android.jar
#                                 (plus optionally framework-wifi/-connectivity/-graphics/-media.jar),
#                                 which is what CI consumes instead of a whole AOSP checkout.
if [ -z "$T" ] && [ -z "$JARS" ]; then
  for _c in "$HOME"/aosp/* "$HOME"/android/* "$HOME"/okcaros/*; do
    [ -d "$_c/out/target/common/obj/JAVA_LIBRARIES" ] && { T=$_c; break; }
  done
fi
if [ -z "$JARS" ] && { [ -z "$T" ] || [ ! -d "$T" ]; }; then
  echo "SKIP: needs an AOSP source tree (TREE=/path/to/aosp) or a framework jar directory (FRAMEWORK_JARS=...); skipping this build" >&2
  exit 0
fi
# Locate the JDK (the repository bakes in no local path):
# $JH -> $JDK -> $JAVA_HOME -> PATH -> usual install locations.
if [ -z "${JH:-}" ]; then
  if [ -n "${JDK:-}" ]; then JH=$JDK
  elif [ -n "${JAVA_HOME:-}" ]; then JH=$JAVA_HOME
  else
    _javac=$(command -v javac 2>/dev/null || true)
    if [ -n "$_javac" ]; then
      JH=$(dirname "$(dirname "$_javac")")
    else
      for _c in "$HOME"/*/tools/jdk-* /usr/lib/jvm/*; do
        [ -x "$_c/bin/javac" ] && { JH=$_c; break; }
      done
    fi
  fi
fi
if [ -z "${JH:-}" ] || [ ! -x "$JH/bin/javac" ]; then
  echo "needs JDK 17: set JH=/path/to/jdk-17 (or JDK/JAVA_HOME), or put javac on PATH" >&2
  exit 2
fi
OUT=$REPO/build/linkd-build
DIST=$REPO/build
if [ -n "$JARS" ]; then
  # CI path: flat jar directory, d8 from the SDK build-tools (D8=/path/to/d8 or D8_JAR=...).
  ANDROID_JAR=${ANDROID_JAR:-$JARS/android.jar}
  CP=$JARS/framework-minus-apex.jar:$ANDROID_JAR
  for m in framework-wifi framework-connectivity framework-graphics framework-media; do
    [ -f "$JARS/$m.jar" ] && CP=$CP:$JARS/$m.jar
  done
  if [ -z "${D8:-}" ] && [ -z "${D8_JAR:-}" ]; then
    echo "FRAMEWORK_JARS mode needs d8: set D8=/path/to/d8 or D8_JAR=/path/to/d8.jar (the SDK build-tools ship one)" >&2
    exit 2
  fi
else
  TOOLS=$T/prebuilts/sdk/tools/linux/bin
  FW=$T/out/target/common/obj/JAVA_LIBRARIES
  ANDROID_JAR=$T/prebuilts/sdk/33/public/android.jar
  CP=$FW/framework-minus-apex_intermediates/classes.jar:$ANDROID_JAR
  for m in framework-wifi.com.android.wifi framework-connectivity.com.android.tethering framework-graphics framework-media; do
    j=$(ls "$FW"/${m}*_intermediates/classes.jar 2>/dev/null | head -1)
    [ -n "$j" ] && CP=$CP:$j
  done
  if [ ! -f "$FW/framework-minus-apex_intermediates/classes.jar" ]; then
    echo "the AOSP tree has no framework-minus-apex classes.jar: run a full AOSP build first, or use FRAMEWORK_JARS=..." >&2
    exit 2
  fi
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found: $ANDROID_JAR (set ANDROID_JAR=/path/to/android.jar)" >&2
  exit 2
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$DIST"
# One source root: the shared wire-format codec (com.okcar.cast) lives here too,
# so the app and the daemon cannot drift apart.
SRC=$REPO/daemon/src

echo "== javac (full tree, incl. daemon) =="
find "$SRC" -name '*.java' > "$OUT/sources.list"
# --release 11: d8 warned "class file version >= 56 which is not officially supported" and the daemon
    # died twice with ART-internal SIGSEGVs (ClassLinker::FindResolvedMethod NULL deref on the
    # wireless thread, and the throw_dex_pc check in StackVisitor on the wireless stack).  Both are ART
    # verification/resolution paths, so ship classes ART actually supports.
"$JH/bin/javac" --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" -cp "$CP" @"$OUT/sources.list"
echo "compiled $(wc -l < "$OUT/sources.list") files"

echo "== d8 (single dex) =="
"$JH/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
if [ -n "${D8:-}" ]; then D8CMD=$D8
elif [ -n "${D8_JAR:-}" ]; then D8CMD="$JH/bin/java -cp $D8_JAR com.android.tools.r8.D8"
else D8CMD="$JH/bin/java -cp $TOOLS/../lib/d8.jar com.android.tools.r8.D8"
fi
# shellcheck disable=SC2086
$D8CMD --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/dex/classes.dex" "$DIST/okcar-link.dex"
ls -l "$DIST/okcar-link.dex"
echo "LINKD_BUILD_EXIT=$?"
