package com.bilicache;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块：让 B 站 8.61.0 - 9.6.0 启动时直接扫描识别旧版缓存。
 *
 * 注：旧版格式写缓存功能已弃用（实测无效、需求不再需要），相关代码已移除。
 *
 * 原理：
 *  1. DataStorageWrapper 的 gate 方法（旧版 #I/#N，新版 #o/#O）是
 *     “数据库迁移成功次数(c_db_migrate_success_times) >= 3”的判断。
 *     为 true 时应用只读 offlineVideo.db，不再扫描磁盘 —— 手动拷贝的旧缓存因此“看不见”，
 *     而且 cleanInvalidSourceIfNeed 会把这些“磁盘有、数据库无”的目录当成无效文件删除。
 *  2. 把 gate 恒置为 false、并把迁移成功次数读取器恒置为 0 后：
 *       - 每次启动都会走 FileDataStorage.loadLocalVideos 全盘扫描下载目录
 *         （Android/data/tv.danmaku.bili/download，内/外置存储都会扫），
 *         旧格式 entry.json/index.json 也能解析（新解析器 ignoreUnknownKeys=true）；
 *       - 迁移链会把扫描到的缓存（含旧版拷贝来的目录）写进 offlineVideo.db，
 *         之后列表就能正常显示、播放；
 *       - cleanInvalidSourceIfNeed 开头 if (!gate()) return，直接跳过，不会再删磁盘目录。
 *
 * 注意：类名/方法名来自 8.61.0-9.6.0 各版本 dex 静态分析（VersionMap.java，按 versionCode 索引），
 *       均为 R8 混淆后的名字；新增版本时需要重新分析更新 VersionMap。
 */
