package android.content;

import java.io.File;

/**
 * 最小 Context 桩：只实现 spider 常见的几个只读方法。
 * 网关没有 Android 环境，init(Context, ext) 传的是 null；
 * 这个类存在的主要目的是让 jar 里的方法签名与类引用能正常解析。
 */
public class Context {
    public String getPackageName() {
        return "com.catvod.gateway";
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getFilesDir() {
        return new File("/tmp");
    }

    public File getCacheDir() {
        return new File("/tmp");
    }

    public File getExternalFilesDir(String type) {
        return new File("/tmp");
    }

    public Object getSystemService(String name) {
        return null;
    }
}
