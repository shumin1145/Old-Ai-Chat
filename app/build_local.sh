#!/usr/bin/env bash
# OAC 1.4 (TLS-fixed) build -- from-source APK, Windows Git Bash
# 关键：原生 Windows 工具(aapt/zipalign/java)只认 C:/ 路径，故一律用 cygpath -m 转换
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"   # unix 路径，供 bash 内建命令用
ROOT_W="$(cygpath -m "$ROOT")"          # C:/... 供原生 Windows 工具用
SDK_W="C:/AndroidSDK"
BT_W="$SDK_W/build-tools/30.0.3"
# aapt 资源编译用高 API jar（含 state_activated 等 API11+ 属性）；javac/d8 用已验证的 android-10.jar
PLAT_JAVAC_W="$SDK_W/platforms/temp10/android-10/android.jar"
PLAT_AAPT_W="$SDK_W/platforms/android-33/android.jar"
KS_W="$ROOT_W/oac.keystore"
JAVA_BIN="$(dirname "$(which javac)")"

echo "==> ROOT : $ROOT_W"
echo "==> PLAT_JAVAC : $PLAT_JAVAC_W"
echo "==> PLAT_AAPT  : $PLAT_AAPT_W"
echo "==> BT   : $BT_W"
echo "==> JAVA : $JAVA_BIN"
[ -f "$BT_W/aapt.exe" ] || { echo "ERROR: aapt.exe missing"; exit 1; }
[ -f "$PLAT_JAVAC_W" ]  || { echo "ERROR: android.jar missing"; exit 1; }
[ -f "$PLAT_AAPT_W" ]   || { echo "ERROR: aapt android.jar missing"; exit 1; }

echo "==> clean"
rm -rf "$ROOT/gen" "$ROOT/obj" "$ROOT/bin"
mkdir -p "$ROOT/gen" "$ROOT/obj" "$ROOT/bin"

echo "==> 1) aapt R.java"
"$BT_W/aapt.exe" package -f -M "$ROOT_W/AndroidManifest.xml" -S "$ROOT_W/res" -I "$PLAT_AAPT_W" -J "$ROOT_W/gen" -m

echo "==> 2) javac"
mapm() { while read -r f; do [ -n "$f" ] && cygpath -m "$f"; done; }
SRCS=($(find "$ROOT/src" -name '*.java' | mapm))
RGEN=($(find "$ROOT/gen" -name '*.java' | mapm))
LIBS=($(find "$ROOT/libs" -name '*.jar' | mapm))
CP="$PLAT_JAVAC_W;$(IFS=';'; echo "${LIBS[*]}")"
"$JAVA_BIN/javac" -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$CP" -d "$ROOT_W/obj" "${SRCS[@]}" "${RGEN[@]}"
echo "    javac OK, classes: $(find "$ROOT/obj" -name '*.class' | wc -l)"

echo "==> 3) d8 (app + libs -> classes.dex, min-api 7)"
( cd "$ROOT_W/obj" && "$JAVA_BIN/jar" cf "$ROOT_W/bin/classes.jar" . )
D8ARGS=(--output "$ROOT_W/bin/classes-dex.zip" --lib "$PLAT_JAVAC_W" --min-api 7 "$ROOT_W/bin/classes.jar" "${LIBS[@]}")
"$JAVA_BIN/java" -cp "$BT_W/lib/d8.jar" com.android.tools.r8.D8 "${D8ARGS[@]}"
mkdir -p "$ROOT/bin/dex_extract"
( cd "$ROOT/bin/dex_extract" && unzip -oq "$ROOT/bin/classes-dex.zip" )
for d in "$ROOT/bin/dex_extract"/classes*.dex; do
  mv "$d" "$ROOT/bin/$(basename "$d")"
done
rm -rf "$ROOT/bin/classes-dex.zip" "$ROOT/bin/dex_extract"
echo "    dex files: $(ls "$ROOT/bin"/classes*.dex)"

echo "==> 4) aapt package (resources)"
"$BT_W/aapt.exe" package -f -M "$ROOT_W/AndroidManifest.xml" -S "$ROOT_W/res" -I "$PLAT_AAPT_W" -F "$ROOT_W/bin/app.unsigned.apk"

echo "==> 5) add classes.dex"
for d in "$ROOT/bin"/classes*.dex; do
  ( cd "$ROOT_W/bin" && "$BT_W/aapt.exe" add app.unsigned.apk "$(basename "$d")" )
done

if [ ! -f "$KS_W" ]; then
  echo "==> generate keystore (oacbeta / oacbeta123)"
  "$JAVA_BIN/keytool" -genkeypair -keystore "$KS_W" -storetype JKS -storepass oacbeta123 \
    -alias oacbeta -keypass oacbeta123 -keyalg RSA -keysize 2048 -validity 20000 \
    -dname "CN=OAC,O=OAC,C=CN"
fi

echo "==> 6) zipalign"
"$BT_W/zipalign.exe" -f -p 4 "$ROOT_W/bin/app.unsigned.apk" "$ROOT_W/bin/app.aligned.apk"

echo "==> 7) apksigner (V1+V2)"
"$JAVA_BIN/java" -jar "$BT_W/lib/apksigner.jar" sign --v1-signing-enabled true --v2-signing-enabled true \
  --ks "$KS_W" --ks-pass pass:oacbeta123 --ks-key-alias oacbeta --key-pass pass:oacbeta123 \
  --out "$ROOT_W/bin/OAC_1.6.apk" "$ROOT_W/bin/app.aligned.apk"

echo ""
echo "BUILD OK: $ROOT_W/bin/OAC_1.6.apk"
du -h "$ROOT/bin/OAC_1.6.apk"
