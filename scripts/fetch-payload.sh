#!/usr/bin/env bash
# 下载构建需要的大文件：magpie 的安卓二进制和图标。
# 这两个东西不进仓库（104MB / 附件），首次构建前跑一次。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${MAGPIE_VERSION:-0.1.4868}"
PROXY="${https_proxy:-${HTTPS_PROXY:-}}"
PROXY_ARG=""
[ -n "$PROXY" ] && PROXY_ARG="-x $PROXY"

SO="$HERE/app/src/main/jniLibs/arm64-v8a/libmagpie-core.so"
ICON="$HERE/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

if [ ! -f "$SO" ]; then
  echo "== magpie-cli-android-arm64 v$VERSION"
  mkdir -p "$(dirname "$SO")"
  curl -fL $PROXY_ARG --retry 3 -o "$SO" \
    "https://github.com/yetone/magpie-releases/releases/download/v$VERSION/magpie-cli-android-arm64"
fi

if [ ! -f "$ICON" ]; then
  echo "== icon"
  mkdir -p "$(dirname "$ICON")"
  curl -fL $PROXY_ARG --retry 3 -o "$ICON" \
    "https://raw.githubusercontent.com/yetone/magpie/main/site/public/img/icon-256.png"
fi

chmod 0644 "$SO" "$ICON" 2>/dev/null || true
echo "ok: $SO ($(stat -c%s "$SO" 2>/dev/null || wc -c <"$SO") bytes)"
