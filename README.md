# Bili Cache

LSPosed 模块：让 B 站 **8.61.0 - 9.6.0（共 52 个版本）** 启动时**直接扫描识别旧版离线缓存**，
不需要清应用数据、重置迁移计数或先让旧版登记。

## 原理

新版 B 站识别离线缓存的逻辑（`video.biz.offline.base.infra.storage.DataStorageWrapper`）：

- 数据库迁移成功次数 `c_db_migrate_success_times >= 3`（即 gate 为 true）之后，
  应用只读 `offlineVideo.db`，不再扫描磁盘 —— 手动拷贝的旧缓存目录没有数据库记录，因此“看不见”。
- 更糟的是，`cleanInvalidSourceIfNeed` 会把“磁盘上存在、数据库里没有”的缓存目录当无效文件删除。

模块按 versionCode 选择对应版本的 Hook 目标（`VersionMap.java`，由 dex 静态分析自动生成）：

- **getter**：读取 `c_db_migrate_success_times` 的无参 int 方法 → 恒返回 0
- **gate**：`DataStorageWrapper` 中判断“迁移成功次数 >= 3”的 boolean 方法
  （旧版 `#I`/`#N`，新版 `#o`/`#O`）→ 恒返回 false

两个 Hook 同时生效后：

1. 每次启动都会全盘扫描下载目录（`Android/data/tv.danmaku.bili/download`，内/外置存储都会扫）；
2. 旧格式的 `entry.json` / `index.json` 能被新解析器读取（`ignoreUnknownKeys=true`，字段差异无影响）；
3. 迁移链会把扫描到的缓存（含旧版拷贝来的目录）写入数据库，之后列表正常显示、可播放；
4. `cleanInvalidSourceIfNeed` 因 gate 为 false 直接跳过，不再删除磁盘上的缓存目录。

所以装上模块后，只要把旧缓存目录放进正确的下载目录，重启应用（或等它下次扫描）即可识别。

## 附加功能：新版缓存直接写成旧版格式

模块还会拦截新版 App 写入缓存元数据，把输出转成旧版（legacy）格式，
这样新版下载/更新的缓存，旧版 App 也能直接读取：

- `entry.json`：`season_id` -> `seasion_id`，去掉 `ep:null`
- `index.json`：`bilidrmUri` -> `bilidrm_uri`，去掉 `widevinePssh`，
  补 `dash_drm_type`/`audio_stream_type`，audio 条目去掉 `frame_rate`

实现方式：Hook `kotlinx.io.Utf8Kt.writeString$default` 和
`com.bilibili.commons.io.FileUtils.writeStringToFile`，只处理包含新版特征字段的 JSON，
其它写入不受影响。

注意：转换后 entry.json 使用 `seasion_id`，新版自身读取时 season 相关字段会按旧键名解析；
普通 UGC 视频无影响（season_id 本就为 0）。`cover.jpg`/`danmaku.pb` 仍会照常下载
（旧版读取时会忽略多余文件）。

## 版本适配范围

| 版本区间 | getter 类 | gate |
|---|---|---|
| 8.61.0 - 8.71.0 | `video.offline.utils.{m,n,p}$a#{j,k}` | `DataStorageWrapper#I` |
| 8.72.0 - 8.74.0 | `video.offline.utils.s$a#n` | `DataStorageWrapper#N` |
| 8.75.0 - 8.95.0 | `video.biz.offline.base.infra.utils.{r,s}$a#n` | `DataStorageWrapper#N` |
| 8.96.0 | `video.biz.offline.base.infra.utils.s$a#n` | `DataStorageWrapper#O` |
| 8.97.0 - 9.6.0 | `video.biz.offline.base.infra.utils.{a,b,c}$a#a` | `DataStorageWrapper#o` |

完整 52 个版本的 versionCode → Hook 目标映射见
`app/src/main/java/com/bilicache/VersionMap.java`
（由 `tools/analyze_versions.py` + `tools/generate_version_map.py`
从各版本 APK 的 dex 自动分析生成）。

## 构建

需要 Android Studio（或 Gradle + Android SDK，compileSdk 34）。

```bash
cd BiliCache
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 本地签名（无 Android Studio 环境）

release 产物默认未签名，用以下命令签名后即可安装到 LSPosed：

```bat
set JAVA_HOME=<JDK21路径>
set ANDROID_HOME=<SDK路径>

rem 1. 生成密钥（首次）
keytool -genkeypair -keystore module.keystore -alias bilicache ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass bilicache -keypass bilicache -dname "CN=BiliCache,O=Local,C=CN"

rem 2. 对齐 + 签名
%ANDROID_HOME%\build-tools\34.0.0\zipalign -f 4 ^
  app\build\outputs\apk\release\app-release-unsigned.apk app-release-aligned.apk
%ANDROID_HOME%\build-tools\34.0.0\apksigner sign --ks module.keystore ^
  --ks-pass pass:bilicache --key-pass pass:bilicache ^
  --out app\build\outputs\apk\release\app-release.apk app-release-aligned.apk
```

## 安装与使用

1. 安装签名后的 APK；
2. LSPosed 管理器 → 模块勾选 “Bili Cache” → 作用域勾选 `tv.danmaku.bili` → 重启；
3. 把旧缓存目录拷贝到：
   ```
   /sdcard/Android/data/tv.danmaku.bili/download/<avid>/c_<cid>/<type_tag>/
   ```
   （目录结构保持原样：`entry.json`、`index.json`、`audio.m4s`、`video.m4s` 等）
4. 打开/重启 B 站，离线缓存页即可看到旧缓存。

## 注意事项

- 类名/方法名来自各版本 APK 的 dex 静态分析结果，是 R8 混淆后的名字；
  换其他版本时需要重新运行 `tools/analyze_versions.py` 并更新 `VersionMap.java`。
- 每次启动都会执行一次“扫描 + 迁移（重建数据库记录）”，缓存很多时启动会稍慢，属预期行为；
  这也意味着之后直接往目录里拷贝新缓存，重启应用即可被识别，不需要任何额外操作。
- 模块同时禁用了“无效缓存清理”，磁盘上未登记目录不会再被自动删除。
- 媒体文件本身（编码、URL 签名）不做任何修改，旧缓存仍以原格式播放。
