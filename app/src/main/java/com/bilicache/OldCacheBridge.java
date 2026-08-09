package com.bilicache;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import java.io.File;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * LSPosed 模块：让 B 站 8.61.0 - 9.6.0 启动时直接扫描识别旧版缓存。
 *
 * 附加功能：让新版 App 写入缓存时直接使用旧版格式
 *  - entry.json: season_id -> seasion_id，去掉 ep:null
 *  - index.json: bilidrmUri -> bilidrm_uri，去掉 widevinePssh，补 dash_drm_type/audio_stream_type
 *  这样新版下载/更新缓存后，输出与旧版(legacy)缓存格式一致，旧版可直接读取。
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

        // 3) 旧版格式输出：拦截 entry.json / index.json 的写入（受功能开关控制）
        hookOldFormatOutput(classLoader);

        // 4) 在 B 站「设置」页注入 Bili Cache 入口
        hookSettingsEntry(classLoader);
    }

    /**
     * 拦截缓存元数据写入，把新版格式转成旧版格式。
     * 只处理包含新版特征字段的 JSON，其它写入不受影响。
     */
    private static void hookOldFormatOutput(ClassLoader classLoader) {
        // 新版离线模块用 kotlinx.io 写入：Utf8Kt.writeString$default(Sink, String, int, int, int, Object)
        try {
            Class<?> sink = Class.forName("kotlinx.io.Sink", false, classLoader);
            XposedHelpers.findAndHookMethod(
                    "kotlinx.io.Utf8Kt", classLoader, "writeString$default",
                    sink, String.class, int.class, int.class, int.class, Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BiliPrefs.oldFormatOutput()) {
                                String converted = toOldFormat((String) param.args[1]);
                                if (converted != null) {
                                    param.args[1] = converted;
                                }
                            }
                        }
                    });
            XposedBridge.log("[BiliCache] hooked Utf8Kt.writeString$default (old-format output)");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] Utf8Kt hook failed: " + t);
        }

        // 旧版离线模块用 FileUtils 写入：writeStringToFile(File, String, String[, boolean])
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bilibili.commons.io.FileUtils", classLoader, "writeStringToFile",
                    File.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BiliPrefs.oldFormatOutput()) {
                                String converted = toOldFormat((String) param.args[1]);
                                if (converted != null) {
                                    param.args[1] = converted;
                                }
                            }
                        }
                    });
            XposedBridge.log("[BiliCache] hooked FileUtils.writeStringToFile(3) (old-format output)");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] FileUtils(3) hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bilibili.commons.io.FileUtils", classLoader, "writeStringToFile",
                    File.class, String.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BiliPrefs.oldFormatOutput()) {
                                String converted = toOldFormat((String) param.args[1]);
                                if (converted != null) {
                                    param.args[1] = converted;
                                }
                            }
                        }
                    });
            XposedBridge.log("[BiliCache] hooked FileUtils.writeStringToFile(4) (old-format output)");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] FileUtils(4) hook failed: " + t);
        }
    }

    /**
     * 新版格式 -> 旧版格式。不匹配时返回 null（保持原样）。
     */
    private static String toOldFormat(String json) {
        if (json == null) {
            return null;
        }
        if (json.contains("\"season_id\"")) {
            try {
                JSONObject obj = new JSONObject(json);
                if (obj.has("season_id") && !obj.has("seasion_id")) {
                    obj.put("seasion_id", obj.remove("season_id"));
                }
                if (obj.has("ep") && obj.isNull("ep")) {
                    obj.remove("ep");
                }
                return obj.toString();
            } catch (JSONException e) {
                return null;
            }
        }
        if (json.contains("\"widevinePssh\"") || json.contains("\"bilidrmUri\"")) {
            try {
                JSONObject root = new JSONObject(json);
                for (String key : new String[]{"video", "audio"}) {
                    JSONArray arr = root.optJSONArray(key);
                    if (arr == null) {
                        continue;
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        if (item.has("bilidrmUri") && !item.has("bilidrm_uri")) {
                            item.put("bilidrm_uri", item.remove("bilidrmUri"));
                        }
                        item.remove("widevinePssh");
                        if ("audio".equals(key)) {
                            // 旧版 audio 条目没有 frame_rate
                            item.remove("frame_rate");
                        }
                        if (!item.has("dash_drm_type")) {
                            item.put("dash_drm_type", 0);
                        }
                        if (!item.has("audio_stream_type")) {
                            item.put("audio_stream_type", 0);
                        }
                    }
                }
                return root.toString();
            } catch (JSONException e) {
                return null;
            }
        }
        return null;
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
