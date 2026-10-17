package com.catvod.gateway;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 网关配置。
 *
 * 用 .properties 而不是 JSON：部署时零依赖、手改方便。
 * 环境变量优先于文件（docker-compose 的 .env 注入 GATEWAY_TOKEN / PUBLIC_BASE_URL）。
 */
final class GatewayConfig {
    String token = "";
    int port = 8080;
    int timeoutSeconds = 25;
    int cacheClassSeconds = 300;
    int cacheListSeconds = 60;
    int cacheDetailSeconds = 600;
    boolean playProxy = true;
    String publicBaseUrl = "";
    final Map<String, SiteConfig> sites = new LinkedHashMap<String, SiteConfig>();

    static final class SiteConfig {
        final String key;
        String className = "";
        String ext = "";

        SiteConfig(String key) {
            this.key = key;
        }
    }

    static GatewayConfig load(Path path) throws IOException {
        GatewayConfig c = new GatewayConfig();
        Properties props = new Properties();
        if (Files.exists(path)) {
            Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            try {
                props.load(r);
            } finally {
                r.close();
            }
        }

        c.token = env("GATEWAY_TOKEN", props.getProperty("token", ""));
        c.port = intOf(env("GATEWAY_PORT", props.getProperty("port", "8080")), 8080);
        c.timeoutSeconds = intOf(props.getProperty("timeout.seconds", "25"), 25);
        c.cacheClassSeconds = intOf(props.getProperty("cache.class.seconds", "300"), 300);
        c.cacheListSeconds = intOf(props.getProperty("cache.list.seconds", "60"), 60);
        c.cacheDetailSeconds = intOf(props.getProperty("cache.detail.seconds", "600"), 600);
        c.playProxy = boolOf(props.getProperty("play.proxy", "true"), true);
        c.publicBaseUrl = trimSlash(env("PUBLIC_BASE_URL", props.getProperty("public.baseUrl", "")));

        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("site.")) continue;
            String rest = name.substring("site.".length());
            int dot = rest.indexOf('.');
            if (dot <= 0) continue;
            String key = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            SiteConfig s = c.sites.get(key);
            if (s == null) {
                s = new SiteConfig(key);
                c.sites.put(key, s);
            }
            if ("className".equals(field)) {
                s.className = props.getProperty(name, "").trim();
            } else if ("ext".equals(field)) {
                s.ext = props.getProperty(name, "");
            }
        }
        return c;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(String.valueOf(s).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolOf(String s, boolean def) {
        if (s == null) return def;
        String v = s.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) return false;
        return def;
    }

    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
