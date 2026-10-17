package com.catvod.gateway;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HttpUtil {
    private HttpUtil() {
    }

    static Map<String, String> query(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** 稳定缓存键：排序 + 去掉 token */
    static String canonical(Map<String, String> q) {
        List<String> keys = new ArrayList<String>(q.keySet());
        Collections.sort(keys);
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            if ("token".equals(k)) continue;
            if (b.length() > 0) b.append('&');
            b.append(k).append('=').append(q.get(k));
        }
        return b.toString();
    }

    static boolean auth(HttpExchange ex, Map<String, String> q, String token) {
        if (token == null || token.isEmpty()) return true;
        String t = q.get("token");
        if (t == null || t.isEmpty()) t = ex.getRequestHeaders().getFirst("X-Token");
        if (t == null || t.isEmpty()) {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            if (a != null && a.toLowerCase().startsWith("bearer ")) {
                t = a.substring("bearer ".length()).trim();
            }
        }
        return token.equals(t);
    }

    static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream out = ex.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    static String error(String msg) {
        return "{\"ok\":false,\"msg\":\"" + PlayRewriter.escape(msg == null ? "" : msg) + "\"}";
    }

    /** 对外基地址：优先配置，否则按反向代理头推断 */
    static String baseUrl(HttpExchange ex, String configured) {
        String s = configured == null ? "" : configured.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.isEmpty()) return s;

        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null || proto.trim().isEmpty()) proto = "http";
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.trim().isEmpty()) host = "127.0.0.1:8080";
        return proto.trim() + "://" + host.trim();
    }

    static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }
}
