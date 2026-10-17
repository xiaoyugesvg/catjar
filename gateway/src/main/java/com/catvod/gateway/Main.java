package com.catvod.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CatVodSpider jar → HTTP 网关（零第三方依赖，仅 JDK 17）。
 *
 * 路由（对齐 CatYing type=1 JSON 源）：
 *   GET /{key}?ac=class                                   → homeContent(true)
 *   GET /{key}?ac=videolist&t=<tid>&pg=<page>&<筛选...>   → categoryContent(...)
 *   GET /{key}?ac=detail&ids=<id>                         → detailContent([id])
 *   GET /{key}?wd=<kw>                                    → searchContent(kw, true, 1)
 *   GET /{key}/play?flag=<线路>&id=<播放值>               → playerContent(...)
 *   GET /health                                           → 健康检查
 *   POST /admin/reload                                    → 重新加载配置与 jar
 *
 * 鉴权：token 可放 query（?token=）、X-Token 或 Authorization: Bearer。
 */
public final class Main {
    private static volatile GatewayConfig cfg;
    private static volatile Registry registry;

    private static final ConcurrentHashMap<String, CacheEntry> CACHE =
            new ConcurrentHashMap<String, CacheEntry>();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Paths.get(env("CONFIG_FILE", "/app/config/gateway.properties"));
        cfg = GatewayConfig.load(configPath);

