#!/usr/bin/env bash
# ============================================================
# 拉取 / 校验网关需要的 JAR
#
# 顺序：
#   catvod-api.jar    官方 FongMi/CatVodSpider → 本仓库镜像
#   custom_spider.jar 官方/镜像都是 Android dex，**不会**自动放进 libs/
#                     （JVM 网关需要非 dex 的 class jar，见 read.md）
#
# 用法：
#   bash scripts/fetch-jars.sh
# ============================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$DIR/libs"
mkdir -p "$LIBS"

have() { [ -s "$1" ]; }

is_dex() {
  [ -f "$1" ] && grep -aq 'classes.dex' "$1"
}

download() {
  # $1=url $2=output
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 15 -o "$2.part" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$2.part" "$1"
  else
    echo "[fetch] 需要 curl 或 wget"
    return 1
  fi
  mv "$2.part" "$2"
}

echo "==> catvod-api.jar"
if have "$LIBS/catvod-api.jar"; then
  echo "    已存在，跳过"
else
  ok=0
  for url in \
    "https://raw.githubusercontent.com/FongMi/CatVodSpider/main/app/libs/catvod-api.jar" \
    "https://raw.githubusercontent.com/xiaoyugesvg/catjar/main/jars/catvod-api.jar"
  do
    echo "    尝试: $url"
    if download "$url" "$LIBS/catvod-api.jar"; then ok=1; break; fi
    rm -f "$LIBS/catvod-api.jar.part"
  done
  if [ "$ok" != "1" ]; then
    echo "    ❌ 下载失败。请手动放到 $LIBS/catvod-api.jar"
    exit 1
  fi
  echo "    ✅ 已下载"
fi

echo "==> custom_spider.jar"
if have "$LIBS/custom_spider.jar" && ! is_dex "$LIBS/custom_spider.jar"; then
  echo "    已存在且是 class jar，跳过"
elif is_dex "$LIBS/custom_spider.jar"; then
  echo "    ❌ libs/custom_spider.jar 是 Android dex 包，JVM 网关无法加载。"
  echo "       请换成从 FongMi/CatVodSpider 源码编译出的非 dex jar。"
  echo "       详见 read.md 的「关于 custom_spider.jar」。"
  exit 1
else
  echo "    缺少 libs/custom_spider.jar（官方预编译版是 dex，不能直接用）。"
  echo "    请从源码构建非 dex jar 后放进来，或改用 Python/QuickJS 源。"
  echo "    参考：read.md 的「关于 custom_spider.jar」。"
  exit 1
fi

echo
echo "JAR 就绪："
ls -lh "$LIBS"/*.jar 2>/dev/null || true
