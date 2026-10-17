# CatVodSpider JAR → HTTP 网关（Docker）

把 FongMi 的 `CatVodSpider` 产出**非 dex 的 JVM jar** 跑在 JVM 上，对外提供 **TVBox / CatYing type=1 JSON 接口**。

适合没有 Android 环境、或希望在 iOS / 任意 HTTP 客户端上复用 jar 源的场景。

> ⚠️ 官方预编译的 `custom_spider.jar` 是 **Android dex 包**，标准 JVM 不能直接加载。详见下面的「关于 custom_spider.jar」。
>
> 本仓库不包含任何账号、Token、密码。部署时由脚本生成随机 `GATEWAY_TOKEN`，只写在本机 `.env` 里（`.gitignore` 已忽略）。

---

## 1. 准备两个 JAR

放到 `libs/`：

| 文件 | 官方地址 / 来源 | 能否直接给 JVM 网关用 |
| --- | --- | --- |
| `libs/catvod-api.jar` | `https://raw.githubusercontent.com/FongMi/CatVodSpider/main/app/libs/catvod-api.jar` | ✅ 可以（class 版本 65，需要 Java 21） |
| `libs/custom_spider.jar` | `https://raw.githubusercontent.com/FongMi/CatVodSpider/main/jar/custom_spider.jar` | ❌ 官方是 dex 包，需要换成非 dex 构建 |

仓库里的 `jars/` 保存了这两个官方文件的镜像（含 SHA256 / MD5），仅作备用与校验；`catvod-api.jar` 可直接复制到 `libs/` 使用。

### 关于 custom_spider.jar（重要）

官方 `jar/custom_spider.jar` 解压后只有：

```
classes.dex
META-INF/MANIFEST.MF
```

它是给 TVBox / FongMi 的 `DexClassLoader` 用的。标准 JVM 不认 `classes.dex`。
用 dex2jar 转换后虽然能生成 `.class`，但 R8 优化过的类会丢失/改错继承关系，运行时会报：

```
VerifyError: Expecting a stackmap frame at branch target ...
AbstractMethodError: Receiver class java.lang.Object ...
```

所以 JVM 网关需要的是**非 dex 的 `.class` jar**。两条可行路径：

1. **从源码编译（推荐）**：用 FongMi/CatVodSpider 源码，取 javac 中间产物打包，不要用 R8 后的 dex。
   大致步骤（需要 Android SDK / Gradle）：
   ```bash
   git clone https://github.com/FongMi/CatVodSpider
   cd CatVodSpider
   ./gradlew :app:assembleRelease
   # 取 R8 之前的 javac 输出，打包成普通 jar
   cd app/build/intermediates/javac/release/classes
   jar cf ../../../../custom_spider-jvm.jar .
   ```
   然后把 `custom_spider-jvm.jar` 改名成 `custom_spider.jar` 放进 `libs/`。
   它依赖的 okhttp / gson / slf4j / jsoup / smbj / quickjs 等 `compileOnly` 库，
   按实际报错把对应 jar 放进 `libs/lib/`。
2. **改用 Python / QuickJS 源**：CatVodSpider 仓库自带
   `examples/python/spider.py`、`examples/javascript/spider.js`，
   不需要 JVM，也就不存在 dex 问题。

> 如果 `custom_spider.jar` 是未压缩的 class jar，入口脚本会自动识别；
> 检测到 `classes.dex` 会直接给出明确错误并退出，不会抛 `VerifyError`。

### 自动构建非 dex jar（GitHub Actions）

仓库自带工作流：

```
.github/workflows/build-spider-jvm.yml
```

使用步骤：

1. 打开 https://github.com/xiaoyugesvg/catjar/actions
2. 选择 **Build CatVodSpider JVM jar** → **Run workflow**
3. 构建完成后，在 Release `spider-jvm` 里会得到：
   - `custom_spider.jar`（非 dex，JVM 可加载）
   - `custom_spider.jar.sha256`
   - `spider-deps.zip`（okhttp / gson / slf4j / jsoup / smbj 等运行时依赖，尽力而为）

工作流会 checkout `FongMi/CatVodSpider` 源码，用 Android SDK 执行
`assembleRelease`，然后取 **R8 之前** 的 `javac` class 输出打包，所以不含
`classes.dex`。

