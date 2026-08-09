#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据 version_map.json + version_codes.txt 生成 LSPosed 模块的 VersionMap.java"""

import json


def to_dot(cls):
    return cls.lstrip("L").rstrip(";").replace("/", ".")


def main():
    mapping = json.load(open("version_map.json", encoding="utf-8"))
    codes = {}
    with open("version_codes.txt", encoding="utf-8-sig") as fh:
        for line in fh:
            parts = line.strip().split("\t")
            if len(parts) == 3:
                codes[parts[0]] = parts[1]

    out = [
        "package com.bilicache;",
        "",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "",
        "/**",
        " * 自动生成：8.61.0 - 9.6.0 各版本的 Hook 目标映射（按 versionCode）。",
        " * 每个版本: [getterClass, getterMethod, gateClass, gateMethod]",
        " * getter: 读取 c_db_migrate_success_times 的无参 int 方法，Hook 返回 0",
        " * gate:   DataStorageWrapper 里判断迁移成功次数>=3 的 boolean 方法，Hook 返回 false",
        " */",
        "public final class VersionMap {",
        "    public static final Map<Integer, String[]> MAP = new HashMap<>();",
        "    static {",
    ]
    for ver in sorted(mapping):
        d = mapping[ver]
        g = (d.get("getters") or [None])[0]
        gate = d.get("gate")
        vc = codes.get(ver)
        if g is None or gate is None or vc is None:
            continue
        line = (
            '        MAP.put(%s, new String[]{"%s", "%s", "%s", "%s"}); // %s'
            % (vc, to_dot(g["class"]), g["method"], to_dot(gate["class"]), gate["method"], ver)
        )
        out.append(line)
    out.append("    }")
    out.append("")
    out.append("    private VersionMap() {}")
    out.append("}")

    with open(
        "BiliCache/app/src/main/java/com/bilicache/VersionMap.java",
        "w",
        encoding="utf-8",
    ) as fh:
        fh.write("\n".join(out))
    print("written, versions:", len([v for v in mapping if v in codes]))


if __name__ == "__main__":
    main()
