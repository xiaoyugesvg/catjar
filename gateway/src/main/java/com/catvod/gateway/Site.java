package com.catvod.gateway;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个已加载的 spider 实例。
 *
 * 设计要求：
 *  - 每个源**单线程**执行：多数 spider 不是线程安全的，串行最稳；
 *  - 每次调用带超时：坏规则不能把网关线程池拖死；
 *  - 全部走反射：网关不依赖 catvod-api 的编译期类型。
 */
final class Site {
    final GatewayConfig.SiteConfig cfg;
    private final Object spider;
    private final Class<?> cls;
    private final int timeoutSeconds;
    private final ExecutorService exec;

    private final Method mHome1;
    private final Method mHome0;
    private final Method mCategory;
    private final Method mDetail;
    private final Method mSearch3;
    private final Method mSearch2;
    private final Method mPlay;
    private final Method mDestroy;

    Site(GatewayConfig.SiteConfig cfg, Object spider, int timeoutSeconds) {
        this.cfg = cfg;
        this.spider = spider;
        this.cls = spider.getClass();
        this.timeoutSeconds = timeoutSeconds;
        this.exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "spider-" + cfg.key);
                t.setDaemon(true);
                return t;
            }
        });
        this.mHome1 = find("homeContent", 1);
        this.mHome0 = find("homeContent", 0);
        this.mCategory = find("categoryContent", 4);
        this.mDetail = find("detailContent", 1);
        this.mSearch3 = find("searchContent", 3);
        this.mSearch2 = find("searchContent", 2);
        this.mPlay = find("playerContent", 3);
        this.mDestroy = find("destroy", 0);
    }

    /** 按「方法名 + 参数个数」找，避免各版本 SDK 的参数类型差异 */
    private Method find(String name, int argc) {
        Method[] all = cls.getMethods();
        for (Method m : all) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    String home(boolean filter) throws Exception {
        if (mHome1 != null) return call(mHome1, Boolean.valueOf(filter));
        if (mHome0 != null) return call(mHome0);
        throw new NoSuchMethodException("homeContent");
    }

    String category(String tid, String pg, boolean filter, Map<String, String> extend) throws Exception {
        if (mCategory == null) throw new NoSuchMethodException("categoryContent");
        Class<?>[] pt = mCategory.getParameterTypes();
        Object page = (pt[1] == int.class || pt[1] == Integer.class)
                ? (Object) Integer.valueOf(parseInt(pg))
                : (Object) pg;
        return call(mCategory, tid, page, Boolean.valueOf(filter), new HashMap<String, String>(extend));
    }

    String search(String key, boolean quick, String pg) throws Exception {
        if (mSearch3 != null) {
            Class<?>[] pt = mSearch3.getParameterTypes();
            Object page = (pt[2] == int.class || pt[2] == Integer.class)
                    ? (Object) Integer.valueOf(parseInt(pg))
                    : (Object) pg;
            return call(mSearch3, key, Boolean.valueOf(quick), page);
        }
        if (mSearch2 != null) {
            return call(mSearch2, key, Boolean.valueOf(quick));
        }
        throw new NoSuchMethodException("searchContent");
    }

    String detail(String id) throws Exception {
        if (mDetail == null) throw new NoSuchMethodException("detailContent");
        Class<?>[] pt = mDetail.getParameterTypes();
        Object arg = (pt[0] == String.class)
                ? (Object) id
                : Collections.singletonList(id);
        return call(mDetail, arg);
    }

    String play(String flag, String id) throws Exception {
        if (mPlay == null) throw new NoSuchMethodException("playerContent");
        return call(mPlay, flag, id, Collections.<String>emptyList());
    }

    private String call(final Method method, final Object... args) throws Exception {
        // 用 Callable 显式声明：避免 submit(Runnable) 重载把返回值吞掉
        Future<Object> future = exec.submit(new java.util.concurrent.Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return method.invoke(spider, args);
            }
        });
        try {
            Object r = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return r == null ? "" : String.valueOf(r);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new java.io.IOException("spider 超时（" + timeoutSeconds + "s）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 反射调用会把目标异常再包一层 InvocationTargetException，
            // 不拆开的话日志只剩一句 "null"，根本看不出源为什么失败。
            if (cause instanceof java.lang.reflect.InvocationTargetException) {
                Throwable target = ((java.lang.reflect.InvocationTargetException) cause).getTargetException();
                if (target != null) cause = target;
            }
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    void destroy() {
        try {
            if (mDestroy != null) mDestroy.invoke(spider);
        } catch (Throwable ignored) {
            // destroy 失败不影响替换
        }
        exec.shutdownNow();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(String.valueOf(s).trim());
        } catch (Exception e) {
            return 1;
        }
    }
}
