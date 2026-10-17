package com.github.catvod;

import android.app.Activity;
import android.content.Context;

/**
 * 覆盖 catvod-api 里的 Runtime（类路径里 /app/classes 优先）。
 *
 * 官方实现依赖 Android（Activity/Toast）。网关没有 UI：
 *  - context()/activity() 返回 null；
 *  - toast() 打到日志。
 *
 * 注意：有 Context 才能工作的能力（WebView cookie、FileProvider、
 * 读写 App 私有目录）在网关上不可用。
 */
public final class Runtime {
    private Runtime() {
    }

    public static Context context() {
        return null;
    }

    public static Context getContext() {
        return null;
    }

    public static Activity activity() {
        return null;
    }

    public static Activity getActivity() {
        return null;
    }

    public static void toast(String msg) {
        System.out.println("[toast] " + msg);
    }
}
