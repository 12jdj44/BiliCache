#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描 8.61.0-9.6.0 目录下的所有 B 站 APK(zip)，为每个版本找出：
  1. getter: 读取 "c_db_migrate_success_times" 的混淆方法（配置读取器）
  2. gate:   DataStorageWrapper 里调用该 getter 的迁移完成判断方法（布尔）
  3. wrapper: DataStorageWrapper 类名
输出 version_map.json，供 LSPosed 模块按 versionCode 选择 Hook 目标。

用法:
    python analyze_versions.py <zips目录> <输出json>
"""

import json
import os
import struct
import sys
import zipfile

TARGET_STR = "c_db_migrate_success_times"


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


class Dex:
    def __init__(self, data):
        self.data = data
        assert data[:4] == b"dex\n", data[:8]
        self.string_ids_size, self.string_ids_off = struct.unpack_from("<II", data, 56)
        self.type_ids_size, self.type_ids_off = struct.unpack_from("<II", data, 64)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from("<II", data, 72)
        self.method_ids_size, self.method_ids_off = struct.unpack_from("<II", data, 88)
        self.class_defs_size, self.class_defs_off = struct.unpack_from("<II", data, 96)

        self.strings = [self._string(i) for i in range(self.string_ids_size)]
        self.types = [
            self.strings[idx] for idx in self._u32_list(self.type_ids_off, self.type_ids_size)
        ]
        self.protos = []
        for i in range(self.proto_ids_size):
            shorty, ret, params_off = struct.unpack_from(
                "<III", self.data, self.proto_ids_off + i * 12
            )
            self.protos.append((self.strings[shorty], self.types[ret], params_off))
        self.methods = []
        for i in range(self.method_ids_size):
            cls, proto, name = struct.unpack_from(
                "<HHI", self.data, self.method_ids_off + i * 8
            )
            self.methods.append((self.types[cls], self.protos[proto], self.strings[name]))

    def _string(self, idx):
        off = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)[0]
        p = off
        while self.data[p] & 0x80:
            p += 1
        p += 1
        end = self.data.index(b"\0", p)
        return self.data[p:end].decode("utf-8", "replace")

    def _u32_list(self, off, size):
        return [
            struct.unpack_from("<I", self.data, off + i * 4)[0] for i in range(size)
        ]

    def methods_with_string(self, target):
        """Find (class, method_name, proto, code_off) for methods whose code references target string."""
        hits = []
        for i in range(self.class_defs_size):
            off = self.class_defs_off + i * 32
            class_idx = struct.unpack_from("<I", self.data, off)[0]
            class_data_off = struct.unpack_from("<I", self.data, off + 24)[0]
            if class_data_off == 0:
                continue
            p = class_data_off
            _, p = uleb(self.data, p)
            _, p = uleb(self.data, p)
            direct_size, p = uleb(self.data, p)
            virtual_size, p = uleb(self.data, p)
            # skip encoded_field lists (two blocks: static, instance)
            for _ in range(2):
                # need counts; encoded fields: each has field_idx_diff + access_flags
                # field counts are static_fields_size and instance_fields_size,
                # but we didn't keep them. Re-read properly:
                pass
            # fallback: re-read with field counts
            p2 = class_data_off
            static_fields_size, p2 = uleb(self.data, p2)
            instance_fields_size, p2 = uleb(self.data, p2)
            direct_size, p2 = uleb(self.data, p2)
            virtual_size, p2 = uleb(self.data, p2)
            for _ in range(static_fields_size + instance_fields_size):
                _, p2 = uleb(self.data, p2)
                _, p2 = uleb(self.data, p2)
            # direct_methods 和 virtual_methods 是两个独立列表，
            # 每个列表第一个方法的 method_idx_diff 相对 0 计算（不能跨列表累计）
            for section_size in (direct_size, virtual_size):
                method_idx = 0
                for _ in range(section_size):
                    diff, p2 = uleb(self.data, p2)
                    access, p2 = uleb(self.data, p2)
                    code_off, p2 = uleb(self.data, p2)
                    method_idx += diff
                    if code_off == 0:
                        continue
                    if self._code_refs_string(code_off, target):
                        cls_name, proto, mname = self.methods[method_idx]
                        hits.append(
                            (cls_name, mname, proto[1], proto[2], access, code_off)
                        )
        return hits

    def _code_refs_string(self, code_off, target):
        d = self.data
        registers, ins, outs, tries = struct.unpack_from("<HHHH", d, code_off)
        debug_off = struct.unpack_from("<I", d, code_off + 8)[0]
        insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        insns_off = code_off + 16
        # skip past try/catch structures if any
        if tries > 0:
            # try_item: 8 bytes each; handlers after
            handlers_off = insns_off + insns_size * 2
            # find handlers: align to 4
            handlers_off = (handlers_off + 3) & ~3
            # count handlers and their sizes; but we only scan insns, so not needed.
            pass
        for i in range(insns_size):
            u = struct.unpack_from("<H", d, insns_off + i * 2)[0]
            op = u & 0xFF
            if op == 0x1A and i + 1 < insns_size:  # const-string
                idx = struct.unpack_from("<H", d, insns_off + (i + 1) * 2)[0]
                if idx < len(self.strings) and self.strings[idx] == target:
                    return True
                i += 1
            elif op == 0x1B and i + 2 < insns_size:  # const-string/jumbo
                lo = struct.unpack_from("<H", d, insns_off + (i + 1) * 2)[0]
                hi = struct.unpack_from("<H", d, insns_off + (i + 2) * 2)[0]
                idx = lo | (hi << 16)
                if idx < len(self.strings) and self.strings[idx] == target:
                    return True
                i += 2
        return False

    def methods_referencing(self, target_method_idx, only_class_substr=None):
        """Find methods that invoke the given method index."""
        hits = []
        for i in range(self.class_defs_size):
            off = self.class_defs_off + i * 32
            class_idx = struct.unpack_from("<I", self.data, off)[0]
            class_data_off = struct.unpack_from("<I", self.data, off + 24)[0]
            if class_data_off == 0:
                continue
            p = class_data_off
            static_fields_size, p = uleb(self.data, p)
            instance_fields_size, p = uleb(self.data, p)
            direct_size, p = uleb(self.data, p)
            virtual_size, p = uleb(self.data, p)
            for _ in range(static_fields_size + instance_fields_size):
                _, p = uleb(self.data, p)
                _, p = uleb(self.data, p)
            for section_size in (direct_size, virtual_size):
                method_idx = 0
                for _ in range(section_size):
                    diff, p = uleb(self.data, p)
                    access, p = uleb(self.data, p)
                    code_off, p = uleb(self.data, p)
                    method_idx += diff
                    if code_off == 0:
                        continue
                    cls_name, proto, mname = self.methods[method_idx]
                    if only_class_substr and only_class_substr not in cls_name:
                        continue
                    if self._code_refs_method(code_off, target_method_idx):
                        hits.append((cls_name, mname, proto[1], access, code_off))
        return hits

    def _code_refs_method(self, code_off, target_method_idx):
        d = self.data
        insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        insns_off = code_off + 16
        invoke_ops = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75}
        for i in range(insns_size):
            u = struct.unpack_from("<H", d, insns_off + i * 2)[0]
            op = u & 0xFF
            if op in invoke_ops:
                idx = struct.unpack_from("<H", d, insns_off + (i + 1) * 2)[0]
                if idx == target_method_idx:
                    return True
                if op in (0x6E, 0x6F, 0x70, 0x71, 0x72):
                    i += 3  # 35c: 4 units total (skip C/D/E/F words)
                else:
                    i += 2  # 3rc: 3 units total
        return False

    def find_class(self, substr):
        return [t for t in self.types if substr in t]


def analyze_apk(path):
    result = {"file": os.path.basename(path), "getters": [], "gate": None, "wrappers": []}
    with zipfile.ZipFile(path) as zf:
        dex_names = sorted(n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex"))
        if not dex_names:
            return result
        all_getters = []
        all_wrappers = []
        all_gates = []
        for dn in dex_names:
            dex = Dex(zf.read(dn))
            getters = dex.methods_with_string(TARGET_STR)
            wrappers = dex.find_class("DataStorageWrapper")
            for g in getters:
                all_getters.append(
                    {
                        "class": g[0],
                        "method": g[1],
                        "return": g[2],
                        "params_off": g[3],
                        "access": g[4],
                    }
                )
            all_wrappers.extend(wrappers)
            # gate: method in wrapper class that references the getter
            for wcls in wrappers:
                for g in getters:
                    # find getter method id
                    gid = None
                    for mi, (mcls, mproto, mname) in enumerate(dex.methods):
                        if (
                            mcls == g[0]
                            and mname == g[1]
                            and mproto[1] == g[2]
                            and mproto[2] == g[3]
                        ):
                            gid = mi
                            break
                    if gid is None:
                        continue
                    for ref in dex.methods_referencing(gid, only_class_substr=wcls):
                        all_gates.append(
                            {
                                "class": ref[0],
                                "method": ref[1],
                                "return": ref[2],
                                "access": ref[3],
                            }
                        )
        # dedupe
        # 只保留"无参、返回 int"的读取器（写入/日志方法会带参数或返回 void）
        result["getters"] = _dedupe(
            [g for g in all_getters if g["return"] == "I" and g["params_off"] == 0]
        )
        result["wrappers"] = sorted(set(all_wrappers))
        # prefer boolean gate
        bool_gates = [g for g in _dedupe(all_gates) if g["return"] == "Z"]
        result["gate"] = bool_gates[0] if bool_gates else (_dedupe(all_gates)[:1] or [None])[0]
    return result


def _dedupe(items):
    seen = set()
    out = []
    for it in items:
        k = (it["class"], it["method"], it["return"])
        if k not in seen:
            seen.add(k)
            out.append(it)
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src_dir, out_json = sys.argv[1], sys.argv[2]
    mapping = {}
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith(".zip"):
            continue
        version = name[:-4]
        print(f"analyzing {version} ...", flush=True)
        try:
            mapping[version] = analyze_apk(os.path.join(src_dir, name))
        except Exception as e:
            print(f"  ERROR: {e}", flush=True)
            mapping[version] = {"file": name, "error": str(e)}
    with open(out_json, "w", encoding="utf-8") as fh:
        json.dump(mapping, fh, ensure_ascii=False, indent=1)
    print(f"\nwritten {out_json}")


if __name__ == "__main__":
    main()
