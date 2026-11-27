#!/usr/bin/env bash
# Builds the magpie APK without Gradle: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${SDK:-/tmp/sdk}"
BT="$SDK/android-16"
AJ="$SDK/android-35/android.jar"
OUT="${OUT:-$(cd "$HERE/../../outputs" && pwd)}"
NAME="${NAME:-magpie-android-arm64}"
KEYTOOL="/d/Java/bin/keytool.exe"

# VERSION=0.1.4900 bash build.sh  -> 更新到那个 release
VERSION_NAME="${VERSION:-0.1.4868}"
IFS=. read -r V_MAJOR V_MINOR V_PATCH <<< "$VERSION_NAME"
REV="${REV:-0}"   # 同一个 magpie 版本重打包时 +1
VERSION_CODE=$(( V_MAJOR * 1000000 + V_MINOR * 10000 + V_PATCH * 10 + REV ))

cd "$HERE"

# the payload: the official Android/arm64 CLI build, fetched once
SO=lib/arm64-v8a/libmagpie-core.so
SOV=lib/arm64-v8a/.version
if [ ! -f "$SO" ] || [ "$(cat "$SOV" 2>/dev/null)" != "$VERSION_NAME" ]; then
  echo "== fetching magpie-cli-android-arm64 v$VERSION_NAME"
  mkdir -p lib/arm64-v8a
  rm -f "$SO"
  curl -fL --retry 3 -o "$SO" \
    "https://github.com/yetone/magpie-releases/releases/download/v$VERSION_NAME/magpie-cli-android-arm64"
  echo "$VERSION_NAME" > "$SOV"
fi
chmod 0644 "$SO"

# 图标同样不进仓库，缺了就取 magpie 官方那张
ICON=res/mipmap-xxxhdpi/ic_launcher.png
if [ ! -f "$ICON" ]; then
  echo "== fetching icon"
  mkdir -p "$(dirname "$ICON")"
  curl -fL --retry 3 -o "$ICON"     "https://raw.githubusercontent.com/yetone/magpie/main/site/public/img/icon-256.png"
fi

rm -rf build
rm -f "$OUT/$NAME.apk.idsig"
mkdir -p build/gen build/classes build/dex build/res "$OUT"

echo "== aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res/resources.zip

echo "== aapt2 link"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  -R build/res/resources.zip \
  --java build/gen \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "== javac"
{ find src build/gen -name '*.java'; } > build/sources.txt
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d build/classes @build/sources.txt

echo "== d8"
{ find build/classes -name '*.class'; } > build/classes.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJ" --min-api 24 --release \
  --output build/dex @build/classes.txt
ls -l build/dex

echo "== package"
python pack.py build/base.apk lib/arm64-v8a/libmagpie-core.so build/dex/classes.dex build/unsigned.apk

echo "== zipalign"
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk

echo "== keystore"
KS="$HERE/magpie.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storetype PKCS12 \
    -alias magpie -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass magpie123 -keypass magpie123 \
    -dname "CN=magpie, OU=magpie, O=magpie, L=Shanghai, C=CN" >/dev/null
fi

echo "== apksigner"
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:magpie123 --key-pass pass:magpie123 \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$NAME.apk" build/aligned.apk

java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT/$NAME.apk" | head -4
ls -lh "$OUT/$NAME.apk"
echo "done -> $OUT/$NAME.apk"
