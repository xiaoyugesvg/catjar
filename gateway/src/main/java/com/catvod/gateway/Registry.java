package com.catvod.gateway;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一组已加载的源。
 *
 * custom_spider.jar 与 catvod-api.jar 的关系：
 *  - catvod-api.jar 放在进程类路径（父加载器），提供 Spider/Net/Runtime 等 SDK 类；
 *  - custom_spider.jar 用独立 URLClassLoader 加载 —— 这样才能热更新（换 jar 后重建 loader）。
 */
final class Registry {
    private final Map<String, Site> sites = new LinkedHashMap<String, Site>();
    private final URLClassLoader loader;

    private Registry(URLClassLoader loader) {
        this.loader = loader;
    }

    static Registry load(GatewayConfig cfg, File jar) throws Exception {
        if (jar == null || !jar.isFile()) {
            throw new java.io.FileNotFoundException("找不到 spider jar: "
                    + (jar == null ? "(null)" : jar.getAbsolutePath()));
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toURI().toURL()},
                Registry.class.getClassLoader());
        Registry registry = new Registry(loader);

        for (GatewayConfig.SiteConfig sc : cfg.sites.values()) {
            if (sc.className.isEmpty()) {
                System.err.println("[gateway] 跳过源 " + sc.key + "：className 为空");
                continue;
            }
            try {
                Class<?> cls = Class.forName(sc.className, true, loader);
                Constructor<?> ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object spider = ctor.newInstance();

                // init(Context, String)：没有 Android 环境，Context 传 null。
                // Context 类从 spider 自己的 loader 取，保证与 jar 里的方法签名是同一个类。
                try {
                    Class<?> ctxCls = Class.forName("android.content.Context", false, cls.getClassLoader());
                    Method init = cls.getMethod("init", ctxCls, String.class);
                    init.setAccessible(true);
                    init.invoke(spider, (Object) null, sc.ext == null ? "" : sc.ext);
                } catch (NoSuchMethodException ignored) {
                    // 少数 spider 没有 init，直接跳过
                } catch (Throwable t) {
                    System.err.println("[gateway] 源 " + sc.key + " init 失败：" + t);
                }

                registry.sites.put(sc.key, new Site(sc, spider, cfg.timeoutSeconds));
                System.out.println("[gateway] 已加载源 " + sc.key + " -> " + sc.className);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // 配置里写了某个类，但当前 jar 没带（不同构建/不同分支很常见）
                // ⇒ 跳过这一条，不要让整个网关起不来
                System.err.println("[gateway] 跳过源 " + sc.key + "：找不到类 "
                        + sc.className + "（" + e + "）");
            } catch (Throwable t) {
                System.err.println("[gateway] 跳过源 " + sc.key + "：加载失败 " + t);
            }
        }
        return registry;
    }

    Site get(String key) {
        return sites.get(key);
    }

    List<String> keys() {
        return new ArrayList<String>(sites.keySet());
    }

    void destroy() {
        for (Site s : sites.values()) {
            try {
                s.destroy();
            } catch (Throwable ignored) {
                // 忽略单个源的销毁失败
            }
        }
        try {
            loader.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
