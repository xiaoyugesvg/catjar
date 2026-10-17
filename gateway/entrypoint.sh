#!/bin/sh
# 启动入口：类路径顺序很重要
#   /app/classes 里是我们的 android.* 兼容层 + com.github.catvod.Runtime 覆盖
#   catvod-api.jar 提供 Spider / Net 等 SDK 类
#   libs/lib 放 spider 额外依赖（okhttp/gson…）
# custom_spider.jar 不进类路径：由代码里的 URLClassLoader 单独加载，方便热更新
set -e

API_JAR="${API_JAR:-catvod-api.jar}"
SPIDER_JAR="${SPIDER_JAR:-custom_spider.jar}"

[ -f "/app/libs/$API_JAR" ] || { echo "[gateway] 缺少 /app/libs/$API_JAR"; exit 1; }
[ -f "/app/libs/$SPIDER_JAR" ] || { echo "[gateway] 缺少 /app/libs/$SPIDER_JAR"; exit 1; }

exec java --add-modules jdk.httpserver \
  ${JAVA_OPTS:--Xms96m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError} \
  -cp "/app/classes:/app/libs/$API_JAR:/app/libs/lib/*" \
  com.catvod.gateway.Main
