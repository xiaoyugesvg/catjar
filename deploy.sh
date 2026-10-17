#!/usr/bin/env bash
# ============================================================
# CatVodSpider jar → HTTP 网关：一键部署
#
# 支持系统：
#   · Alibaba Cloud Linux 3.x（dnf）
#   · CentOS / RHEL / Rocky / AlmaLinux 8+（dnf）
#   · Ubuntu 20.04+ / Debian 11+（apt）
#
# 用法：
#   sudo bash deploy.sh example.com you@example.com
#   sudo bash deploy.sh 1.2.3.4                      # 无域名，只开 HTTP:80
#   sudo bash deploy.sh 1.2.3.4 --port 18080         # 无备案/无域名：换端口
#   sudo bash deploy.sh example.com you@example.com --no-tls
#
# 部署前把两个 jar 放到本目录 libs/：
#   libs/catvod-api.jar     ← FongMi/TV 根目录 ./gradlew apiJar
#   libs/custom_spider.jar  ← FongMi/CatVodSpider 根目录 gradlew spiderJar
# ============================================================
set -euo pipefail

DOMAIN="${1:-}"
EMAIL=""
NO_TLS=0
PORT=80

if [ $# -gt 0 ]; then shift; fi
while [ $# -gt 0 ]; do
  case "$1" in
    --no-tls) NO_TLS=1 ;;
    --port) PORT="${2:-80}"; shift ;;
    --port=*) PORT="${1#*=}" ;;
    *)
      if [ -z "$EMAIL" ] && echo "$1" | grep -q '@'; then
        EMAIL="$1"
      else
        echo "未知参数: $1"
        exit 1
      fi
      ;;
  esac
  shift
done

if [ -z "$DOMAIN" ]; then
  echo "用法: sudo bash deploy.sh <域名或IP> [邮箱] [--no-tls] [--port 18080]"
  exit 1
fi
if [ "$(id -u)" != "0" ]; then
  echo "请用 root 运行（sudo bash deploy.sh ...）"
  exit 1
fi

APP_DIR=/opt/catvod-gateway
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 发行版识别 ----------
. /etc/os-release
OS_ID="${ID:-unknown}"
OS_NAME="${PRETTY_NAME:-$OS_ID}"
case "$OS_ID" in
  ubuntu|debian) FAMILY=deb ;;
  alinux|centos|rhel|rocky|almalinux|fedora|openEuler) FAMILY=rpm ;;
  *) echo "未适配的系统: $OS_ID ($OS_NAME)"; exit 1 ;;
esac
echo "系统: $OS_NAME（$FAMILY 系）"

IS_IP=0
echo "$DOMAIN" | grep -Eq '^[0-9.]+$' && IS_IP=1

