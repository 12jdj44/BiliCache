package com.bilicache;

import java.util.HashMap;
import java.util.Map;

/**
 * 自动生成：8.61.0 - 9.6.0 各版本的 Hook 目标映射（按 versionCode）。
 * 每个版本: [getterClass, getterMethod, gateClass, gateMethod]
 * getter: 读取 c_db_migrate_success_times 的无参 int 方法，Hook 返回 0
 * gate:   DataStorageWrapper 里判断迁移成功次数>=3 的 boolean 方法，Hook 返回 false
 */
public final class VersionMap {
    public static final Map<Integer, String[]> MAP = new HashMap<>();
    static {
        MAP.put(8610300, new String[]{"video.offline.utils.m$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.61.0
        MAP.put(8611100, new String[]{"video.offline.utils.m$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.61.1
        MAP.put(8620300, new String[]{"video.offline.utils.m$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.62.0
        MAP.put(8630300, new String[]{"video.offline.utils.m$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.63.0
        MAP.put(8640300, new String[]{"video.offline.utils.n$a", "k", "video.offline.storage.DataStorageWrapper", "I"}); // 8.64.0
        MAP.put(8650200, new String[]{"video.offline.utils.p$a", "k", "video.offline.storage.DataStorageWrapper", "I"}); // 8.65.0
        MAP.put(8660300, new String[]{"video.offline.utils.p$a", "k", "video.offline.storage.DataStorageWrapper", "I"}); // 8.66.0
        MAP.put(8670300, new String[]{"video.offline.utils.p$a", "k", "video.offline.storage.DataStorageWrapper", "I"}); // 8.67.0
        MAP.put(8680200, new String[]{"video.offline.utils.p$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.68.0
        MAP.put(8690300, new String[]{"video.offline.utils.p$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.69.0
        MAP.put(8700300, new String[]{"video.offline.utils.p$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.70.0
        MAP.put(8710600, new String[]{"video.offline.utils.p$a", "j", "video.offline.storage.DataStorageWrapper", "I"}); // 8.71.0
        MAP.put(8720300, new String[]{"video.offline.utils.s$a", "n", "video.offline.storage.DataStorageWrapper", "N"}); // 8.72.0
        MAP.put(8730400, new String[]{"video.offline.utils.s$a", "n", "video.offline.storage.DataStorageWrapper", "N"}); // 8.73.0
        MAP.put(8740400, new String[]{"video.offline.utils.s$a", "n", "video.offline.storage.DataStorageWrapper", "N"}); // 8.74.0
        MAP.put(8750200, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.75.0
        MAP.put(8760400, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.76.0
        MAP.put(8770300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.77.0
        MAP.put(8780300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.78.0
        MAP.put(8790200, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.79.0
        MAP.put(8800300, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.80.0
        MAP.put(8810200, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.81.0
        MAP.put(8820300, new String[]{"video.biz.offline.base.infra.utils.r$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.82.0
        MAP.put(8830500, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.83.0
        MAP.put(8840200, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.84.0
        MAP.put(8850500, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.85.0
        MAP.put(8860400, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.86.0
        MAP.put(8870400, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.87.0
        MAP.put(8880300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.88.0
        MAP.put(8890400, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.89.0
        MAP.put(8900300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.90.0
        MAP.put(8901100, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.90.1
        MAP.put(8902100, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.90.2
        MAP.put(8910300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.91.0
        MAP.put(8911100, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.91.1
        MAP.put(8920300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.92.0
        MAP.put(8921100, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.92.1
        MAP.put(8930400, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.93.0
        MAP.put(8940300, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.94.0
        MAP.put(8950600, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "N"}); // 8.95.0
        MAP.put(8960400, new String[]{"video.biz.offline.base.infra.utils.s$a", "n", "video.biz.offline.base.infra.storage.DataStorageWrapper", "O"}); // 8.96.0
        MAP.put(8970300, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 8.97.0
        MAP.put(8980200, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 8.98.0
        MAP.put(8990400, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 8.99.0
        MAP.put(9000200, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.0.0
        MAP.put(9010300, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.1.0
        MAP.put(9011100, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.1.1
        MAP.put(9020300, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.2.0
        MAP.put(9030300, new String[]{"video.biz.offline.base.infra.utils.b$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.3.0
        MAP.put(9040200, new String[]{"video.biz.offline.base.infra.utils.a$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.4.0
        MAP.put(9050300, new String[]{"video.biz.offline.base.infra.utils.b$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.5.0
        MAP.put(9060300, new String[]{"video.biz.offline.base.infra.utils.c$a", "a", "video.biz.offline.base.infra.storage.DataStorageWrapper", "o"}); // 9.6.0
    }

    private VersionMap() {}
}