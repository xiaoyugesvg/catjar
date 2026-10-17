package android.os;

/** Handler 桩：post 立即同步执行（spider 一般只用来切主线程弹提示） */
public class Handler {
    public Handler() {
    }

    public Handler(Looper looper) {
    }

    public boolean post(Runnable r) {
        if (r != null) r.run();
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r != null) r.run();
        return true;
    }

    public void removeCallbacks(Runnable r) {
        // no-op
    }

    public void removeCallbacksAndMessages(Object token) {
        // no-op
    }
}
