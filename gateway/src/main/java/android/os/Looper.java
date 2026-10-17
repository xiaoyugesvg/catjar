package android.os;

/** Looper 桩：网关没有 Android 消息循环 */
public final class Looper {
    private static final Looper MAIN = new Looper();

    private Looper() {
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static void prepareMainLooper() {
        // no-op
    }

    public static void prepare() {
        // no-op
    }

    public static Looper myLooper() {
        return MAIN;
    }
}