也可以直接在服务器上执行：

```bash
bash scripts/fetch-jars.sh
```

脚本会优先从该 Release 下载非 dex 的 `custom_spider.jar` 和依赖包，
放到 `libs/` 与 `libs/lib/`。

---

## 2. 三种部署方式

### 方式 A：纯 Docker（HTTP，最简单）

宿主机只需要 Docker：

```bash
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# 放好两个 jar 后
chmod +x run-docker.sh
./run-docker.sh 18080
# 也可以显式指定对外地址：
# ./run-docker.sh 18080 http://47.xx.xx.xx:18080
```

脚本会：构建镜像 → 生成 `.env`（随机 token）→ 启动容器 `catvod-gateway` → 健康检查 → 打印源地址。

### 方式 B：docker compose（HTTP）

```bash
cp .env.example .env
# 编辑 .env：填 PUBLIC_BASE_URL（手机能访问到的地址）
# GATEWAY_TOKEN 留占位也行，建议改成随机串

docker compose up -d --build
```

`docker-compose.yml` 做了什么：

| 配置 | 说明 |
| --- | --- |
| `build.context: .` + `gateway/Dockerfile` | 现场用 JDK 21 编译网关源码，不依赖 Maven 私服 |
| `JDK_IMAGE` / `JRE_IMAGE` | 国内拉不到 Docker Hub 时，可在 `.env` 里换成可用镜像仓库 |
| `ports: 127.0.0.1:18080:8080` | 只监听本机，公网入口交给 Nginx / Caddy |
| `volumes: ./config` | `gateway.properties`（源配置），只读挂载 |
| `volumes: ./libs` | 两个 jar 与额外依赖，只读挂载；`:z` 兼容 SELinux |
| `mem_limit: 700m` / `memswap_limit: 1g` | 2C1G 机器保守参数，JVM 堆 320m |
| `cpus: 1.5` | 留出系统与 Nginx 的 CPU |
| `healthcheck` | 每 30 秒请求 `/health` |
| `logging` | 单文件 10MB × 3，防止日志占满磁盘 |

查看状态 / 日志：

```bash
docker compose ps
docker compose logs -f --tail=100
docker compose restart
docker compose down
```

### 方式 C：Compose + Caddy 自动 HTTPS

适合有已备案域名、且 80/443 已放通：

```bash
cp .env.example .env
# .env 里设置：
#   DOMAIN=example.com
#   PUBLIC_BASE_URL=https://example.com
#   GATEWAY_TOKEN=随便一串足够长的随机字符

docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build
```

Caddy 会自动申请 / 续期证书，并反代到 `gateway:8080`；宿主机不需要装 Nginx / Certbot。

### 方式 D：宿主机 Nginx 一键脚本

```bash
sudo bash deploy.sh example.com you@example.com
# 无域名 / 无备案：
sudo bash deploy.sh 1.2.3.4 --port 18080
```

支持 Alibaba Cloud Linux 3 / CentOS / RHEL / Ubuntu / Debian（脚本自动识别 apt/dnf）。

---

## 3. 配置源

编辑 `config/gateway.properties`：

```properties
token=由.env覆盖
public.baseUrl=
port=8080
timeout.seconds=25
cache.class.seconds=300
cache.list.seconds=60
cache.detail.seconds=600
play.proxy=true

# 一条源一个 key
site.douban.className=com.github.catvod.spider.Douban
site.douban.ext=

# site.ali.className=com.github.catvod.spider.Ali
# site.ali.ext={"token":"xxx"}
```

- `className` 必须是 `custom_spider.jar` 里的全限定类名
- `ext` 原样传给 spider 的 `init(Context, String)`
- 改完执行 `docker compose restart`，或 `POST /admin/reload?token=...` 热重载

环境变量优先于文件：

| 环境变量 | 说明 |
| --- | --- |
| `GATEWAY_TOKEN` | 访问令牌（`.env` 注入） |
| `PUBLIC_BASE_URL` | 对外地址，用于改写播放地址 |
| `LIBS_DIR` | jar 目录，默认 `/app/libs` |
| `CONFIG_FILE` | 配置文件路径 |
| `JAVA_OPTS` | JVM 参数 |

