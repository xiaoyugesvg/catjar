package com.catvod.gateway;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 detailContent 返回的 `vod_play_url` 里「非直链的播放值」改写成网关地址。
 *
 * CatVod 的约定：
 *   vod_play_from = 线路名，多条用 $$$
 *   vod_play_url  = 每线 `集名$播放值`，多集用 #，多线用 $$$
 *
 * 播放值有两种：
 *   · 已经是 http(s) 直链 → 原样保留，App 直接播；
 *   · 是 spider 内部 id / 需要 playerContent 解析 → 改写成
 *     {base}/{key}/play?flag=线路&id=播放值
 *     由网关调用 playerContent 后再 302 或代理。
 */
final class PlayRewriter {
    private static final Pattern FROM = Pattern.compile("\"vod_play_from\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern URL = Pattern.compile("\"vod_play_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private PlayRewriter() {
    }

    static String rewrite(String json, String siteKey, String baseUrl, String token) {
        if (json == null || json.isEmpty()) return json;
        Matcher mu = URL.matcher(json);
        if (!mu.find()) return json;

        Matcher mf = FROM.matcher(json);
        String from = mf.find() ? unescape(mf.group(1)) : "";
        String play = unescape(mu.group(1));

        String[] froms = from.split("\\$\\$\\$", -1);
        String[] groups = play.split("\\$\\$\\$", -1);
        StringBuilder out = new StringBuilder(play.length() + 64);

        for (int i = 0; i < groups.length; i++) {
            if (i > 0) out.append("$$$");
            String line = i < froms.length ? froms[i] : "";
            String[] eps = groups[i].split("#", -1);
            for (int j = 0; j < eps.length; j++) {
                if (j > 0) out.append('#');
                String ep = eps[j];
                int d = ep.indexOf('$');
                String name = d >= 0 ? ep.substring(0, d) : "";
                String val = d >= 0 ? ep.substring(d + 1) : ep;

                if (val.isEmpty() || isDirect(val)) {
                    out.append(ep);
                    continue;
                }
                String link = baseUrl + "/" + enc(siteKey) + "/play?flag=" + enc(line) + "&id=" + enc(val);
                // /play 与其它接口一样要鉴权；不带上 token 会 401（表现为"点开就播不了"）
                if (token != null && !token.isEmpty()) link += "&token=" + enc(token);
                out.append(d >= 0 ? name + "$" + link : link);
            }
        }

        String replaced = escape(out.toString());
        return json.substring(0, mu.start(1)) + replaced + json.substring(mu.end(1));
    }

    private static boolean isDirect(String value) {
        String s = value.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** JSON 字符串 → 原始文本 */
    static String unescape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception e) {
                            b.append("\\u");
                        }
                    } else {
                        b.append("\\u");
                    }
                    break;
                default:
                    b.append(n);
            }
        }
        return b.toString();
    }

    /** 原始文本 → JSON 字符串内容（不含两侧引号） */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}
