# catvod-api.jar 是 class 文件版本 65（Java 21），所以构建/运行都用 21
ARG JDK_IMAGE=eclipse-temurin:21-jdk-alpine
FROM ${JDK_IMAGE} AS build
WORKDIR /build
COPY gateway/src ./src
RUN find src -name '*.java' > sources.txt \
 && javac --add-modules jdk.httpserver -encoding UTF-8 -d classes @sources.txt

ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${JRE_IMAGE}
RUN adduser -D -H -u 10001 app
WORKDIR /app
COPY --from=build /build/classes /app/classes
COPY gateway/entrypoint.sh /app/entrypoint.sh
# 仓库内镜像的官方 jar（catvod-api 可用；custom_spider 是 dex，入口会做检测）
COPY jars/ /opt/catvod/jars/
RUN chmod 755 /app/entrypoint.sh && mkdir -p /app/libs /app/config
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
