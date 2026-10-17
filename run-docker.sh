#!/usr/bin/env bash
# ============================================================
# 纯 Docker 运行（宿主机不装 Nginx / Java / Certbot）
#
# 用法：
#   ./run-docker.sh 18080
#   ./run-docker.sh 18080 http://1.2.3.4:18080
#
# 前置：本目录 libs/ 下有两个 jar
#   catvod-api.jar / custom_spider.jar
#
# 说明：PUBLIC_BASE_URL 会写进 App 的播放地址，必须是手机能访问到的地址。
#       用域名/公网 IP 时请传第二个参数，或事后改 .env 再重启容器。
# ============================================================
set -euo pipefail

PORT="${1:-18080}"
BASE_ARG="${2:-}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# 没显式给对外地址时，尝试用阿里云元数据/公网 IP 拼一个
detect_ip() {
  curl -s --max-time 2 http://100.100.100.200/latest/meta-data/eipv4 2>/dev/null \
    || curl -s --max-time 3 https://api.ipify.org 2>/dev/null \
    || true
}
if [ -z "$BASE_ARG" ]; then
  IP="$(detect_ip)"
  if [ -n "$IP" ]; then
    BASE_ARG="http://$IP:$PORT"
  else
    BASE_ARG="http://127.0.0.1:$PORT"
  fi
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "未安装 Docker。请先安装：curl -fsSL https://get.docker.com | sh"
  exit 1
fi

missing=0
for j in catvod-api.jar custom_spider.jar; do
  [ -f "libs/$j" ] || { echo "缺少 libs/$j"; missing=1; }
done
if [ "$missing" = "1" ]; then
  echo
  echo "请先放入："
  echo "  libs/catvod-api.jar     ← FongMi/TV 根目录 ./gradlew apiJar"
  echo "  libs/custom_spider.jar  ← FongMi/CatVodSpider 根目录 gradlew spiderJar"
  exit 1
fi

if [ ! -f .env ]; then
  TOKEN="$(openssl rand -hex 24 2>/dev/null || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cat > .env <<EOF
GATEWAY_TOKEN=$TOKEN
PUBLIC_BASE_URL=$BASE_ARG
EOF
  chmod 600 .env 2>/dev/null || true
else
  # 显式传了第二个参数就更新对外地址（否则保留原值）
  if [ -n "${2:-}" ]; then
    sed -i "s#^PUBLIC_BASE_URL=.*#PUBLIC_BASE_URL=$BASE_ARG#" .env
  fi
fi

echo "==> 构建镜像 catvod-gateway:local"
docker build -f gateway/Dockerfile -t catvod-gateway:local .

echo "==> 启动容器（端口 $PORT）"
docker rm -f catvod-gateway >/dev/null 2>&1 || true
docker run -d \
  --name catvod-gateway \
  --restart unless-stopped \
  -p "${PORT}:8080" \
  --env-file .env \
  -e LIBS_DIR=/app/libs \
  -e CONFIG_FILE=/app/config/gateway.properties \
  -e JAVA_OPTS="-Xms96m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError" \
  -v "$DIR/config:/app/config:ro,z" \
  -v "$DIR/libs:/app/libs:ro,z" \
  --memory 700m \
  --memory-swap 1g \
  --cpus 1.5 \
  catvod-gateway:local

echo "==> 等待就绪..."
ok=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
if [ "$ok" != "1" ]; then
  echo "启动失败，日志："
  docker logs --tail=100 catvod-gateway || true
  exit 1
fi

TOKEN_SHOW="$(grep '^GATEWAY_TOKEN=' .env | cut -d= -f2-)"
BASE_SHOW="$(grep '^PUBLIC_BASE_URL=' .env | cut -d= -f2-)"

cat <<EOF

============================================================
Docker 部署完成
  地址   : $BASE_SHOW
  健康   : $BASE_SHOW/health
  Token  : $TOKEN_SHOW

  App 源地址（type=1 JSON 接口）：
    $BASE_SHOW/douban?token=$TOKEN_SHOW

  常用：
    docker logs -f catvod-gateway
    docker restart catvod-gateway
    docker rm -f catvod-gateway     # 删除容器

  ⚠️ 阿里云安全组要放通 $PORT 端口。
  ⚠️ 改 PUBLIC_BASE_URL：编辑 .env 后重新执行本脚本。
============================================================
EOF
