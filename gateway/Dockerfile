# 纯 JDK 构建：不依赖 Maven 私服，1C1G 机器也能快速构建
ARG JDK_IMAGE=eclipse-temurin:17-jdk-alpine
FROM ${JDK_IMAGE} AS build
WORKDIR /build
COPY gateway/src ./src
RUN find src -name '*.java' > sources.txt \
 && javac --add-modules jdk.httpserver -encoding UTF-8 -d classes @sources.txt

ARG JRE_IMAGE=eclipse-temurin:17-jre-alpine
FROM ${JRE_IMAGE}
RUN adduser -D -H -u 10001 app
WORKDIR /app
COPY --from=build /build/classes /app/classes
COPY gateway/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh && mkdir -p /app/libs /app/config
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
