#!/bin/sh
# 启动入口：
#   · 优先使用 /app/libs（老部署方式，jar 由宿主机提供）
#   · 没有就用 /app/runtime（BT / 镜像方式，可自动下载并持久化）
#   · 再没有就用镜像内置的 /opt/catvod/jars（只含 catvod-api；custom_spider 是 dex，会被拒绝）
set -e

RUNTIME="${LIBS_DIR:-/app/runtime}"
CONFIG="${CONFIG_FILE:-$RUNTIME/gateway.properties}"
API_JAR="${API_JAR:-catvod-api.jar}"
SPIDER_JAR="${SPIDER_JAR:-custom_spider.jar}"
BASE="${JAR_RELEASE_BASE:-https://github.com/xiaoyugesvg/catjar/releases/download/spider-jvm}"

# 只读挂载时 mkdir 会失败，忽略即可（后续写入失败会给出明确提示）
mkdir -p "$RUNTIME" 2>/dev/null || true
mkdir -p "$RUNTIME/lib" 2>/dev/null || true
# bind mount 默认是 root 所有；先修正权限，再降权运行 Java
chown -R app:app "$RUNTIME" 2>/dev/null || true

# ---------- catvod-api.jar ----------
API_PATH="$RUNTIME/$API_JAR"
if [ ! -f "$API_PATH" ] && [ -f "/app/libs/$API_JAR" ] && [ "$RUNTIME" != "/app/libs" ]; then
  cp "/app/libs/$API_JAR" "$API_PATH" 2>/dev/null || true
fi
if [ ! -f "$API_PATH" ] && [ -f "/opt/catvod/jars/$API_JAR" ]; then
  cp "/opt/catvod/jars/$API_JAR" "$API_PATH" 2>/dev/null || true
fi
if [ ! -f "$API_PATH" ]; then
  echo "[gateway] 下载 $API_JAR ..."
  curl -fL --retry 3 --connect-timeout 15 -o "$API_PATH.part" \
    "https://raw.githubusercontent.com/FongMi/CatVodSpider/main/app/libs/$API_JAR" || true
  [ -s "$API_PATH.part" ] && mv "$API_PATH.part" "$API_PATH" || true
fi
[ -f "$API_PATH" ] || { echo "[gateway] ❌ 缺少 $API_JAR，请放到 $RUNTIME/"; exit 1; }

# ---------- 配置文件 ----------
if [ ! -f "$CONFIG" ]; then
  if cp /opt/catvod/default/gateway.properties "$CONFIG" 2>/dev/null; then
    echo "[gateway] 已生成默认配置：$CONFIG"
    echo "[gateway] 请编辑 site.<key>.className / ext 后重启：docker restart catvod-gateway"
  fi
fi

# ---------- custom_spider.jar（必须非 dex） ----------
SPIDER_PATH="$RUNTIME/$SPIDER_JAR"
if [ -f "$SPIDER_PATH" ] && grep -q 'classes.dex' "$SPIDER_PATH"; then
  echo "[gateway] 现有 $SPIDER_PATH 是 dex，删除后重新获取"
  rm -f "$SPIDER_PATH" 2>/dev/null || true
fi
if [ ! -f "$SPIDER_PATH" ] && [ -f "/app/libs/$SPIDER_JAR" ] && [ "$RUNTIME" != "/app/libs" ]; then
  if ! grep -q 'classes.dex' "/app/libs/$SPIDER_JAR"; then
    cp "/app/libs/$SPIDER_JAR" "$SPIDER_PATH" 2>/dev/null || true
  fi
fi
if [ ! -f "$SPIDER_PATH" ]; then
  echo "[gateway] 下载非 dex 的 $SPIDER_JAR ..."
  curl -fL --retry 3 --connect-timeout 15 -o "$SPIDER_PATH.part" "$BASE/$SPIDER_JAR" || true
  [ -s "$SPIDER_PATH.part" ] && mv "$SPIDER_PATH.part" "$SPIDER_PATH" || true
fi
if [ ! -f "$SPIDER_PATH" ]; then
  echo "[gateway] ❌ 无法获取非 dex 的 $SPIDER_JAR。"
  echo "          1) 运行 scripts/fetch-jars.sh；"
  echo "          2) 或从 $BASE/$SPIDER_JAR 手动下载到 $RUNTIME/；"
  echo "          3) 或到 https://github.com/xiaoyugesvg/catjar/actions 运行构建工作流。"
  exit 1
fi
if grep -q 'classes.dex' "$SPIDER_PATH"; then
  echo "[gateway] ❌ $SPIDER_PATH 是 Android dex jar，JVM 网关无法加载。"
  echo "          请用 Release spider-jvm 里的非 dex custom_spider.jar。"
  exit 1
fi

# ---------- 运行时依赖 ----------
if [ -z "$(ls -A "$RUNTIME/lib" 2>/dev/null || true)" ]; then
  echo "[gateway] 下载运行时依赖 ..."
  if curl -fL --retry 3 --connect-timeout 15 -o "$RUNTIME/deps.zip" "$BASE/spider-deps.zip"; then
    unzip -oq "$RUNTIME/deps.zip" -d "$RUNTIME/lib" 2>/dev/null || true
    rm -f "$RUNTIME/deps.zip"
  fi
fi

echo "[gateway] runtime=$RUNTIME api=$API_PATH spider=$SPIDER_PATH config=$CONFIG"
export SPIDER_JAR_PATH="$SPIDER_PATH"
export CONFIG_FILE="$CONFIG"

exec su-exec app:app java --add-modules jdk.httpserver \
  ${JAVA_OPTS:--Xms96m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError} \
  -cp "/app/classes:$API_PATH:$RUNTIME/lib/*" \
  com.catvod.gateway.Main
