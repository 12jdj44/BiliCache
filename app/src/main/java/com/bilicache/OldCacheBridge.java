package com.bilicache;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块：让 B 站 8.61.0 - 9.6.0 启动时直接扫描识别旧版缓存。
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

        // 1) 迁移成功次数读取器 -> 恒为 0（主开关）
        try {
            XposedHelpers.findAndHookMethod(
                    cfg[0], classLoader, cfg[1],
                    XC_MethodReplacement.returnConstant(0));
            XposedBridge.log("[BiliCache] " + versionCode + " (" + versionName
                    + ") getter hooked: " + cfg[0] + "#" + cfg[1] + " -> 0");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] getter hook failed " + cfg[0] + "#" + cfg[1] + ": " + t);
        }

        // 2) 迁移完成判断 gate -> 恒为 false（保险，并禁用无效缓存清理）
        try {
            XposedHelpers.findAndHookMethod(
                    cfg[2], classLoader, cfg[3],
                    XC_MethodReplacement.returnConstant(false));
            XposedBridge.log("[BiliCache] " + versionCode + " (" + versionName
                    + ") gate hooked: " + cfg[2] + "#" + cfg[3] + " -> false");
        } catch (Throwable t) {
            XposedBridge.log("[BiliCache] gate hook failed " + cfg[2] + "#" + cfg[3] + ": " + t);
        }
    }
}