        File libsDir = new File(env("LIBS_DIR", "/app/libs"));
        File spiderJar = new File(libsDir, env("SPIDER_JAR", "custom_spider.jar"));
        registry = Registry.load(cfg, spiderJar);

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port), 0);
        server.createContext("/", Main::handle);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.start();

        System.out.println("[gateway] listening on 0.0.0.0:" + cfg.port
                + " sites=" + registry.keys()
                + " auth=" + (cfg.token.isEmpty() ? "OFF" : "ON"));
    }

    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = HttpUtil.query(ex.getRequestURI().getRawQuery());

            if ("/health".equals(path)) {
                HttpUtil.json(ex, 200, health());
                return;
            }
            if (!HttpUtil.auth(ex, q, cfg.token)) {
                HttpUtil.json(ex, 401, HttpUtil.error("unauthorized"));
                return;
            }
            if ("/admin/reload".equals(path) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
                reload();
                HttpUtil.json(ex, 200, "{\"ok\":true}");
                return;
            }

            String[] seg = path.split("/");
            if (seg.length < 2 || seg[1].isEmpty()) {
                HttpUtil.json(ex, 404, HttpUtil.error("not found"));
                return;
            }
            String key;
            try {
                key = URLDecoder.decode(seg[1], "UTF-8");
            } catch (Exception e) {
                key = seg[1];
            }
            Site site = registry.get(key);
            if (site == null) {
                HttpUtil.json(ex, 404, HttpUtil.error("unknown site: " + key));
                return;
            }
            if (seg.length == 3 && "play".equals(seg[2])) {
                play(ex, site, q);
                return;
            }
            if (seg.length != 2) {
                HttpUtil.json(ex, 404, HttpUtil.error("not found"));
                return;
            }
            api(ex, site, q);
        } catch (Throwable t) {
            System.err.println("[gateway] 请求失败: " + t);
            try {
                HttpUtil.json(ex, 502, HttpUtil.error(String.valueOf(t.getMessage())));
            } catch (Exception ignored) {
                // 响应可能已经开始写出
            }
        }
    }

    // ============================================================
    // 列表 / 详情 / 搜索
    // ============================================================

    private static void api(HttpExchange ex, final Site site, Map<String, String> q) throws Exception {
        final String ac = q.get("ac");
        final String wd = q.get("wd");
        final String ids = HttpUtil.firstNonEmpty(q.get("ids"), q.get("id"));
        final String pg = HttpUtil.firstNonEmpty(q.get("pg"), "1");
        final String cacheKey = site.cfg.key + "|" + HttpUtil.canonical(q);

        String kind;
        String raw;

        if (wd != null && !wd.isEmpty()) {
            kind = "search";
            raw = cached(cacheKey, cfg.cacheListSeconds, new Loader() {
                @Override
                public String load() throws Exception {
                    return site.search(wd, true, pg);
                }
            });
        } else if ("videolist".equals(ac)) {
            kind = "list";
            final Map<String, String> extend = new LinkedHashMap<String, String>(q);
            extend.remove("ac");
            extend.remove("t");
            extend.remove("pg");
            extend.remove("token");
            raw = cached(cacheKey, cfg.cacheListSeconds, new Loader() {
                @Override
                public String load() throws Exception {
                    return site.category(HttpUtil.firstNonEmpty(q.get("t"), ""), pg, true, extend);
                }
            });
        } else if ("detail".equals(ac) && !ids.isEmpty()) {
            kind = "detail";
            raw = cached(cacheKey, cfg.cacheDetailSeconds, new Loader() {
                @Override
                public String load() throws Exception {
                    return site.detail(ids);
                }
            });
        } else {
            // ac=class、空 ac、以及 TVBox 的 ac=detail&filter=true（无 ids）都走首页
            kind = "class";
            raw = cached(cacheKey, cfg.cacheClassSeconds, new Loader() {
                @Override
                public String load() throws Exception {
                    return site.home(true);
                }
            });
        }

        if ("detail".equals(kind)) {
            raw = PlayRewriter.rewrite(raw, site.cfg.key, HttpUtil.baseUrl(ex, cfg.publicBaseUrl));
        }
        if (raw == null || raw.trim().isEmpty()) {
            raw = "{\"list\":[]}";
        }
        HttpUtil.json(ex, 200, raw);
    }

    // ============================================================
    // 播放
    // ============================================================

    private static void play(HttpExchange ex, Site site, Map<String, String> q) throws Exception {
        String flag = HttpUtil.firstNonEmpty(q.get("flag"), "");
        String id = HttpUtil.firstNonEmpty(q.get("id"), "");
        if (id.isEmpty()) {
            HttpUtil.json(ex, 400, HttpUtil.error("missing id"));
            return;
        }

        String body = site.play(flag, id);
        String url = JsonUtil.string(body, "url");
        if (url.isEmpty()) url = JsonUtil.string(body, "playUrl");
        if (url.isEmpty()) {
            HttpUtil.json(ex, 502, HttpUtil.error("播放地址为空: " + body));
            return;
        }

        Map<String, String> headers = JsonUtil.headers(body);
        if (cfg.playProxy && !headers.isEmpty()) {
            proxy(ex, url, headers);
            return;
        }

        ex.getResponseHeaders().set("Location", url);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    /** 带防盗链头的播放：由网关转发（会消耗服务器带宽） */
    private static void proxy(HttpExchange ex, String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            try {
                builder.header(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // 非法头（含中文/换行）直接跳过
            }
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && !range.isEmpty()) {
            builder.header("Range", range);
        }

        HttpResponse<InputStream> resp = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        ex.getResponseHeaders().set("Content-Type",
                resp.headers().firstValue("Content-Type").orElse("application/octet-stream"));
        String acceptRanges = resp.headers().firstValue("Accept-Ranges").orElse(null);
        if (acceptRanges != null) {
            ex.getResponseHeaders().set("Accept-Ranges", acceptRanges);
        }

        ex.sendResponseHeaders(resp.statusCode(), 0);
        InputStream in = resp.body();
        OutputStream out = ex.getResponseBody();
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // 客户端拖动/断开
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    // ============================================================
    // 缓存 / 健康检查 / 热重载
    // ============================================================

    private interface Loader {
        String load() throws Exception;
    }

    private static final class CacheEntry {
        final String body;
        final long expireAt;

        CacheEntry(String body, long expireAt) {
            this.body = body;
            this.expireAt = expireAt;
        }
    }

    private static String cached(String key, int ttlSeconds, Loader loader) throws Exception {
        if (ttlSeconds <= 0) return loader.load();
        long now = System.currentTimeMillis();
        CacheEntry entry = CACHE.get(key);
        if (entry != null && entry.expireAt > now) return entry.body;

        String body = loader.load();
        if (body != null && !body.isEmpty()) {
            if (CACHE.size() > 2000) CACHE.clear();
            CACHE.put(key, new CacheEntry(body, now + ttlSeconds * 1000L));
        }
        return body;
    }

    private static String health() {
        StringBuilder b = new StringBuilder("{\"ok\":true,\"sites\":[");
        List<String> keys = registry.keys();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(PlayRewriter.escape(keys.get(i))).append('"');
        }
        b.append("],\"jar\":\"")
                .append(PlayRewriter.escape(env("SPIDER_JAR", "custom_spider.jar")))
                .append("\",\"auth\":")
                .append(cfg.token.isEmpty() ? "false" : "true")
                .append('}');
        return b.toString();
    }

    private static synchronized void reload() throws Exception {
        Path configPath = Paths.get(env("CONFIG_FILE", "/app/config/gateway.properties"));
        GatewayConfig nextCfg = GatewayConfig.load(configPath);
        File libsDir = new File(env("LIBS_DIR", "/app/libs"));
        File spiderJar = new File(libsDir, env("SPIDER_JAR", "custom_spider.jar"));

        Registry nextRegistry = Registry.load(nextCfg, spiderJar);
        Registry oldRegistry = registry;
        cfg = nextCfg;
        registry = nextRegistry;
        CACHE.clear();
        if (oldRegistry != null) oldRegistry.destroy();
        System.out.println("[gateway] reloaded, sites=" + nextRegistry.keys());
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }
}