public class OldCacheBridge implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tv.danmaku.bili";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        try {
            // 等 Application.attach 拿到 Context 后再读取版本号并挂载
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context context = (Context) param.args[0];
                                PackageInfo info = context
                                        .getPackageManager()
                                        .getPackageInfo(context.getPackageName(), 0);
                                applyHooks(lpparam.classLoader, info.versionCode,
                                        info.versionName, context);
                            } catch (Throwable t) {
                                BiliLog.log("attach hook error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            BiliLog.log("handleLoadPackage failed: " + t);
        }
    }

    private static void applyHooks(ClassLoader classLoader, int versionCode,
                                   String versionName, Context context) {
        BiliLog.init(context);
        BiliLog.log("module loaded, versionCode=" + versionCode
                + " versionName=" + versionName + " package=" + context.getPackageName());
        startExportLoop();
        BiliLog.toast("BiliCache 模块已加载 v1.9");

        // 通用配置 Hook：不依赖版本映射，直接拦迁移计数读取（识别旧版缓存主开关的兜底）
        hookUniversalConfig(classLoader);

        String[] cfg = VersionMap.MAP.get(versionCode);
        if (cfg == null) {
            BiliLog.log("unsupported versionCode " + versionCode
                    + " (" + versionName + "), supported: " + VersionMap.MAP.size()
                    + " versions (8.61.0-9.6.0)");
            BiliLog.toast("BiliCache v1.8: 版本未匹配 vcode=" + versionCode
                    + "，已启用通用模式（识别旧版缓存仍可用）");
            return;
        }

        // 1) 迁移成功次数读取器 -> 恒为 0（功能开关：识别旧版缓存）
        try {
            XposedHelpers.findAndHookMethod(
                    cfg[0], classLoader, cfg[1],
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BiliPrefs.recognizeOldCache()) {
                                param.setResult(0);
                            }
                        }
                    });
            BiliLog.log(versionCode + " (" + versionName
                    + ") getter hooked: " + cfg[0] + "#" + cfg[1] + " -> 0");
        } catch (Throwable t) {
            BiliLog.log("getter hook failed " + cfg[0] + "#" + cfg[1] + ": " + t);
        }

        // 2) 迁移完成判断 gate -> 恒为 false（保险，并禁用无效缓存清理；受同一开关控制）
        try {
            XposedHelpers.findAndHookMethod(
                    cfg[2], classLoader, cfg[3],
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BiliPrefs.recognizeOldCache()) {
                                param.setResult(false);
                            }
                        }
                    });
            BiliLog.log(versionCode + " (" + versionName
                    + ") gate hooked: " + cfg[2] + "#" + cfg[3] + " -> false");
        } catch (Throwable t) {
            BiliLog.log("gate hook failed " + cfg[2] + "#" + cfg[3] + ": " + t);
        }

        // 3) 【已弃用】旧版格式写缓存：
        //    该功能实测无效（写入路径/时序与预期不符），且需求上已不需要，
        //    因此注释掉不再启用。相关 hookOldFormatOutput / toOldFormat 代码已移除。
        //    如需恢复，可按 Git 历史回退 v1.1 版本。

        // 4) 在 B 站「设置」页注入 Bili Cache 入口
        hookSettingsEntry(classLoader);

        // 5) 兼容极老 FLV 分段缓存（lua.* / *.blv）：播放时强制按 FLV 解析
        hookFlvPlaybackFix(classLoader);
    }

    /**
     * 通用兜底：直接拦截 kntr.base.config 读取 "c_db_migrate_success_times"，
     * 恒返回 "0"，使“迁移已完成”判断永远不成立。
     * 各版本配置类/方法名/参数顺序都不同（d#a / d#c / d#d / i#a），按签名动态发现。
     */
    private static void hookUniversalConfig(ClassLoader classLoader) {
        for (String name : new String[]{"kntr.base.config.d", "kntr.base.config.i"}) {
            try {
                Class<?> configClass = Class.forName(name, false, classLoader);
                int count = 0;
                for (java.lang.reflect.Method m : configClass.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 3 && m.getReturnType() == Object.class
                            && containsStringParam(pts)) {
                        XposedHelpers.findAndHookMethod(
                                configClass, m.getName(), pts[0], pts[1], pts[2],
                                configHook());
                        count++;
                        BiliLog.log("universal config hook: " + name + "#" + m.getName()
                                + " (recognize fallback)");
                    }
                }
                if (count == 0) {
                    BiliLog.log("universal config hook: no matching method in " + name);
                }
            } catch (Throwable t) {
                BiliLog.log("universal config hook skip " + name + ": " + t);
            }
        }
    }

    private static boolean containsStringParam(Class<?>[] pts) {
        for (Class<?> p : pts) {
            if (p == String.class) {
                return true;
            }
        }
        return false;
    }

    private static XC_MethodHook configHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    for (Object arg : param.args) {
                        if (arg instanceof String && "c_db_migrate_success_times".equals(arg)) {
                            if (BiliPrefs.recognizeOldCache()) {
                                param.setResult("0");
                            }
                            return;
                        }
                    }
                } catch (Throwable t) {
                    BiliLog.log("config hook error: " + t);
                }
            }
        };
    }

    private static volatile boolean sExportLoopStarted;

    private static void startExportLoop() {
        if (sExportLoopStarted) {
            return;
        }
        sExportLoopStarted = true;
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (BiliPrefs.exportLog()) {
                        String path = BiliLog.exportToDownloads();
                        if (path != null) {
                            BiliLog.log("log exported: " + path);
                            BiliLog.toastExportResult("BiliCache 日志已导出: " + path);
                        } else {
                            BiliLog.toastExportResult("BiliCache 日志导出失败：请用 adb pull "
                                    + "/sdcard/Android/data/tv.danmaku.bili/files/BiliCache.log "
                                    + "或从 LSPosed 日志复制 [BiliCache] 行");
                        }
                    }
                } catch (Throwable ignored) {
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, 5000);
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(task, 1000);
    }

    /**
     * 极老缓存（B 站 5.x-6.x 时代）是 FLV 分段格式：
     *   <avid>/<page>/lua.flvXXX.bili2api.<qn>/0.blv
     * 但这类 entry.json 没有 media_type 字段，新版扫描时默认当成 DASH，
     * 播放解析会去 DASH 分支找 video.m4s -> 找不到 -> 黑屏/损坏。
     * 这里在播放解析前把媒体类型强制改成 FLV，让解析器走 .blv 分段分支。
     */
    private static void hookFlvPlaybackFix(ClassLoader classLoader) {
        // 1) 播放解析入口：OfflineResolverKt.f(entity, mediaDir)
        try {
            Class<?> entityClass = XposedHelpers.findClass(
                    "video.biz.offline.base.model.entity.OfflineVideoEntity", classLoader);
            Class<?> resolverClass = XposedHelpers.findClass(
                    "com.bilibili.koffline.resolver.OfflineResolverKt", classLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                File dir = (File) param.args[1];
                                Object entity = param.args[0];
                                String mediaType = describeMediaType(entity);
                                String files = dir == null ? "null" : listFiles(dir);
                                boolean hasBlv = dir != null && containsBlv(dir);
                                BiliLog.log("resolve f() dir=" + dir + " mediaType=" + mediaType
                                        + " hasBlv=" + hasBlv + " files=" + files);
                                if (hasBlv) {
                                    forceFlvMediaType(entity);
                                    BiliLog.log("resolve f() after force: mediaType="
                                            + describeMediaType(entity));
                                }
                            } catch (Throwable t) {
                                BiliLog.log("FLV resolver fix error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                BiliLog.log("resolve result: " + param.getResult()
                                        + " throwable=" + param.getThrowable());
                                Object result = param.getResult();
                                if (result != null) {
                                    BiliLog.log("resolve playIndex: " + describePlayIndex(result));
                                }
                            } catch (Throwable t) {
                                BiliLog.log("resolve after-log error: " + t);
                            }
                        }
                    };
            // 方法名因版本而异（8.75 是 i，9.6 是 f），按签名动态匹配
            boolean hooked = false;
            for (java.lang.reflect.Method m : resolverClass.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && pts[0] == entityClass && pts[1] == File.class) {
                    XposedHelpers.findAndHookMethod(
                            resolverClass, m.getName(), pts[0], pts[1], hook);
                    BiliLog.log("hooked OfflineResolverKt." + m.getName()
                            + "(OfflineVideoEntity, File) (FLV playback fix)");
                    hooked = true;
                    break;
                }
            }
            if (!hooked) {
                BiliLog.log("OfflineResolverKt: no (OfflineVideoEntity, File) method found");
                BiliLog.toast("BiliCache v1.8: FLV 播放修复 Hook 未挂上（找不到解析方法）");
            }
        } catch (Throwable t) {
            BiliLog.log("OfflineResolverKt hook skip: " + t);
            BiliLog.toast("BiliCache v1.8: FLV 播放修复 Hook 失败: " + t);
        }

        // 2) 扫描入口：entry 的 type_tag 以 lua. 开头（FLV 格式特征）时，登记时就直接标 FLV
        try {
            XposedHelpers.findAndHookMethod(
                    "video.biz.offline.base.infra.utils.i", classLoader, "e", File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object entity = param.getResult();
                                if (entity == null) {
                                    return;
                                }
                                BiliLog.log("scan entry -> entity " + entity
                                        + " typeTag lua=" + hasLuaTypeTag(entity));
                                if (hasLuaTypeTag(entity)) {
                                    forceFlvMediaType(entity);
                                }
                            } catch (Throwable t) {
                                BiliLog.log("FLV scan fix error: " + t);
                            }
                        }
                    });
            BiliLog.log("hooked utils.i#e (FLV scan fix)");
        } catch (Throwable t) {
            BiliLog.log("utils.i#e hook skip: " + t);
        }

        // 3) 离线诊断校验入口：validateLocalResource(entity)，同样强制 FLV 并记录结果
        try {
            Class<?> entityClass = XposedHelpers.findClass(
                    "video.biz.offline.base.model.entity.OfflineVideoEntity", classLoader);
            XposedHelpers.findAndHookMethod(
                    "video.biz.offline.base.infra.utils.o", classLoader, "a",
                    entityClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object entity = param.args[0];
                                BiliLog.log("validateLocalResource start: " + entity
                                        + " mediaType=" + describeMediaType(entity)
                                        + " lua=" + hasLuaTypeTag(entity));
                                if (hasLuaTypeTag(entity)) {
                                    forceFlvMediaType(entity);
                                }
                            } catch (Throwable t) {
                                BiliLog.log("validate hook error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                BiliLog.log("validateLocalResource result=" + param.getResult());
                            } catch (Throwable t) {
                                BiliLog.log("validate after error: " + t);
                            }
                        }
                    });
            BiliLog.log("hooked utils.o#a (validation FLV fix)");
        } catch (Throwable t) {
            BiliLog.log("utils.o#a hook skip: " + t);
        }
    }

    private static boolean containsBlv(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f != null && f.getName().endsWith(".blv")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLuaTypeTag(Object entity) {
        try {
            for (Field f : entity.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(entity);
                if (v instanceof String && ((String) v).startsWith("lua.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 通过反射把实体的 MediaType 枚举字段设为 FLV（不依赖混淆后的字段名）。
     */
    private static void forceFlvMediaType(Object entity) {
        try {
            for (Field f : entity.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(entity);
                if (v != null && v.getClass().isEnum()
                        && v.getClass().getName().contains("MediaType")
                        && !"FLV".equals(v.toString())) {
                    Object flv = Enum.valueOf((Class<Enum>) v.getClass(), "FLV");
                    f.set(entity, flv);
                    f.set(entity, flv);
                    BiliLog.log("forced MediaType -> FLV for " + entity);
                    return;
                }
            }
        } catch (Throwable t) {
            BiliLog.log("forceFlvMediaType error: " + t);
        }
    }

    private static String describeMediaType(Object entity) {
        try {
            for (Field f : entity.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(entity);
                if (v != null && v.getClass().isEnum()
                        && v.getClass().getName().contains("MediaType")) {
                    return v.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String listFiles(File dir) {
        StringBuilder sb = new StringBuilder("[");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                sb.append(f.getName()).append("(").append(f.length()).append(") ");
            }
        } else {
            sb.append("null");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 从 MediaResource 里提取 PlayIndex 的关键字段（mNormalMrl / 分段数量 / 画质），
     * 用于确认播放器拿到的本地路径是否正确。
     */
    private static String describePlayIndex(Object mediaResource) {
        try {
            StringBuilder sb = new StringBuilder();
            Object vodIndex = XposedHelpers.getObjectField(mediaResource, "mVodIndex");
            if (vodIndex == null) {
                sb.append("mVodIndex=null ");
            } else {
                Object list = XposedHelpers.getObjectField(vodIndex, "mVodList");
                if (list instanceof java.util.List) {
                    java.util.List<?> items = (java.util.List<?>) list;
                    sb.append("playListSize=").append(items.size()).append(" ");
                    for (Object pi : items) {
                        Object mrl = XposedHelpers.getObjectField(pi, "mNormalMrl");
                        Object seg = XposedHelpers.getObjectField(pi, "mSegmentList");
                        Object q = XposedHelpers.getObjectField(pi, "mQuality");
                        Object from = XposedHelpers.getObjectField(pi, "mFrom");
                        sb.append("mrl=").append(mrl)
                                .append(" segs=").append(seg instanceof java.util.List
                                        ? ((java.util.List<?>) seg).size() : seg)
                                .append(" q=").append(q)
                                .append(" from=").append(from).append(" ");
                    }
                } else {
                    sb.append("mVodList=?").append(" ");
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "describePlayIndex err: " + t;
        }
    }

    /**
     * 在 B 站设置页（PreferenceFragment）里注入 “Bili Cache” 入口。
     * BiliPreferencesFragment 所有版本都有；WideBiliPreferencesFragment 仅 9.0+。
     */
    private static void hookSettingsEntry(ClassLoader classLoader) {
        String[] fragments = {
                "com.bilibili.app.preferences.BiliPreferencesActivity$BiliPreferencesFragment",
                "com.bilibili.app.preferences.fragment.WideBiliPreferencesFragment"
        };
        for (String fragment : fragments) {
            try {
                XposedHelpers.findAndHookMethod(
                        fragment, classLoader, "onCreatePreferences",
                        Bundle.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    addBiliCacheEntry(param.thisObject, classLoader);
                                } catch (Throwable t) {
                                    BiliLog.log("add settings entry failed: " + t);
                                }
                            }
                        });
                BiliLog.log("settings entry injected into " + fragment);
            } catch (Throwable t) {
                BiliLog.log("settings hook skip " + fragment + ": " + t);
                BiliLog.toast("BiliCache v1.8: 设置入口注入失败 " + fragment);
            }
        }
    }

    private static void addBiliCacheEntry(Object fragment, ClassLoader classLoader) throws Throwable {
        Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (context == null || screen == null) {
            return;
        }

        Class<?> preferenceClass = XposedHelpers.findClass(
                "androidx.preference.Preference", classLoader);
        Object preference = XposedHelpers.newInstance(preferenceClass, context);
        XposedHelpers.callMethod(preference, "setTitle", "Bili Cache");
        XposedHelpers.callMethod(preference, "setSummary", "旧版缓存识别 / 旧版格式写缓存");

        // 8.75 等版本里 OnPreferenceClickListener 接口名被混淆，按 setter 参数类型动态找
        boolean listenerSet = false;
        for (java.lang.reflect.Method m : preferenceClass.getMethods()) {
            if ("setOnPreferenceClickListener".equals(m.getName())) {
                Class<?> listenerType = m.getParameterTypes()[0];
                Object listener = Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{listenerType},
                        (proxy, method, args) -> {
                            if ("onPreferenceClick".equals(method.getName())) {
                                startBiliCacheSettings(context);
                                return true;
                            }
                            return false;
                        });
                m.invoke(preference, listener);
                listenerSet = true;
                break;
            }
        }
        if (!listenerSet) {
            BiliLog.log("add settings entry: no setOnPreferenceClickListener found");
        }

        // 尽量插到列表最前面，失败则追加到末尾
        try {
            XposedHelpers.callMethod(screen, "addPreference", 0, preference);
        } catch (Throwable t) {
            XposedHelpers.callMethod(screen, "addPreference", preference);
        }
    }

    private static void startBiliCacheSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.bilicache", "com.bilicache.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            BiliLog.log("open settings failed: " + t);
        }
    }
}
