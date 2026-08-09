package com.bilicache;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块设置读写。
 *
 * 开关保存在模块自己的 SharedPreferences（bilicache_settings）里，
 * Hook 侧通过 LSPosed 的 XSharedPreferences 跨进程读取。
 */
public final class BiliPrefs {

    public static final String PREFS_NAME = "bilicache_settings";

    /** 功能1：新版识别旧版缓存（getter/gate 主开关） */
    public static final String KEY_RECOGNIZE_OLD_CACHE = "recognize_old_cache";

    /** 【已弃用】功能2：新版按旧版格式写缓存（entry/index.json 转换）—— 实测无效，已停用 */
    public static final String KEY_OLD_FORMAT_OUTPUT = "old_format_output";

    private static volatile XSharedPreferences sPrefs;

    private BiliPrefs() {
    }

    public static boolean recognizeOldCache() {
        return getBoolean(KEY_RECOGNIZE_OLD_CACHE, true);
    }

    private static boolean getBoolean(String key, boolean def) {
        try {
            XSharedPreferences prefs = prefs();
            prefs.reload();
            return prefs.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static XSharedPreferences prefs() {
        if (sPrefs == null) {
            XSharedPreferences p = new XSharedPreferences(
                    "com.bilicache", PREFS_NAME);
            try {
                p.makeWorldReadable();
            } catch (Throwable ignored) {
            }
            sPrefs = p;
        }
        return sPrefs;
    }
}