# ---------- 1. 基础软件 ----------
echo "==> 1/8 安装基础软件"
if [ "$FAMILY" = "deb" ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y curl ca-certificates openssl gnupg nginx chrony ufw
  if [ "$NO_TLS" = "0" ] && [ "$IS_IP" = "0" ]; then
    apt-get install -y certbot python3-certbot-nginx
  fi
else
  dnf install -y curl ca-certificates openssl nginx chrony firewalld policycoreutils-python-utils
  systemctl enable --now chronyd >/dev/null 2>&1 || true
fi

# ---------- 2. Docker ----------
echo "==> 2/8 安装 Docker"
if ! command -v docker >/dev/null 2>&1; then
  if [ "$FAMILY" = "rpm" ]; then
    # Alibaba Cloud Linux 3 走阿里云 docker-ce 镜像；$releasever 会解析成 3，需固定为 8
    dnf install -y dnf-plugins-core || true
    dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo || true
    [ -f /etc/yum.repos.d/docker-ce.repo ] && sed -i 's/\$releasever/8/g' /etc/yum.repos.d/docker-ce.repo || true
    dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin \
      || curl -fsSL https://get.docker.com | sh
  else
    curl -fsSL https://get.docker.com | sh
  fi
fi
systemctl enable --now docker

if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "缺少 docker compose，请先安装 docker-compose-plugin"
  exit 1
fi

# 国内机器拉不到 Docker Hub 时，可在 /etc/docker/daemon.json 配镜像加速；
# 这里只在文件不存在时给一个空模板，避免覆盖用户已有配置。
if [ ! -f /etc/docker/daemon.json ]; then
  mkdir -p /etc/docker
  cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
JSON
  systemctl restart docker
fi

# ---------- 3. 1G 内存调优 ----------
echo "==> 3/8 内存调优（swap + swappiness）"
if ! swapon --show 2>/dev/null | grep -q .; then
  if ! fallocate -l 1G /swapfile 2>/dev/null; then
    dd if=/dev/zero of=/swapfile bs=1M count=1024
  fi
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "已创建 1G swap"
fi
sysctl -qw vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >> /etc/sysctl.conf

# ---------- 4. 拷贝文件 ----------
echo "==> 4/8 拷贝部署文件到 $APP_DIR"
mkdir -p "$APP_DIR"
cp -r "$SRC_DIR/gateway" "$APP_DIR/"
cp -r "$SRC_DIR/config" "$APP_DIR/"
cp -r "$SRC_DIR/nginx" "$APP_DIR/"
cp "$SRC_DIR/docker-compose.yml" "$APP_DIR/"
mkdir -p "$APP_DIR/libs/lib"
if [ -d "$SRC_DIR/libs" ]; then
  cp -n "$SRC_DIR/libs/"*.jar "$APP_DIR/libs/" 2>/dev/null || true
fi

# ---------- 5. token / 对外地址 ----------
echo "==> 5/8 生成 token 与对外地址"
if [ "$NO_TLS" = "1" ] || [ "$IS_IP" = "1" ] || [ "$PORT" != "80" ]; then
  if [ "$PORT" = "80" ] || [ "$PORT" = "443" ]; then
    BASE="http://$DOMAIN"
  else
    BASE="http://$DOMAIN:$PORT"
  fi
else
  BASE="https://$DOMAIN"
fi

if [ ! -f "$APP_DIR/.env" ]; then
  TOKEN="$(openssl rand -hex 24)"
  cat > "$APP_DIR/.env" <<EOF
GATEWAY_TOKEN=$TOKEN
PUBLIC_BASE_URL=$BASE
EOF
  chmod 600 "$APP_DIR/.env"
fi
# shellcheck disable=SC1090
set -a; . "$APP_DIR/.env"; set +a

if [ ! -f "$APP_DIR/config/gateway.properties" ]; then
  cp "$SRC_DIR/config/gateway.properties" "$APP_DIR/config/gateway.properties"
fi

# ---------- 6. 检查 jar ----------
echo "==> 6/8 检查 jar"
missing=0
for j in catvod-api.jar custom_spider.jar; do
  [ -f "$APP_DIR/libs/$j" ] || { echo "  缺少: $APP_DIR/libs/$j"; missing=1; }
done
if [ "$missing" = "1" ]; then
  cat <<EOF

请先上传两个 jar 到 $APP_DIR/libs/ ，再重新执行本脚本：
  scp catvod-api.jar    root@$DOMAIN:$APP_DIR/libs/
  scp custom_spider.jar root@$DOMAIN:$APP_DIR/libs/

  catvod-api.jar    ← FongMi/TV 根目录执行 ./gradlew apiJar
  custom_spider.jar ← FongMi/CatVodSpider 根目录执行 gradlew spiderJar

如果 spider jar 还缺 okhttp/gson 等依赖，放到 $APP_DIR/libs/lib/ 下。

EOF
  exit 1
fi

# ---------- 7. 构建启动 ----------
echo "==> 7/8 构建并启动容器（首次会拉取 JDK 镜像，耐心等）"
cd "$APP_DIR"
$DC up -d --build

echo "  等待网关就绪..."
ok=0
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:18080/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
if [ "$ok" != "1" ]; then
  echo "网关启动失败，最近日志："
  $DC logs --tail=100 || true
  echo
  echo "常见原因："
  echo "  1) 拉不到 eclipse-temurin 镜像 → 配置 /etc/docker/daemon.json 镜像加速，"
  echo "     或在 .env 里设置 JDK_IMAGE / JRE_IMAGE 指向可用的镜像仓库"
  echo "  2) jar 与 catvod-api 版本不匹配 → 用同一次 FongMi/TV 构建的 apiJar"
  exit 1
fi
curl -fsS "http://127.0.0.1:18080/health" && echo

# ---------- 8. Nginx / 防火墙 / TLS ----------
echo "==> 8/8 配置 Nginx 与防火墙"

if [ "$FAMILY" = "deb" ]; then
  sed "s/__DOMAIN__/$DOMAIN/g; s/__PORT__/$PORT/g" "$APP_DIR/nginx/gateway.conf.template" \
    > /etc/nginx/sites-available/catvod-gateway
  ln -sf /etc/nginx/sites-available/catvod-gateway /etc/nginx/sites-enabled/catvod-gateway
  rm -f /etc/nginx/sites-enabled/default
else
  # RHEL 系没有 sites-available；写到 conf.d
  sed "s/__DOMAIN__/$DOMAIN/g; s/__PORT__/$PORT/g" "$APP_DIR/nginx/gateway.conf.template" \
    > /etc/nginx/conf.d/catvod-gateway.conf
  # 系统自带 server 块占着 80 default_server，把它挪到 8081，避免抢走请求
  if [ "$PORT" = "80" ]; then
    sed -i 's/listen\s*80 default_server;/listen 8081 default_server;/g; s/listen\s*\[::\]:80 default_server;/listen [::]:8081 default_server;/g' /etc/nginx/nginx.conf
  fi
  # SELinux：让 nginx 能反代本机端口（失败不影响）
  setsebool -P httpd_can_network_connect 1 >/dev/null 2>&1 || true
fi

nginx -t
systemctl enable --now nginx
systemctl reload nginx

if [ "$FAMILY" = "deb" ]; then
  ufw allow 22/tcp >/dev/null 2>&1 || true
  ufw allow "$PORT"/tcp >/dev/null 2>&1 || true
  [ "$PORT" != "80" ] && ufw allow 80/tcp >/dev/null 2>&1 || true
  [ "$PORT" != "443" ] && ufw allow 443/tcp >/dev/null 2>&1 || true
  ufw --force enable >/dev/null 2>&1 || true
else
  systemctl enable --now firewalld >/dev/null 2>&1 || true
  firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null 2>&1 || true
  firewall-cmd --permanent --add-service=http >/dev/null 2>&1 || true
  firewall-cmd --permanent --add-service=https >/dev/null 2>&1 || true
  firewall-cmd --reload >/dev/null 2>&1 || true
fi

if [ "$NO_TLS" = "0" ] && [ "$IS_IP" = "0" ] && [ "$PORT" = "80" ] && [ -n "$EMAIL" ]; then
  if [ "$FAMILY" = "rpm" ] && ! command -v certbot >/dev/null 2>&1; then
    dnf install -y https://mirrors.aliyun.com/epel/epel-release-latest-8.noarch.rpm >/dev/null 2>&1 || true
    dnf install -y certbot python3-certbot-nginx >/dev/null 2>&1 || true
  fi
  if command -v certbot >/dev/null 2>&1; then
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect || {
      echo "certbot 失败：确认域名已解析到本机、安全组已放通 80/443 后手动执行："
      echo "  certbot --nginx -d $DOMAIN"
    }
  else
    echo "未能自动安装 certbot。可改用 acme.sh："
    echo "  curl https://get.acme.sh | sh -s email=$EMAIL"
    echo "  ~/.acme.sh/acme.sh --issue -d $DOMAIN --nginx"
  fi
fi

TOKEN_SHOW="$(grep '^GATEWAY_TOKEN=' "$APP_DIR/.env" | cut -d= -f2-)"
BASE_SHOW="$(grep '^PUBLIC_BASE_URL=' "$APP_DIR/.env" | cut -d= -f2-)"

cat <<EOF

============================================================
部署完成
  网关地址 : $BASE_SHOW
  健康检查 : $BASE_SHOW/health
  Token    : $TOKEN_SHOW

  App 里填的源地址（type=1 JSON 接口）：
    $BASE_SHOW/douban?token=$TOKEN_SHOW
  例：
    $BASE_SHOW/douban?ac=class&token=$TOKEN_SHOW
    $BASE_SHOW/douban?ac=videolist&t=1&pg=1&token=$TOKEN_SHOW
    $BASE_SHOW/douban?ac=detail&ids=123&token=$TOKEN_SHOW
    $BASE_SHOW/douban?wd=关键词&token=$TOKEN_SHOW

  源配置：$APP_DIR/config/gateway.properties
  改完重启：cd $APP_DIR && $DC restart

  ⚠️ 阿里云安全组要放通 $PORT 端口（控制台 → 安全组 → 入方向）。
  ⚠️ 国内 ECS 用域名走 80/443 需要 ICP 备案；没备案请用 --port 18080 + IP。
  ⚠️ token 会出现在源地址里，请勿外泄。
============================================================
EOF
