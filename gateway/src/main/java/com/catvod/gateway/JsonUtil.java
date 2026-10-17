package com.catvod.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 JSON 取值：只用于 playerContent 的返回。
 * 不引入 Gson，避免 1C1G 机器构建时依赖 Maven 私服。
 */
final class JsonUtil {
    private JsonUtil() {
    }

    /** 取顶层/嵌套里第一个匹配的字符串字段（转义已还原） */
    static String string(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? PlayRewriter.unescape(m.group(1)) : "";
    }

    /** 取 `"headers": {...}` 里的键值对 */
    static Map<String, String> headers(String json) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (json == null || json.isEmpty()) return out;
        Matcher h = Pattern.compile("\"headers\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
        if (!h.find()) return out;
        Matcher kv = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(h.group(1));
        while (kv.find()) {
            out.put(PlayRewriter.unescape(kv.group(1)), PlayRewriter.unescape(kv.group(2)));
        }
        return out;
    }
}
