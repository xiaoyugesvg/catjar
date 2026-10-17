#!/bin/sh
# 启动入口：类路径顺序很重要
#   /app/classes 里是我们的 android.* 兼容层 + com.github.catvod.Runtime 覆盖
#   catvod-api.jar 提供 Spider / Net 等 SDK 类（class 版本 65 → JDK 21）
#   libs/lib 放 spider 额外依赖（okhttp/gson/slf4j…）
# custom_spider.jar 不进类路径：由代码里的 URLClassLoader 单独加载，方便热更新
set -e

API_JAR="${API_JAR:-catvod-api.jar}"
SPIDER_JAR="${SPIDER_JAR:-custom_spider.jar}"

# 优先用挂载进来的 /app/libs；没有就用镜像内置备份 /opt/catvod/jars
API_PATH="/app/libs/$API_JAR"
[ -f "$API_PATH" ] || API_PATH="/opt/catvod/jars/$API_JAR"

SPIDER_PATH="/app/libs/$SPIDER_JAR"
[ -f "$SPIDER_PATH" ] || SPIDER_PATH="/opt/catvod/jars/$SPIDER_JAR"

[ -f "$API_PATH" ] || { echo "[gateway] 缺少 $API_JAR（/app/libs 或 /opt/catvod/jars）"; exit 1; }
[ -f "$SPIDER_PATH" ] || { echo "[gateway] 缺少 $SPIDER_JAR（/app/libs 或 /opt/catvod/jars）"; exit 1; }

# 官方预编译的 custom_spider.jar 是 Android dex 包（classes.dex），
# 标准 JVM 无法加载。提前给一句明确原因，而不是抛 VerifyError。
if grep -aq 'classes.dex' "$SPIDER_PATH"; then
  echo "[gateway] ❌ $SPIDER_PATH 是 Android dex jar（内含 classes.dex）"
  echo "          JVM 网关需要非 dex 的 .class jar。"
  echo "          可选："
  echo "            1) 用 FongMi/CatVodSpider 源码编译，取 javac 中间产物打包；"
  echo "            2) 或改用 CatVodSpider 的 Python / QuickJS 源（不需要 JVM）。"
  echo "          详见 read.md 的「关于 custom_spider.jar」。"
  exit 1
fi

echo "[gateway] api=$API_PATH spider=$SPIDER_PATH"
export SPIDER_JAR_PATH="$SPIDER_PATH"

exec java --add-modules jdk.httpserver \
  ${JAVA_OPTS:--Xms96m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError} \
  -cp "/app/classes:$API_PATH:/app/libs/lib/*" \
  com.catvod.gateway.Main
