#!/usr/bin/env bash
# OAC 1.4 (TLS-fixed) build script  --  Linux / macOS / WSL
# Standard legacy Android project (Eclipse/Ant layout): src/ res/ libs/ AndroidManifest.xml
# Requires: Android SDK with build-tools 30.0.3 (edit BT below) and a platform android.jar.
# No .so / no native libs. minSdk 9 (Android 2.3). SpongyCastle provides TLS.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

# ---- SDK location: prefer local.properties, else $ANDROID_HOME, else default ----
SDKDIR=""
if [ -f "$ROOT/local.properties" ]; then
  SDKDIR="$(grep -E '^\s*sdk\.dir\s*=' "$ROOT/local.properties" | head -1 | sed -E 's/.*=\s*//' | tr -d '\r')"
fi
if [ -z "$SDKDIR" ]; then SDKDIR="${ANDROID_HOME:-$HOME/Android/Sdk}"; fi

BT="$SDKDIR/build-tools/30.0.3"
PLAT="$SDKDIR/platforms/android-28/android.jar"
if [ ! -f "$PLAT" ]; then
  PLAT="$(find "$SDKDIR/platforms" -name android.jar 2>/dev/null | head -1)"
  [ -n "$PLAT" ] || { echo "ERROR: cannot find android.jar under $SDKDIR/platforms"; exit 1; }
fi
KS="$ROOT/oac.keystore"

echo "==> SDK  : $SDKDIR"
echo "==> PLAT : $PLAT"
echo "==> BT   : $BT"
[ -x "$BT/aapt" ] || { echo "ERROR: aapt not found in $BT"; exit 1; }

echo "==> clean"
rm -rf "$ROOT/gen" "$ROOT/obj" "$ROOT/bin"
mkdir -p "$ROOT/gen" "$ROOT/obj" "$ROOT/bin"

echo "==> 1) aapt R.java"
"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$PLAT" -J "$ROOT/gen" -m

echo "==> 2) javac"
SRCS=($(find "$ROOT/src" -name '*.java'))
RGEN=($(find "$ROOT/gen" -name '*.java'))
LIBS=($(find "$ROOT/libs" -name '*.jar'))
CP="$PLAT:$(IFS=':'; echo "${LIBS[*]}")"
javac -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$CP" -d "$ROOT/obj" "${SRCS[@]}" "${RGEN[@]}"

echo "==> 3) jar + d8 (min-api 9)"
( cd "$ROOT/obj" && jar cf "$ROOT/bin/classes.jar" . )
D8ARGS=(--output "$ROOT/bin/classes-dex.zip" --lib "$PLAT" --min-api 9 "$ROOT/bin/classes.jar" "${LIBS[@]}")
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "${D8ARGS[@]}"
mkdir -p "$ROOT/bin/dex_extract"
( cd "$ROOT/bin/dex_extract" && unzip -oq "$ROOT/bin/classes-dex.zip" )
for d in "$ROOT/bin/dex_extract"/classes*.dex; do
  mv "$d" "$ROOT/bin/$(basename "$d")"
done
rm -rf "$ROOT/bin/classes-dex.zip" "$ROOT/bin/dex_extract"

echo "==> 4) aapt package (resources)"
"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$PLAT" -F "$ROOT/bin/app.unsigned.apk"

echo "==> 5) add classes.dex (multidex aware)"
for d in "$ROOT/bin"/classes*.dex; do
  ( cd "$ROOT/bin" && "$BT/aapt" add app.unsigned.apk "$(basename "$d")" )
done

if [ ! -f "$KS" ]; then
  echo "==> generate keystore (oacbeta / oacbeta123)"
  keytool -genkeypair -keystore "$KS" -storetype JKS -storepass oacbeta123 \
    -alias oacbeta -keypass oacbeta123 -keyalg RSA -keysize 2048 -validity 20000 \
    -dname "CN=OAC,O=OAC,C=CN"
fi

echo "==> 6) zipalign"
"$BT/zipalign" -f -p 4 "$ROOT/bin/app.unsigned.apk" "$ROOT/bin/app.aligned.apk"

echo "==> 7) apksigner"
"$BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true \
  --ks "$KS" --ks-pass pass:oacbeta123 --ks-key-alias oacbeta --key-pass pass:oacbeta123 \
  --out "$ROOT/bin/OAC_1.4.apk" "$ROOT/bin/app.aligned.apk"

echo ""
echo "BUILD OK: $ROOT/bin/OAC_1.4.apk"
du -h "$ROOT/bin/OAC_1.4.apk"
