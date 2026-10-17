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
SPIDER_RELEASE="https://github.com/xiaoyugesvg/catjar/releases/download/spider-jvm"

if have "$LIBS/custom_spider.jar" && ! is_dex "$LIBS/custom_spider.jar"; then
  echo "    已存在且是 class jar，跳过"
else
  # 已有的是 dex 就删掉，避免入口脚本直接报错
  if is_dex "$LIBS/custom_spider.jar"; then
    echo "    现有 custom_spider.jar 是 dex，删除后重新获取"
    rm -f "$LIBS/custom_spider.jar"
  fi

  ok=0
  for url in "${CUSTOM_SPIDER_URL:-}" "$SPIDER_RELEASE/custom_spider.jar"; do
    [ -z "$url" ] && continue
    echo "    尝试: $url"
    if download "$url" "$LIBS/custom_spider.jar"; then
      if is_dex "$LIBS/custom_spider.jar"; then
        echo "    ❌ 下载到的是 dex，丢弃"
        rm -f "$LIBS/custom_spider.jar"
      else
        ok=1
        break
      fi
    fi
    rm -f "$LIBS/custom_spider.jar.part"
  done

  if [ "$ok" != "1" ]; then
    echo "    ❌ 没有可用的非 dex custom_spider.jar。"
    echo "       1) 到 https://github.com/xiaoyugesvg/catjar/actions 运行"
    echo "          「Build CatVodSpider JVM jar」，然后重跑本脚本；"
    echo "       2) 或按 read.md 从源码构建后放到 $LIBS/custom_spider.jar。"
    exit 1
  fi
  echo "    ✅ 已获取非 dex jar"
fi

# 运行时依赖（okhttp / gson / slf4j / jsoup / smbj …），有就解压到 libs/lib
if [ -z "$(ls -A "$LIBS/lib" 2>/dev/null || true)" ]; then
  echo "==> spider-deps.zip"
  if download "$SPIDER_RELEASE/spider-deps.zip" "$LIBS/spider-deps.zip"; then
    mkdir -p "$LIBS/lib"
    if command -v unzip >/dev/null 2>&1; then
      unzip -oq "$LIBS/spider-deps.zip" -d "$LIBS/lib"
    elif command -v python3 >/dev/null 2>&1; then
      python3 -m zipfile -e "$LIBS/spider-deps.zip" "$LIBS/lib"
    else
      echo "    没有 unzip/python3，跳过解压：$LIBS/spider-deps.zip"
    fi
    rm -f "$LIBS/spider-deps.zip"
    echo "    ✅ 依赖已解压到 $LIBS/lib"
  else
    echo "    未获取到依赖包（可稍后手动补到 libs/lib）"
  fi
else
  echo "==> spider-deps 已存在，跳过"
fi

echo
echo "JAR 就绪："
ls -lh "$LIBS"/*.jar 2>/dev/null || true
