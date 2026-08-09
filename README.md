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

## ~~旧版格式写缓存（已弃用）~~

曾尝试让新版 App 写入缓存时把 entry.json/index.json 转成旧版格式，
实测无效（写入路径/时序与预期不符），且需求上不再需要，该功能已注释移除，
相关 Hook（`hookOldFormatOutput`/`toOldFormat`）不再启用。

## 设置开关

目前保留一个开关（默认开启）：

- **识别旧版缓存**：控制 getter/gate Hook（全盘扫描登记 + 禁用无效缓存清理）

开关位置：

1. 模块自己的应用页（桌面图标 “Bili Cache”），带卡片式开关界面；
2. **B 站「我的 → 设置」页面最底部**会注入一个 “Bili Cache” 入口，点击直达模块设置页。

开关保存在模块的 `bilicache_settings` SharedPreferences 中，
Hook 每次调用实时读取（LSPosed XSharedPreferences），修改后立即生效，无需重启。

## 极老 FLV 缓存兼容（v1.4）

B 站 5.x-6.x 时代的缓存是 FLV 分段格式：

```
<avid>/<page>/lua.flvXXX.bili2api.<qn>/0.blv
```

这类 entry.json 没有 `media_type` 字段，新版扫描时默认当成 DASH，播放时会去
DASH 分支找 `video.m4s` 导致黑屏/损坏。v1.4 起模块会在播放解析前把媒体类型
强制改为 FLV（检测到目录里存在 `.blv` 文件），并让扫描登记时直接标记为 FLV，
旧版 FLV 缓存即可在新版正常播放。

## 日志导出（v1.5）

设置页新增“导出日志”开关：开启后，模块（运行在 B 站进程内）会把所有 Hook 事件
持续同步到公共 `Download/BiliCache.log`；同时在 LSPosed 日志中也能过滤
`[BiliCache]` 标签查看。导出采用多级回退：
1. `Download/BiliCache.log`（MediaStore，Android 11+ 无需权限）；
2. 旧版公共 Download 路径（需存储权限）；
3. `/sdcard/Android/data/tv.danmaku.bili/files/Download/BiliCache.log`（无权限要求）。

每次导出结果会 Toast 提示路径；排障流程：开启开关 → 重启 B 站 → 复现问题 → 取日志。

## v1.7：适配 8.75 及其它版本

- **播放解析 Hook 改为按签名动态匹配**：`OfflineResolverKt` 里找
  `(OfflineVideoEntity, File)` 方法（8.75 叫 `i`，9.6 叫 `f`），FLV 修复对所有版本生效；
- **通用配置兜底 Hook**：动态发现 `kntr.base.config.d/i` 的配置读取方法，
  拦截 `c_db_migrate_success_times` 恒返回 0——即使版本号不在映射表里，
  “识别旧版缓存”也能工作；
- **模块加载 Toast**：B 站启动时 Toast 提示“BiliCache 模块已加载”，方便确认模块是否生效。
- **失败 Toast（v1.8）**：版本未匹配、FLV Hook 未挂上、设置入口注入失败都会直接 Toast 提示，
  不依赖 logcat 也能定位问题。

## v1.9：修复 8.75 设置入口 + 解析结果日志

- 设置入口的点击监听器接口在 8.75 里被混淆（`Preference$OnPreferenceClickListener` 不存在），
  改为**按 `setOnPreferenceClickListener` 的参数类型动态创建**，兼容所有版本；
- 播放解析 Hook 增加**结果日志**：记录返回的 PlayIndex（播放路径/分段数/画质），
  以及是否抛异常，便于定位黑屏到底是解析失败还是播放器不支持；
- Toast 内容同步写入日志，MIUI 屏蔽 Toast 时也能在日志里看到。

## 确认模块生效

1. LSPosed 管理器 → 模块勾选 “Bili Cache” → **作用域勾选 `tv.danmaku.bili`** → 重启；
2. 打开 B 站：启动时应有 “BiliCache 模块已加载” Toast；
3. B 站「我的 → 设置」顶部出现 “Bili Cache” 入口；
4. LSPosed 日志可过滤 `[BiliCache]`。

以上任一项出现都说明模块已生效；若都没有，请检查 LSPosed 模块是否启用、作用域是否勾选。

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
