package com.bilicache;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;

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
                                applyHooks(lpparam.classLoader, info.versionCode, info.versionName);
                            } catch (Throwable t) {
                                XposedBridge.log("[BiliCache] attach hook error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] handleLoadPackage failed: " + t);
        }
    }

    private static void applyHooks(ClassLoader classLoader, int versionCode, String versionName) {
        String[] cfg = VersionMap.MAP.get(versionCode);
        if (cfg == null) {
            XposedBridge.log("[BiliCache] unsupported versionCode " + versionCode
                    + " (" + versionName + "), supported: " + VersionMap.MAP.size()
                    + " versions (8.61.0-9.6.0)");
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
            XposedBridge.log("[BiliCache] " + versionCode + " (" + versionName
                    + ") getter hooked: " + cfg[0] + "#" + cfg[1] + " -> 0");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] getter hook failed " + cfg[0] + "#" + cfg[1] + ": " + t);
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
            XposedBridge.log("[BiliCache] " + versionCode + " (" + versionName
                    + ") gate hooked: " + cfg[2] + "#" + cfg[3] + " -> false");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] gate hook failed " + cfg[2] + "#" + cfg[3] + ": " + t);
        }

        // 3) 【已弃用】旧版格式写缓存：
        //    该功能实测无效（写入路径/时序与预期不符），且需求上已不需要，
        //    因此注释掉不再启用。相关 hookOldFormatOutput / toOldFormat 代码已移除。
        //    如需恢复，可按 Git 历史回退 v1.1 版本。

        // 4) 在 B 站「设置」页注入 Bili Cache 入口
        hookSettingsEntry(classLoader);
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
                                    XposedBridge.log("[BiliCache] add settings entry failed: " + t);
                                }
                            }
                        });
                XposedBridge.log("[BiliCache] settings entry injected into " + fragment);
            } catch (Throwable t) {
                XposedBridge.log("[BiliCache] settings hook skip " + fragment + ": " + t);
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

        Class<?> listenerClass = Class.forName(
                "androidx.preference.Preference$OnPreferenceClickListener", false, classLoader);
        Object listener = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPreferenceClick".equals(method.getName())) {
                        startBiliCacheSettings(context);
                        return true;
                    }
                    return false;
                });
        XposedHelpers.callMethod(preference, "setOnPreferenceClickListener", listener);

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
            XposedBridge.log("[BiliCache] open settings failed: " + t);
        }
    }
}
