# catvod-api.jar 是 class 文件版本 65（Java 21），所以构建/运行都用 21
# 两个 ARG 必须放在第一个 FROM 之前，否则第二个 FROM 取不到 JRE_IMAGE
ARG JDK_IMAGE=eclipse-temurin:21-jdk-alpine
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${JDK_IMAGE} AS build
WORKDIR /build
COPY gateway/src ./src
RUN find src -name '*.java' > sources.txt \
 && javac --add-modules jdk.httpserver -encoding UTF-8 -d classes @sources.txt

FROM ${JRE_IMAGE}
# curl/unzip 用于首次启动自动拉取 release 里的非 dex jar 与依赖
RUN apk add --no-cache curl unzip \
 && adduser -D -H -u 10001 app
WORKDIR /app
COPY --from=build /build/classes /app/classes
COPY gateway/entrypoint.sh /app/entrypoint.sh
# 仓库内镜像的官方 jar（catvod-api 可用；custom_spider 是 dex，入口会检测并拒绝）
COPY jars/ /opt/catvod/jars/
# 默认配置模板（/app/runtime 里没有配置文件时自动复制过去）
COPY config/gateway.properties /opt/catvod/default/gateway.properties
RUN chmod 755 /app/entrypoint.sh \
 && mkdir -p /app/libs /app/config /app/runtime \
 && chown -R app:app /app /opt/catvod
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
