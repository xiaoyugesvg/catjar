package android.webkit;

/**
 * CookieManager 桩：网关没有 WebView。
 * 依赖 cookie:true 的 spider 需要改成普通 headers。
 */
public class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();

    private CookieManager() {
    }

    public static CookieManager getInstance() {
        return INSTANCE;
    }

    public String getCookie(String url) {
        return "";
    }

    public void setCookie(String url, String value) {
        // no-op
    }

    public void flush() {
        // no-op
    }

    public void removeAllCookies(ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
}
