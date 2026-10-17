package android.net;

/** Uri 的最小实现：解析 + 取 query 参数 + host/path */
public final class Uri {
    private final String raw;

    private Uri(String raw) {
        this.raw = raw == null ? "" : raw;
    }

    public static Uri parse(String s) {
        return new Uri(s);
    }

    @Override
    public String toString() {
        return raw;
    }

    public String getHost() {
        int i = raw.indexOf("://");
        if (i < 0) return null;
        String rest = raw.substring(i + 3);
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) end = k;
        }
        String host = rest.substring(0, end);
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    public String getPath() {
        int i = raw.indexOf("://");
        if (i < 0) return raw;
        String rest = raw.substring(i + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) return "";
        String p = rest.substring(slash);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        return p;
    }

    public String getQueryParameter(String key) {
        int q = raw.indexOf('?');
        if (q < 0) return null;
        String query = raw.substring(q + 1);
        int h = query.indexOf('#');
        if (h >= 0) query = query.substring(0, h);
        for (String pair : query.split("&")) {
            int e = pair.indexOf('=');
            String k = e >= 0 ? pair.substring(0, e) : pair;
            String v = e >= 0 ? pair.substring(e + 1) : "";
            if (k.equals(key)) {
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception ignored) {
                    return v;
                }
            }
        }
        return null;
    }
}
