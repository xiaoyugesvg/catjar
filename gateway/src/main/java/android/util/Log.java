package android.util;

/** 日志桩：直接打到 stdout/stderr，方便 docker logs 查看 */
public final class Log {
    private Log() {
    }

    public static int v(String tag, String msg) { return println("V", tag, msg, null); }
    public static int d(String tag, String msg) { return println("D", tag, msg, null); }
    public static int i(String tag, String msg) { return println("I", tag, msg, null); }
    public static int w(String tag, String msg) { return println("W", tag, msg, null); }
    public static int e(String tag, String msg) { return println("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return println("E", tag, msg, tr); }

    private static int println(String level, String tag, String msg, Throwable tr) {
        System.out.println("[" + level + "/" + tag + "] " + msg);
        if (tr != null) tr.printStackTrace(System.out);
        return 0;
    }
}