---

## 4. HTTP 接口

对齐 CatYing / TVBox 的 **type=1 JSON 接口**：

| 请求 | 对应 spider 方法 |
| --- | --- |
| `GET /{key}?ac=class` | `homeContent(true)` |
| `GET /{key}?ac=videolist&t=<tid>&pg=<page>&<筛选>` | `categoryContent(tid, pg, true, extend)` |
| `GET /{key}?ac=detail&ids=<id>` | `detailContent([id])` |
| `GET /{key}?wd=<keyword>` | `searchContent(keyword, true, 1)` |
| `GET /{key}/play?flag=<线路>&id=<播放值>` | `playerContent(flag, id, [])` |
| `GET /health` | 健康检查 |
| `POST /admin/reload` | 重新加载配置与 jar |

鉴权：`?token=xxx`、`X-Token: xxx` 或 `Authorization: Bearer xxx`。

### 播放地址改写

`detailContent` 返回的 `vod_play_url` 里：

- 已经是 `http(s)` 直链 → 原样返回
- 是 spider 内部 id → 改写成 `{PUBLIC_BASE_URL}/{key}/play?...`，由网关调用 `playerContent` 后 302 或代理

带防盗链头（Referer/UA）的地址，`play.proxy=true` 时由网关流式转发；不需要时设为 `false` 直接 302 省带宽。

---

## 5. 在 App 里使用

在 CatYing 里新增一个 **type=1（JSON 接口）** 源，地址：

```
http://<IP>:18080/douban?token=<GATEWAY_TOKEN>
```

或 HTTPS：

```
https://example.com/douban?token=<GATEWAY_TOKEN>
```

---

## 6. 阿里云 / 1C1G 注意事项

- **安全组**：控制台 → 安全组 → 入方向放通 `18080`（或 80/443）
- **备案**：国内 ECS 用域名走 80/443 需要 ICP 备案；没备案用 `--port 18080` + IP
- **内存**：1GiB 机器已配 1G swap、JVM 堆 320m；不要同时开大量 playback 代理
- **镜像拉取**：国内拉不到 `eclipse-temurin` 时，在 `.env` 里设置：
  ```
  JDK_IMAGE=你的仓库/eclipse-temurin:21-jdk-alpine
  JRE_IMAGE=你的仓库/eclipse-temurin:21-jre-alpine
  ```
- **SELinux**：Alibaba Cloud Linux 默认 Enforcing，compose 已用 `:z` 挂载

---

## 7. 安全

- 不要去掉 token；一旦外泄，任何人都能通过你的服务器抓取站点
- `.env` 已被 `.gitignore` 忽略；不要把 `.env`、PAT、Cookie 提交进仓库
- jar 是第三方代码，会在 JVM 中执行，请只使用可信来源
- 网关只做协议转换，不存储视频内容

---

## 8. 目录结构

```
.
├── docker-compose.yml          # 基础编排（HTTP）
├── docker-compose.https.yml    # 追加 Caddy（自动 HTTPS）
├── Caddyfile                   # Caddy 反代配置
├── .env.example                # 环境变量模板（无真实密钥）
├── deploy.sh                   # 宿主机一键部署（Nginx/Certbot）
├── run-docker.sh               # 纯 Docker 一键运行
├── config/
│   └── gateway.properties      # 源配置
├── jars/                       # 官方 jar 镜像（catvod-api 可用；custom_spider 是 dex）
│   ├── catvod-api.jar
│   ├── custom_spider.jar
│   ├── custom_spider.jar.md5
│   └── README.txt              # 来源、SHA256 与非 dex 说明
├── libs/                       # 运行时 jar（git 忽略）
│   ├── catvod-api.jar
│   ├── custom_spider.jar       # 必须是 JVM class jar，不能是 dex
│   └── lib/                    # 额外依赖
├── nginx/
│   └── gateway.conf.template
└── gateway/
    ├── Dockerfile
    ├── entrypoint.sh           # 检测 dex，缺 jar 时回退 /opt/catvod/jars
    └── src/main/java/…         # 网关源码（零第三方依赖）
```
