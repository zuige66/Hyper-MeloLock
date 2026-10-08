# Hyper MeloLock — 开发文档

> 本文件是 Hyper MeloLock 的**开发与实现文档**：当前实现细节、SystemUI 侧机制、真机验收步骤、故障恢复手段与完整变更日志。
> 面向使用者的项目介绍、功能说明与安装指引见仓库根目录的 [README.md](../README.md)；面向 AI 协作者的工程约定见 [AGENTS.md](../AGENTS.md)。
>
> 下文中的路径、命令、设备序列号与版本号均以 2026-10-06 的验证环境为准。

独立的 Android Vector/LSPosed 模块；不修改 Cool Music 或其他播放器。只给 `com.android.systemui` 注入一个锁屏视图适配器。媒体发现、封面和播放控制使用 Android `MediaSessionManager` / `MediaController`。当前仅为指定真机构建开放，默认关闭。

## 真机日志驱动调试技能（已接入）

本项目已接入 WorkBuddy 的 `ondevice-log-driven-debug` 技能（来源：`C:\Users\lirui\.workbuddy\skills\ondevice-log-driven-debug\SKILL.md`）。之后涉及 SystemUI、锁屏覆盖层、Vector/LSPosed 或窗口转场的排查，统一按以下闭环执行：

1. 先用 `adb logcat -c` 清现场，再以 `logcat -d -v time -s MeloLock:V` 或事件窗口 `-T 1` 抓取原始日志；不要用错误的 `Tag:` 文本过滤器判断模块是否运行。
2. 日志落盘后计算相邻时间戳，超过 60 ms 的间隔与主线程工作逐条对账；不靠截图主观判断卡顿。
3. 锁屏亮起时用 `uiautomator dump` 获取真实资源 ID、坐标和层级；配置通过 `content query` 直接读取 Provider 真值。
4. 结论明确区分“日志证明的事实、合理推断、尚无法判断”；每条 `restore` / `skip` 都带原因，且重复状态去重。
5. 改 ROM 行为前先从设备 APK 的 DEX/资源表核实真实类名、方法名和资源 ID；不要按日志文案、网上布局名或猜测的方法名下注入。
6. 不把 `KeyguardManager.isKeyguardLocked()` 当作“覆盖层是否仍遮挡”的唯一判据；优先使用 ROM 的解锁/遮挡回调，并核对 `keyguardRoot` 父容器可见性。
7. 诊断探针独立登记、结论拿到后删除；不在每帧回调里写日志、改视图树或 `bringToFront()`，避免探针本身制造卡顿。

完整原文保留在上述 WorkBuddy 路径；本节是当前仓库的执行约定，后续变更日志也按该技能的“装包 → 重启作用域 → 用户复现 → 日志对账 → 文档更新”闭环记录。

- **仓库**：<https://github.com/zuige66/Hyper-MeloLock>
- **下载**：[Releases](https://github.com/zuige66/Hyper-MeloLock/releases) → 最新版 [v0.2.0](https://github.com/zuige66/Hyper-MeloLock/releases/download/v0.2.0/Hyper-MeloLock-v0.2.0.apk)（`Hyper-MeloLock-v0.2.0.apk`，正式签名，37.1 MB）
- **许可**：AGPL-3.0（见 `LICENSE`）

> 当前唯一验证设备是下面那台 Redmi Note 9 Pro；模块按**精确构建指纹**门禁（`Config.deviceSupported()`），换机型不会生效。

## 已核实的设备

2026-10-06 通过 ADB 读取：

| 项目 | 值 |
| --- | --- |
| 设备 | Redmi Note 9 Pro，`M2007J17C`，`gauguinpro` |
| Android | 16，API 36 |
| 构建指纹 | `Redmi/gauguinpro/gauguinpro:16/BP2A.250605.031.A3/OS3.0.303.0.WNKCNXM:user/release-keys` |
| SystemUI | `com.android.systemui`，`16.03.251211.r`，versionCode `202501210` |
| SystemUI 插件 | `miui.systemui.plugin`，`17.1.4.26.0`，versionCode `171042600` |
| SukiSU Ultra 应用 | `com.sukisu.ultra`，`v3.1.8` |

已从该设备 APK 的 DEX 确认 `com.android.keyguard.widget.HyperOSKeyguardRootView`、`KeyguardClockContainer`、`NotificationStackScrollLayout` 类存在。当前适配器只在上述**精确构建指纹和两个 APK 版本**匹配时工作；根视图中缺少时钟或通知栈时也会放弃覆盖。

## 当前实现

### 配置应用界面

配置端现直接迁入并使用 HyperIsland 的 Kotlin、Jetpack Compose、Miuix 页面壳层、主题、动画和液态导航栏（MIT License），四个入口的版式也改为直接复用 HyperIsland 原版组件，不再自绘样式：

- **首页**：对齐 HyperIsland 首页版式 —— 顶部大标题加右上角按钮，第一行是「方形激活卡（大号对勾底纹）」加右侧两张数据卡，下面是（按需出现的）适配告警卡、系统信息卡、链接卡和使用说明卡。
  - **实现在 `LockScreenPages.kt` 的 `LockHomePage`**。`page/home/OverviewPage.kt` 只提供共享卡片组件，它这个 `OverviewPage` Composable 本身**没有任何调用点（死代码）**——改首页行为务必改 `LockHomePage`。
  - 右上角按钮是「重启作用域」：先 `RestartScopeService.hasRoot()` 探测 root，有权限才弹 `RestartScopeDialog`，无权限走模块通道发 `Config.ACTION_RESTART_SYSTEMUI` 显式广播并 Toast `restart_scope_requested`，顺便 `refreshToken++` 刷新首页数据。
  - 状态卡点击即切换模块总开关，标签在 `已激活` / `未激活` 之间切换。
  - 左数据卡为「已开启应用」，取 `Config.enabledAppCount()`（实际勾选数量，未勾选为 `0`）；点击跳到「音乐应用」页。
  - 右数据卡为「封面圆角」，取 `Config.cornerRadiusDp()`；点击跳到「外观」页。
  - 告警卡仅在 `Config.deviceSupported()` 为假（设备构建指纹不在已验证列表内）时出现，与 `HookEntry` 的加载门禁同一判据。
  - 系统信息卡四行：系统版本、应用版本、Xposed 框架、设备型号。前三行中 Xposed 框架依赖 Vector/LSPosed 服务绑定（`XposedPrefsSyncApp.awaitReady()`）；legacy 模块拿不到服务时显示 `未知`。
- **音乐应用**：对齐 HyperIsland「应用」页 —— 搜索栏加应用行（图标、名称、包名、开关），整行点击也可切换。**列表是全部已安装应用**（含系统应用，可用「显示系统应用」开关过滤），由用户自行勾选哪些是音乐播放器。**默认一个都不勾选，且默认不显示系统应用**——必须显式勾选播放器锁屏才会接管它（与 `Config.packageAllowed()` 对齐）；全部取消后锁屏不再接管任何播放器。列表上方有「**全选**」开关：勾上＝把**当前列表**（受搜索与「显示系统应用」过滤影响）里的应用全部选中，再点一次＝把这些全部取消；它只是对同一份 `allowed_packages` 集合做批量增删，不是独立状态。该页进入时会打一行 `Music apps: N selected by default`，`N=0` 才是真的一个都没勾。HyperOS 上枚举全部应用需要 `com.android.permission.GET_INSTALLED_APPS`，**该权限必须在 Manifest 里显式声明**：申请一个未声明的权限，系统会直接返回拒绝、连授权框都不弹（这正是 2026-10-06 第一次改完列表为空、看不到授权框的原因）。进入该页时会自动申请，实测弹出 MIUI 的「获取已安装的应用信息」授权框。
- **外观**：锁屏三元素（时间 / 专辑封面 / 播放器）的编辑器，外加原有的背景设置。配置通过只读 `ContentProvider` 同步给 SystemUI。
- **开发者**（第 4 个根页面，`LockAboutPage`）：版式参考上游 `AboutPage.kt` —— 整屏一个滚动列表，hero（应用图标 + 应用名 + 版本号）叠在顶部，**上滑时 hero 淡出并轻微缩小、动画渐变背景同时淡掉**，列表内容看起来是「盖上来」的。背景动画直接复用同包的 `AnimatedAboutBackground` / `rememberAboutAnimationTime` / `animatedGradientColors`（从 `private` 提升为 `internal` 共享，没有第二份实现）。最外层 `Box` 里 hero 画在 `LazyColumn` 之后（更上层），列表首项用 `Spacer(heroHeight + 16.dp)` 给 hero 留位。
  - 滚动映射与上游同一套：`backgroundAlpha = 1 - offset/389dp`、`logoProgress = (offset - 0.25·hero) / 0.35·hero`、缩放 `1 - 0.1·progress`。
  - 顶部的渐变背景动画是逐帧的，`isActive`（由 `AppShell` 传 `pagerState.currentPage == 3`）为假时**不跑**，避免在别的页面白耗电。
  - 开发者卡片：圆形头像（`res/drawable-nodpi/dev_avatar.jpg`，`Crop` + `CircleShape` 裁切）、名称 `zuige`、GitHub 号 `@zuige66`，整卡点击直达 `github.com/zuige66`。
  - **没有的功能一律灰度**：讨论（Telegram）、备份与恢复、检查更新、引用、隐私政策都是 `enabled = false` 占位。为此给 `SettingsActionWithArrow` 补了 `enabled` 参数（与 `SettingsAction` 对齐）。项目区里 GitHub（`zuige66/Hyper-MeloLock`）与更新日志（GitHub Releases）是真实链接。

四页共用的卡片来自 HyperIsland 原版实现：`OverviewPage.kt` 里原先私有的 `StatusGrid` / `StatusCard` / `StatCard` / `InfoCard` / 告警卡已提升为 `internal` 的 `OverviewStatusGrid` / `OverviewStatusCard` / `OverviewStatCard` / `OverviewInfoCard` / `OverviewAlertCard`，只把标题与数值参数化，视觉与交互代码未改动。HyperIsland 自己的首页（`OverviewPage`）改为调用同一批组件，因此不存在第二份样式实现。音乐应用页的行样式沿用 HyperIsland `AppsPage` 的 `Card` + `BasicComponent` 组合，图标复用 `InstalledAppsRepository` 的缓存与解码逻辑。

AppShell 的根分页把 `isActive` 传给首页，首页在重新可见时重读配置，因此从其他页改完设置回到首页，数据卡会刷新。

原有 Java/Xposed 锁屏适配链路保留；Compose 页面通过公开的 `Config` API 与同一 ContentProvider 配置同步。`Config.java` 新增 `selectedPackages()`、`allPackagesDisabled()`、`enabledAppCount()`、`deviceSupported()` 四个只读辅助方法，用于区分「未设置（允许全部）」与「已全部取消」两种空集，没有改动任何配置键或读写语义。构建环境已升级到 AGP 9.3.1、Gradle 9.5、Kotlin/Compose 2.4.10。

界面文案现状：这四个页面沿用原有实现，标题与说明文本直接写中文，只有导航栏、系统信息行和链接行使用 `strings.xml` 资源；`待填写` 也是硬编码中文。后续要补多语言时需要一并抽到资源。

### 锁屏元素编辑（时间 / 专辑封面 / 播放器）

`外观` 页把锁屏上三个自绘元素拆成三张卡，每张卡都能调大小、圆角和间距：

| 元素 | 控制项 | 默认值 | 备注 |
| --- | --- | --- | --- |
| 时间 | 字号、粗细、字体圆润、颜色、距顶部 | 字号 75、粗细 770、圆润 0、白色、距顶部 52dp | 时间是文本，不设固定宽高，始终自适应字号与字体，不会被裁剪 |
| 日期 | 开关、字号、粗细、颜色、距顶部 | 开、字号 20、粗细 500、白色、距顶部 6dp | 内容＝「公历+周几 · 农历」（如 6月28日周六 · 乙巳年六月初四），ICU `ChineseCalendar` 换算，按天缓存跨天自动翻；**无「字体圆润」**——圆体字体只含数字，对汉字无效 |
| 签名 | 开关、内容、字号、粗细、颜色、距顶部 | 关、字号 16、粗细 500、白色、距顶部 6dp | **在日期行下方、居中**。自由文本走 `/elements` 的字符串值通道（`date_signature`，Provider 列投影不变）；签名变化拼进 `elementSignature` 触发整场重建 |
| 专辑封面 | 锁定比例、缩放或宽/高、圆角、间距 | 缩放 118%、R角 28dp、间距 24dp | 圆角沿用原有的 `corner_radius_dp`，首页数据卡读的就是它 |
| 播放器 | 锁定比例、缩放或宽/高、圆角、间距、底色 | 缩放 100%、R角 28dp、间距 20dp、底色深色 | 卡片宽度默认是「屏宽 − 24dp」；底色 7 档（跟随封面/深色/墨蓝/浅色/蓝灰/淡紫/淡粉），非手选档时卡片内文字按底色亮度自动适配，「跟随封面」从封面动态取色 |

**上面的默认值是 2026-10-06 从真机调好的一套抄回来的**（`content query .../elements`）：字号 75 / 粗细 770 / 封面缩放 118% / 圆角 28dp。背景三项的默认值（遮罩样式 0、颜色 `0xFF111827`、强度 150）当时已与真机一致，未改。时间是文本，**锁定比例与宽/高已在同日移除**——固定高度会裁掉大字号和自定义字体，位置改由「距顶部」控制。

尺寸单位是 dp 绝对值。**锁定比例**开启时用一个缩放滑杆（时间用字号）等比调整；关闭后出现宽、高两个滑杆，封面在这种模式下按居中裁切填充，所以宽高比不同不会变形只会裁切。

配置实现：键名与默认值集中在 `Config.java` 的 `ELEMENT_DEFAULTS`，统一按字符串存取。`ConfigProvider` 新增 `elements` 路径（`content://io.github.melolock.config/elements`），返回 key/value 两列，因此以后再加参数不需要改 Provider 的列投影。SystemUI 侧由 `Config.elementValues()` 一次查询解析成整数表，读不到时回落默认值，不会撤掉沉浸页。

覆盖层把三元素算尺寸的逻辑集中在 `LockScreenOverlay.measureElements()`：锁定比例取默认尺寸乘百分比，解锁则取各自 dp 值，`0` 表示跟随默认。间距统一成「距上一个元素」：时间=距内容区顶部、封面=距时间、播放器=距封面，默认值与改版前完全一致。

**改完参数后重启 SystemUI 才稳妥**：覆盖层在 `create()` 时读一次配置。自「熄屏唤醒防闪」之后 `ACTION_SCREEN_OFF` 不再撤层，场景若仍存活就会继续沿用旧参数——灭屏再亮屏**不一定**生效，只有场景已被销毁（解锁、关闭模块、锁屏根视图分离）后重建才会读到新值。没有做实时重建——在锁屏期间动态增删 SystemUI 视图风险不可控。

**补充（2026-10-08 晚）：默认值真机化 + 「关于」页检查更新（43cd442）**

- **默认值＝真机配置**（`content query /elements` 全量抄回）：时钟 80/900/跟随封面/距顶 0，封面间距 10，卡片解锁 369×180（**注意：绝对 dp，其他屏宽设备会偏**）/底色跟随封面，日期 22/520/跟随封面/距顶 50、签名间距 8，入口背景跟随封面。**签名例外**：默认关 + 内容空白（用户指定），不随真机。state 三项（背景样式/遮罩/强度）与圆角 28 本就一致未动。存量用户 SharedPreferences 已有值不受影响，默认值只对新装生效。
- **导航「开发者」→「关于」**：`about` 字符串（zh/en）改「关于/About」；页内 SectionTitle `about_developer` 保留「开发者」。
- **检查更新**：恢复 Manifest 的 INTERNET（上游曾 `tools:node="remove"`）；`UpdateService.fetchIfNewer` 参数化 api/downloadUrl（默认仍指上游 HyperIsland），LockAboutPage 传本仓库 `zuige66/Hyper-MeloLock/releases/latest` + releaseUrl 指向 Releases 页。UI：原「模块」分组的灰度检查更新删除，在「项目」分组 GitHub 之下、更新日志之上插入可用项（点击转圈 → 有新版弹 `UpdateDialogHost`、无新版 Toast `already_latest`、失败弹失败对话框）。上游已有全套字符串/对话框组件，直接复用。

**补充（2026-10-08 晚）：检查更新 blog 回退源 + v0.3.0 发版（f4635c0、2c9e284 及后续）**

- **发版**：versionCode 10 / versionName 0.3.0，tag `v0.3.0`，Release 用临时 PAT 经 api.github.com 创建、uploads.github.com 上传 APK（39,157,583 字节，apksigner 校验 SHA-1 `2b73…c1f7` ✓）。
- **blog 回退源**：GitHub Releases API 失败（国内网络常态）→ 回退 Hexo blog 的静态 `latest.json`（`https://blog.zuiges.com/downloads/melolock/latest.json`，字段 versionName/versionCode/changelog/apkUrl，versionCode 整数比较）。两源都失败才抛原始 GitHub 错误弹失败框。`fetchIfNewer` 新增 `currentVersionCode` 参数（blog 侧没有 tag 可解析，只能整数比较）。**注意 Manifest 的 INTERNET 上游曾是 `tools:node="remove"`，必须恢复**。
- **blog 端部署**：`D:\Workplace\hexo\source\downloads\melolock\` 放 `latest.json` + APK，`hexo generate && hexo deploy`（**本机跑法：用 managed node 直接跑 `node_modules/hexo/bin/hexo`，pnpm exec 会失败**；部署目标是 gh-pages 分支）。
- **验证 404 的教训**：deploy 推送成功后立即 curl 仍 404，**别急着排查代码**——是 GitHub Pages 构建延迟（约几分钟）+ CDN 缓存旧 404 响应。正确排查顺序：① api.github.com 查 gh-pages 分支 `downloads/melolock` 目录（文件在）→ ② raw.githubusercontent.com 直链（200）→ ③ 等 CDN 过期后 blog 域名恢复 200。链路：Cloudflare → GitHub Pages（Fastly）。
- **发版红线**：必须打 tag，否则 GitHub API 的 `releases/latest` 检不到该版本；blog 仓库旧 APK 会随着版本堆积，可删。

**补充（2026-10-08 晚）：取色风格子选项（磨砂 / 鲜艳，每项独立）**

- 需求：跟随专辑取色目前只有一套「低饱和磨砂」（M3E）推导，用户要能选「鲜艳」。**每个可调颜色的项各加一个「取色风格」子选项**，仅当该颜色处于「跟随封面」档时显示。
- **新增 6 个 element 键**（`ELEMENT_DEFAULTS` 默认 0＝磨砂，1＝鲜艳；进 elementSignature，改动整场重建）：`card_bg_pick` / `clock_pick` / `date_pick` / `sign_pick` / `entry_color_pick` / `entry_bg_pick`。
- **取色管线重构**：后台 Palette 只算一次**主色原始 RGB**（字段 `autoSwatch`，0＝未取到/失败），不再预推导三种颜色；主线程按各项 pick 标志从同一主色推导：
  - 磨砂档（现行规则不变）：`containerFromSwatch`（浅容器 V0.82/S≤0.25 / 深容器 V0.24/S≤0.42 / 无彩回黑，alpha 0xF2）、`textColorsFromSwatch`（主文字提亮 V0.80~0.92 保留色相、辅助降饱和 alpha 0xE6）。
  - 鲜艳档（新，用户确认「原色直出」）：`vividContainer`＝主色原样仅统一 alpha 0xF2；`vividText`＝保留饱和度（×1.05）只把亮度抬进可读区间 0.72~0.92，辅助文字 alpha 0xE6。浅容器仍由 `isLightColor` 亮度联动自动配深字。
- 派生函数 `followText(vivid, asMain)` / `followCardBg()` / `followEntryBg()` 统一兜底：`autoSwatch==0` 时黑卡/白主字/灰辅字/`0x66101010` 入口底（失败关闭）。`applyFollowColors()` 现在连卡片底色一起刷（原来在 maybeExtractCardPalette 里单刷）；`textColorsFromSwatch`/`containerFromSwatch` 参数从 `Palette.Swatch` 改为 `int` 主色。
- 配置端：`PickStyleDropdown(colorKey, pickKey, ::value, ::update)` 复用组件（`PICK_STYLE_LABELS` 两档），插在 6 个颜色下拉下方；手选固定色时组件直接 return 不显示。Elements 诊断日志追加各档 pick 状态。

**补充（2026-10-08 晚）：主色来源（最鲜艳优先 / 占比优先，全局一项）**

- 需求：现有 `pickSwatch` 是 Palette 鲜艳桶优先（vibrant→darkVibrant→lightVibrant→muted→darkMuted→dominant），**不是全图最鲜艳也不保证占比多**——鲜艳桶可能选中封面上占比很小的点缀色。用户要「占比最多的颜色里面的最鲜艳」。
- 新键 `swatch_pick`（默认 0）：**全局一项**（用户选定，非逐项——所有跟随档共用同一主色保证色相统一）。0＝最鲜艳优先（现状），1＝占比优先。
- `pickDominantVivid`：population 前 5 的色块作候选池（覆盖主体色调、排除边角小色块），池内按鲜艳度 **S×V** 取最高。选出的主色仍走各项已有的磨砂/鲜艳推导。
- 配置端：外观页最顶上加「取色」分组（主色来源下拉）。改键进 elementSignature → 整场重建 → autoSwatch 清零重取，无缓存策略污染问题。诊断日志追加 `swatch=dominant|vibrant`。

**补充（2026-10-08 晚）：时钟「描边加粗」滑杆（4ad0ea9）**

- 用户反馈圆体字体拉满粗细仍细。解析字体 `fvar` 表（python struct 手撸）：**中等圆 wght 轴 300~700、很圆 400~800**——滑杆 900 被字体钳制，字体本身没有更粗的空间。
- 方案：新增 `clock_stroke_dp`（默认 0＝现状，0..8dp），时钟 TextClock 改匿名子类，`onDraw` 前把 paint 设 `FILL_AND_STROKE + strokeWidth` 外圈补粗；0 时恢复 `FILL` 防残留。值 create 时读定，配置变化走 elementSignature 整场重建。**滑杆重映射不做**（会破坏存量粗细配置），描边从 0 起步正合「现在的变成 0，还要更粗」。

**修正（2026-10-08 晚）：跟随封面三连修复（d2847b5）**

1. **触发条件 bug**：`maybeExtractCardPalette` 原来开头 `cardBgConfig != 0 就 return`——播放器底色手选时，**其他跟随档（时间/日期/签名/入口）整个拿不到色**（真机对账确认）。改为 `anyFollow()`：任一处跟随即跑管线。
2. **文字跟随不是黑白灰切换**：原实现按容器亮度切深/白字，跟专辑色无关。改为 `textColorsFromSwatch`：主文字（时间/入口）= 专辑主色提亮（V 0.80~0.92，保留色相），辅助（日期/签名）= 降饱和弱化版（alpha 0xE6）；无彩封面回白/灰。
3. **缓存键从封面引用改曲目 key**：该设备媒体源**每 2 秒交新 Bitmap 实例**（引用比对失效，与 WallpaperCoverPush 同款坑），原实现每 2 秒重跑一次 Palette。改按 `title|artist|尺寸` 缓存，同曲零开销、换歌立即取。字段 `autoTextMain/autoTextSub` 随取色一次算好；坑：lambda 里局部 `main` 遮蔽 Handler 字段导致 `main.post` 编译错，改名 `mainColor`。

**补充（2026-10-08）：配色统一 + 外观页按锁屏顺序重排**

- **统一文字色档**：时间/日期/签名/通知入口的文字颜色共用一套 7 档（`TEXT_COLOR_VALUES`）：跟随封面 / 白 / 黑 / 浅灰 / 暖黄 / 天蓝 / 粉（值 0＝跟随封面，按取色容器亮度联动：主文字〔时间/入口〕深或白、辅助文字〔日期/签名〕灰阶）。旧 `CLOCK_COLORS` 移除。取色回填时 `applyFollowColors()` 一次刷新所有跟随档；跟随标志在 `create()` 读定，改配置走整场重建。
- **通知入口**（新分组）：文字颜色（统一 7 档）+ 胶囊背景（播放器底色同款 7 档，键 `entry_color`/`entry_bg`，背景默认 `0x66101010` 半透明黑≈系统观感）；`notificationButton` 改自绘 `GradientDrawable` 胶囊（`setAllCaps(false)`）。
- **外观页分组顺序**＝锁屏自上而下：日期 → 签名 → 时间 → 专辑封面 → 播放器 → 通知入口 → 背景（原「时间」在最前，已移动）。

**补充（2026-10-08）：底色「跟随封面」档（动态取色）**

- 外观页底色下拉第 1 档「跟随封面」（`card_bg=0`）：用 `androidx.palette`（纯 Java，随模块 dex 进 SystemUI）从封面提取主色，压成**低饱和磨砂容器色**——主色偏亮做浅容器（V 0.82 / S≤0.25 → 亮度联动自动配深字）、偏暗做深容器（V 0.24 / S≤0.42 → 白字）、近乎无彩的封面回历史黑。色相保留、饱和度压低，风格与手选 6 档一致。
- **管线**：`applySnapshot` 只在封面引用变化时触发 → `paletteExecutor`（daemon 单线程）取色（Palette 数百 ms，绝不占主线程）→ `main.post` 回填 `applyCardColors()`。**按封面引用缓存**（换歌才重取）；场景重建后同曲命中缓存**同步**回填，不闪兜底黑。取色任何异常退回历史黑（失败关闭）。手选其余 6 档时取色管线完全不跑。
- 配套重构：`buildPlayerCard` 的配色全部抽进 `applyCardColors(int bg)`（可回填）；小封面底、卡内三枚装饰图标提为字段。

**补充（2026-10-08）：顶部日期行 + 自定义签名行（两行共存、各自独立开关）**

- **布局与间距**：`content` 里自上而下为 日期行 → 签名行 → 时钟 → 封面 → 播放器（签名在日期**下方**，用户指定），沿用「距上一个元素」链：第一行距内容区顶（日期开用 `date_spacing_dp`，否则签名开用 `sign_spacing_dp`，都关回归老的 `clock_spacing_dp`）；时钟的「距顶部」在任一行显示时变为「距上一行」。**两行都关时布局与老版本逐像素一致**。
- **农历**：`android.icu.util.ChineseCalendar`（系统自带，无第三方库）。干支年 = `EXTENDED_YEAR % 60`（epoch 2637 BC＝甲子，序号 −1 后取模映射天干地支）；月名「正二…冬腊」+ 闰月前缀，日名「初一…三十」。文本按「天」缓存（`applySnapshot` 的 2 秒节拍里只做一次 epoch-day 整数比较，跨 0 点 ≤2 秒自动翻），农历换算任何异常都退化为纯公历，绝不影响主场景。
- **签名通道**：`Config` 新增字符串值元素表 `TEXT_ELEMENT_DEFAULTS`（签名默认空串），`elementKeys()` 把它并入 `/elements` 导出（value 列本就是字符串形式，**列投影不变**）；SystemUI 侧 `Config.elementText()` 单独一次查询取回。签名正文拼进 `currentElementSignature()`，改签名 → 整场重建。App 端输入框**三重保存**：输入停顿 800ms 防抖自动写盘（`LaunchedEffect(text)` + `delay`）＋ 页面退出 `onDispose` 兜底 ＋ IME 确认——**首版只挂「失焦保存」，用户输完直接退出页面导致签名丢失**（真机排查：`content query` 显示 `signature_enabled=1` 但 `date_signature` 为空），教训：**编辑类配置不能只依赖焦点事件落盘**。

**补充（2026-10-08）：播放器卡片底色 + 点小封面跳音乐 App**

- **底色**：新增 element 键 `card_bg`（`Config.ELEMENT_DEFAULTS` 默认 `0xF2181818`＝历史硬编码黑，老配置外观不变）。外观页「播放器」分组现在共 **6 档 M3 风格 tonal 配色**（`CARD_BG_VALUES`，与标签一一对应）：深色 `0xF2181818`（原硬编码黑）/ 墨蓝 `0xF21E2A3C`（M3 深色容器调）/ 浅色 `0xF2C7C7CC`（中性浅灰）/ 蓝灰 `0xF2B6C1D6`（系统浅色磨砂同款，用户截图取色）/ 淡紫 `0xF2D8CEF4`（M3 secondaryContainer 系）/ 淡粉 `0xF2F4CEDA`（M3 tertiaryContainer 系）。**浅色档不只是换背景**：`buildPlayerCard` 里按底色 sRGB 亮度（`isLightColor`，alpha 折算，阈值 0.5）联动标题/副标题/时间/图标/进度条配色——浅灰底配白字根本看不清，所以整套联动换深字。`card_bg` 进 `elementSignature`，改完自动触发整场重建，无需额外处理。加新档位只改 Compose 端 `CARD_BG_VALUES`，SystemUI 端亮度判定是通用的。
- **点击跳转**：`cardArt` 挂 `OnClickListener`（`launchMusicApp()`）：从 `shown.controller` 拿当前媒体会话包名 → `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK` → `startActivity`。没有会话/包名起不来时静默忽略并打日志。锁屏上点它走系统标准路径：**先弹解锁验证（bouncer），通过后直达 App**（与点锁屏通知一致，不绕过锁屏）。

### 左侧通知栏下拉：改成直接禁用该手势（当前方案）

**目标**：锁屏沉浸场景显示期间，不响应左侧下拉（通知栏），从源头避免重叠；右侧控制中心不受影响。

**为什么不再做「状态检测」**：左边通知栏是否展开，试过两个信号，都被真机证伪：

| 信号 | 为什么不能用 |
| --- | --- |
| 通知栈（`NotificationStackScrollLayout`）的可见性 | 亮屏、锁屏重排、我们自己的 `restoreChangedViews()` 都会改它 → 误判后状态永久卡住，**沉浸场景彻底消失且不恢复** |
| `notification_panel` 的可见性 | 真机日志显示**亮屏后 76ms 它自己就变 VISIBLE**（它表示「锁屏在显示、通知栏可下拉」，不是「拉下来了」）→ 每次亮屏把刚恢复的场景再藏掉，表现为「原生屏保 + 沉浸式闪一下」 |

**根因确实是 z 序**（这也是「左重叠、右正常」的原因）。`uiautomator dump` + 模块自打的 `Window tree:` 显示窗口根的子节点顺序为 `notification_panel` → … → `control_center_container` → `keyguard_root_view`，自绘层挂在最后一个子节点（画在最上）→ 压在左侧通知栏之上，但在右侧控制中心之下。

**当前做法：在通知面板的触摸入口吃掉左半屏手势。** 自绘层压在面板之上且不消费整块触摸，所以手势仍会派发到面板；在面板的 `dispatchTouchEvent` / `onInterceptTouchEvent` / `onTouchEvent` 上挂 Xposed 钩子，沉浸场景显示期间对**左半屏的 ACTION_DOWN** 直接返回 false（当作没处理）→ 通知栏不会展开。右侧控制中心在独立容器 `control_center_container`，不经过这个视图，所以照旧可用。

**踩过的坑（不要再犯）：钩继承来的方法必须判 `thisObject`。** `NotificationPanelView` **没有覆写** `dispatchTouchEvent`，`panelClass.getMethod("dispatchTouchEvent", …)` 返回的是**框架基类 `android.view.View` 的实现**。钩它等于给 SystemUI 里**所有 View** 装钩子：第一版没有 `thisObject` 判定，结果把左半屏的触摸**全部**吃掉——播放器的 ◀ 播放/暂停（横跨屏幕中线左侧）和「展开通知」按钮（居中）一起失灵。修法是在 `beforeHookedMethod` 里第一行就 `if (hook.thisObject != leftShadePanel) return;`，只处理面板自己。`findTouchMethod()` 会优先取类自己声明的方法，但退回继承实现是常态，所以这个判定**必须**保留。

同理，**不要把这些放进触摸路径**：曾经为了找可靠信号，在 pre-draw 守卫里每帧读 12 个视图属性 + 反射调用 7 个 getter（`Shade watch:` / `Expansion getters:`），既耗 CPU 又诱导后人用不可靠信号；手势拦截落地后已整体删除。

判断「场景正在显示」用的是模块自己的状态（`sceneShowing()` = 场景存在 ∧ 未 suspend ∧ 锁屏周期），**不依赖任何系统视图的可见性**。

**`sceneShowing()` 里绝对不能带「播放器页在前」（`playerSceneVisible`）**：展开通知时它会被置 false，但场景（模糊背景 + 时钟）仍在锁屏上，此时左下拉照样会拉出原生通知面板 → 原生锁屏时钟和我们的时钟就重叠出现了（真机踩过，日志表现为展开期间 `Left-shade gesture consumed` 一条都没有）。判据必须是「场景是否还占着锁屏」。

**代价（已知并接受）**：左半屏起始的「上滑解锁」也会被吃掉。右半屏、指纹、电源键不受影响。因此配置里带一个开关：

- 外观 → 播放器 → **锁屏禁止左下拉**（`Config.BLOCK_LEFT_SHADE`，默认开）
- 开关**每次手势现读**，改完立即生效，不需要重建场景或重启 SystemUI

真机核对：

```bash
adb -s 1b3a7d8 logcat -d | grep -E "Left-shade touch block armed|Left-shade gesture consumed"
# armed    → 钩子装上了（含面板真实类名）
# consumed → 每次成功吃掉的手势，带 x 坐标与序号
```

**铁律（2026-10-07 修正）**：`suspend()` / `resume()` 的**生命周期结构**（解锁不销毁、保留实例复用、`finishSuspend` 兜底）不要动，那是「熄屏秒显 + 解锁撤层」的地基。但**撤层的时机必须靠实测校准、不能凭感觉**：曾经为了「好看」给背景层加过 120ms 淡出，实测反而让桌面被多盖 152~411ms（见下一节）。

### 解锁时的撤层时机：别让盖子盖住已经出现的桌面

`background` 挂在窗口根（`DecorView` index 0）是为了盖住原生壁纸，但**它盖住的时长必须和「桌面什么时候真的出现」对齐**，否则就是用户反馈的「上滑进桌面卡一下」。

系统侧与模块侧的时间戳都在日志里，**直接对齐即可，不需要录屏**：

```bash
adb -s 1b3a7d8 logcat -v time | grep -E "keyguardGoingAway|showSurfaceRobustly mWin:Window\{[0-9a-f]+ u0 com.miui.home|/MeloLock\(|native layers handed back"
```

真机实测（2026-10-07，4 次解锁结论一致）：

| 事件 | 相对 `keyguardGoingAway` |
| --- | --- |
| 桌面窗口 `wms.showSurfaceRobustly Window{com.miui.home/Launcher}` | **+92 ~ +184ms** |
| 模块 `suspend reason=predraw-keyguard-unlocked` | **+301 ~ +318ms**（pre-draw 守卫在等窗口重绘） |
| `native layers handed back at t=+Nms` | 再 +151 ~ +420ms |

也就是：**桌面早就可见了，模块那块不透明层还要多盖 152~411ms**。`finishSuspend` 的 `postDelayed(150)` 只是其中一段，**背景层那 120ms 淡出同样在遮挡**。

现在的做法：**`suspend()` 里背景层直接 `setVisibility(GONE)`，不做淡出。**
依据是 `suspend()` 只在 `keyguardLocked()==false` 时触发（`predraw-keyguard-unlocked` / `user-present` 两条路径都是），那一刻解锁事务已经在跑、桌面已经可见，撤层不会露任何壁纸。硬隐藏仍由 `finishSuspend` 兜底，绝不留下残影。

**残留延迟已解决（2026-10-07）：改用系统侧解锁信号驱动**

上面那 120~367ms 的残留，根因是「只能靠 pre-draw 守卫发现解锁，它比 `keyguardGoingAway` 晚 277~491ms」。
现在多加了一个**系统侧信号**（`HookEntry#hookUnlockSignal`）：挂 `KeyguardStateControllerImpl#notifyKeyguardGoingAway`。

- 它在 **SystemUI 进程内**，**不需要扩 Xposed 作用域**。AOSP 里 `KeyguardViewMediator#keyguardGoingAway` 本来就是调它去通知所有 Callback 的，所以和挂在 `keyguardGoingAway` 是同一时刻。
- 实测我们的回调 `45.699` 比系统的 `keyguardGoingAway, transition`（`45.709`）**还早 10ms**，而守卫要到 `46.182`（晚 473ms）。
- 拿到起点后背景层不再硬切，而是 **Hold 130ms → Fade 240ms**（两个数取自实测的「桌面窗口 +111~205ms 可见」窗口）：先压住"桌面还没上屏"的那一瞬，再让桌面从盖子底下渐显出来。

**两个必须记住的坑**

1. **`notifyKeyguardGoingAway` 会跟着息屏/Doze 一起发，不是只在真解锁时发。** 抓到过一次：信号之后 3ms 就是 `render skipped: display-off`，而锁屏还在 —— 盖子被撤掉，锁屏重现时就只剩原生壁纸。所以 `onUnlockStarting()` 里的 `interactive()` 闸**不能省**。
2. 另加误触发兜底 `restoreCoverIfStillLocked`：+800ms 回头核对一次，只要 `suspend()` 没被叫到（= 守卫确认过 keyguard 真的不锁了）就说明这次不是解锁，把盖子放回去。

**诊断**：`Unlock signal hook armed on <类名>` / `Unlock signal from system: <类>#<方法>` / `Unlock signalled (keyguardGoingAway); cover holds …`。
hook 装不上时会打 `none on <类名> (methods=N, GoingAway*=…)` —— 直接告诉你这个 ROM 上真实的方法叫什么（本机就是这么从
`KeyguardViewMediator`(无) 找到 `statusbar.policy.KeyguardStateControllerImpl#notifyKeyguardGoingAway` 的）。
**铁律仍然成立**：`suspend()` 里那个硬隐藏是兜底，别为了"好看"往里加淡出。

**补充（2026-10-07）：解锁时不再「立刻交还原生层」，改为延迟到 `restore()`。**
系统自己的解锁退场动画在 `keyguardGoingAway` 之后还要跑 ~350ms：

```
07.089  我们: Unlock signalled; cover holds 130ms then fades 240ms
07.366  系统: updateKeyguardWallpaperState: show=false anim:true     ← 系统开始把锁屏壁纸动走
07.654  我们: native layers handed back（旧行为）                     ← 交还动作落在动画进行中
07.715  系统: updateKeyguardWallpaperStateAnim onAnimationFinished
```

三次解锁的「交还 → 系统动画结束」只差 **3 / 61 / 62ms** —— `restoreChangedViews()` 会在动画进行中
把原生壁纸层置回 `VISIBLE`，和系统「正在藏它」打架，用户看到的就是「解锁时闪一下原生锁屏」。
现在 `finishSuspend` **不交还**（那些层都在锁屏根里，而锁屏根此时已被系统置 `GONE`，继续按住没有副作用），
交还给 `restore()`（模块关闭 / 场景销毁）。日志改成
`native layers stay hidden (hand-back deferred to restore); t=+Nms native[keyguardRoot=… wallpaper=… clock=…]`。

最后那段 `native[…]` 是**诊断普查**：排查「闪原生锁屏」时最关键的一问是**闪的只是壁纸层，还是整个原生锁屏**
（时钟/图标都出来）—— 只有壁纸＝交还时机问题；连时钟都出来＝另有路径在放原生内容。
用可见性 + alpha 打出来，比让用户回忆「看清没看清」可靠。

### 亮屏第一帧必须是我们的界面（暗期预显）

`suspend()` 之后场景是 `GONE`，而 `resume()` 只能等两件事：`SCREEN_ON` 广播（**送达时面板已经亮了**）或亮屏后第一帧 pre-draw。于是「息屏 → 快速亮屏」中间那几十~两百毫秒露出的就是**原生锁屏** —— 用户反馈的「息屏快速亮屏又出现原生」。

改法：`SCREEN_OFF` 之后再等 `SCREEN_OFF_PRESHOW_DELAY_MS = 220ms`（**面板黑透之后**）执行 `preShowForWake`，把场景的可见性提前摆回可见 —— 亮屏**第一帧**就是我们的界面。

三个刻意的取舍：

- **必须等 220ms**：`SCREEN_OFF` 广播送达时面板还在跑熄屏动画，那会儿改可见性会跟动画抢（这正是之前被迫给守卫加 `interactive()` 闸的原因）。面板黑透之后再动，既看不见也不会撞动画。
- **只摆可见性，不调 `resume()`**：`resume()` 会走 `showMusic()`，而它会把进度 ticker 启动起来 —— 熄屏期间每 500ms 唤醒一次，纯耗电。入场动画与 ticker 照样留给亮屏时的 `SCREEN_ON` 分支（`playerSceneVisible` 仍为 true，所以 `showMusic()` 走"无动画"路径）。
- 若亮屏后 keyguard 没锁（看到的是桌面），`SCREEN_ON` 分支走 `restore("wake-unlocked")` 把预显收回去。

**诊断字段（重要）**：`state()` 现在带 `cover=` / `front=`（`V1.00` = 可见且不透明，`G0.00` = 已移除，`-` = 对象不存在）。
**排查「露原生」只看 `fg/bg=true` 是不够的** —— 那只说明对象还在，真正决定露不露的是可见性。

日志：`Pre-showing scene while display is dark so the first lit frame is ours` / `Cover was not visible while the scene shows; restoring it`。

**补充（2026-10-07，第 1 轮）：预显的触发点从「固定延时」换成「系统唤醒信号」。**
一次性 220ms 的延时要赌用户的按键节奏：**快速连按两下电源键**时时序会反过来 ——
实测 `KeyguardViewMediator` 的唤醒回调(08.925) 比 `onScreenTurnedOn`(09.165) 早 240ms，
而我们的 `SCREEN_OFF` 广播反而晚到 53ms、预显比亮屏晚 50ms → **前几帧就是原生锁屏**。
现在在 `HookEntry.hookWakeSignal()` 里钩系统自己的唤醒回调（预显是幂等的，多挂几个无害）：

| 真实落点（dex 解析核实） | 相对亮屏 |
|---|---|
| `KeyguardPanelViewController$wakeObserver$1#onStartedWakingUp` | 早 ~224ms |
| `KeyguardViewMediator#-$$Nest$mhandleNotifyStartedWakingUp` | 早 ~240ms |

**踩坑（重要）**：日志里那两行 `onStartedWakingUp` / `handleNotifyWakingUp` **只是 Log 文案，不是方法名** ——
第一版按它们精确匹配，四个候选类全部落空（真机上 `KeyguardViewMediator` 连 `handleNotifyWakingUp`
这个方法都没有）。**正确做法**：用 dex 解析「谁引用了这段字符串」，才能拿到真名
（`-$$Nest$m…` 是 Kotlin/R8 生成的 nest 访问器，说明该方法被内部类调用）。
`WAKE_SIGNAL_CLASSES` 现在按**名字模糊匹配**（小写含 `wakingup`）+ 覆盖匿名内部类 + 接口 default 方法，
并把**实际挂上的方法名**打进日志（`Wake signal hook armed on <类> -> <方法…>`）。

**踩坑**：把 `preShowForWake` 写成字段初始化器里的 lambda 时，用**简单名**引用后面声明的字段（`foreground`/`background`/`main`…）会被 javac 判为「非法前向引用」。
本项目的规矩：**改方法引用 `this::runPreShowForWake`**，把实现体放进普通方法。

**补充（2026-10-07，第 2 轮）：唤醒信号触发还不够，预显必须「同步」摆可见性。**
第 1 轮装上后用户复现：**「快速开屏还是闪原生壁纸，只有壁纸在」**。日志把根因钉死了（22:17:34 一次完整快开屏）：
```
34.505  Wake signal (KeyguardViewMediator) → 我们 main.post(preShowForWake)（本应 ~34.521 跑）
34.643  SCREEN_OFF 广播到达 → main.removeCallbacks(preShowForWake)  ← ✗ 把上面提前排好的预显撤销了！
                                           + postDelayed(220) → 排到 34.863
34.755  onScreenTurnedOn（面板亮）
34.762  KeyguardService 唤醒信号 → post pre-show
34.781  Pre-showing scene                          ← ✗ 比亮屏晚 26ms，中间那几帧就是原生壁纸
```
两处叠加的时序错：
1. **`SCREEN_OFF` 的 `removeCallbacks(preShowForWake)` 把唤醒信号提前排好的预显撤掉了** ——
   快速开屏时 `SCREEN_OFF` 晚于唤醒信号到达（顺序反了），那次 remove 正好删掉最早的预显。
2. **即便没被删，`main.post` 的 runnable 会被唤醒那 ~250ms 的主线程重活饿死**，等到亮屏后才跑。

修法：
- `SCREEN_OFF` 里**删掉 `removeCallbacks(preShowForWake)`**，只留 `postDelayed(…, 220)` 作兜底
  （万一唤醒信号没来，仍按原延时摆回；它不再能撤销唤醒信号排好的预显）。
- `onSystemWakingUp()` 改成：**主线程上同步调 `applyPreShow()`**（钩子跑在 MIUI 唤醒序列最前端、主线程还没被
  占满时，同步摆可见性才能抢在亮屏第一帧之前）；非主线程（个别 binder 回调）才退回 `main.post`。
  整个调用包 `try/catch`——失败就当没预显（退化成原生），不崩 SystemUI。
- `applyPreShow()` 抽出 `runPreShowForWake` 的可见性逻辑，**额外在预显时把原生壁纸层再按一次**
  （`hideNativeWallpaperLayers(root)` + `hideNativeClockLayers(root)`）：唤醒时 MIUI 会把
  `keyguard_background_layer` / `wallpaper_des` / AOD 超级壁纸重新置回可见，必须提前兜一道，
  否则「我们的封面还没盖上去、原生壁纸先露出来」正是用户看到的「只有壁纸」。
- 验证判据：修复后 `Pre-showing scene` 必须出现在 `onScreenTurnedOn` **之前**（面板还黑着时），
  且 `Wake signal pulled the pre-show forward … suspended=true` 应在亮屏前 ~250ms。

**第 3 轮（2026-10-07，用户拍板方向）：第 1/2 轮都是「抢那一帧」的治标，根因是 z 序错配。**
用户原话：「改的不对，应该是要提前画好这个页面，就可以直接覆盖」。
- 旧结构：`background`（全屏模糊封面）挂在 `windowRoot` 第 0 位（最底层）；原生锁屏壁纸
  `keyguard_background_layer` 同在 `root`（锁屏根）里、且层级在 `background` **之上**。
  于是原生壁纸一重显就盖住我们、从 `foreground`（播放器 UI，有透明缝隙）漏出来 —— 即「只有壁纸」。
  之前靠「唤醒瞬间切可见性 + 躲 MIUI 重显」永远是在和 z 序对抗，治标不治本。
- 修法（结构性）：`create()` 里把 `background` 从 `windowRoot.addView(background, 0)` 改成
  `root.addView(background, root.indexOfChild(foreground))`——**插在原生壁纸之上、`foreground` 之下**。
  我们的全屏封面现在提前画好、且层级压住原生壁纸，解锁/亮屏时直接覆盖，不再依赖任何可见性时序。
- 桌面（上滑解锁）的覆盖不受影响：`background` 在 `suspend()`/`finishSuspend()` 里照常立即 `GONE`，
  桌面由 `foreground` 跟随系统退场动画淡出揭示（与前景层同理，不残留到桌面）。
  上滑露壁纸那条旧约束（background 挂窗口根盖桌面）已不再适用——那时 background 还在窗口根最底层，
  现在移进 root 后由 root 跟随系统 dismiss；若真机复现上滑露桌面，再补一层窗口根纯色兜底。
- 第 1/2 轮的唤醒预显（同步 `applyPreShow`）保留：它让 `foreground`/`background` 在亮屏前就 VISIBLE，
  配合 z 序修正等于「可见且压住原生壁纸」双保险。

**第 4 轮（2026-10-07 深夜，用户复现「还是闪」后的完整收尾）——两类根因叠加，且上一轮改动曾被 IDE 旧缓冲区覆盖：**

1. **「快速开屏还闪」＝唤醒守卫误拦 + 固定延时赌输**。日志（22:43:43 一次快开屏）：
   `唤醒信号 43.666 → 我们 post 预显 → SCREEN_OFF 43.731 的 removeCallbacks 把它撤掉 →
   面板实际亮起 43.846（Unblocked screen on after 582ms）→ 预显 43.889 才跑`。
   两个错：① `SCREEN_OFF` 里的 `removeCallbacks(preShowForWake)` 会撤掉唤醒信号提前排好的预显
   （快开屏时 SCREEN_OFF 晚于唤醒信号到）——删掉它，只留 `postDelayed(220)` 兜底；
   ② `onSystemWakingUp` 的守卫里有 `lockscreenCycle`——快速开屏时它还挂着上一轮 USER_PRESENT
   留下的 false（SCREEN_OFF 广播晚到），把最早一次预显拦掉了。改成只认 `keyguardLocked()`（权威），
   并在预显前把 `lockscreenCycle` 就地扶正；且**主线程上同步调 `applyPreShow()`**
   （`Looper.myLooper()==main.getLooper()` 时直接调，不走 post——唤醒那 250ms 主线程重活会把
   post 的 runnable 饿死到亮屏后）。
2. **「解锁还闪」＝我们自己在系统编排前掀了盖子**。`dumpsys window` + 日志证实：解锁过渡时系统
   **主动把壁纸窗口拉起来显示**（`wms.showSurfaceRobustly … com.miui.miwallpaper.wallpaperservice.ImageWallpaper`，
   keyguardGoingAway 后 ~53ms）——它是**独立窗口**，不在任何视图树里，我们普查/隐藏都碰不到它。
   旧版 `suspend()` 在解锁一开始就 `foreground 淡出 120ms + background 立即 GONE`、
   `startUnlockCoverFade()` 又自定节拍（压 240ms 淡 130ms）——都比系统编排快，淡出/摘层期间
   正好露出系统正在过渡展示的壁纸窗口。改法（贯彻「提前画好、直接覆盖」）：
   **解锁交接期间完全不动可见性**——`suspend()` 只停 ticker、标记状态，`startUnlockCoverFade()` 只留
   误触发兜底；我们的层全是 keyguard 根的子视图，系统收 keyguard 根时自然一起收走（原生节奏）。
   硬隐藏兜底 `finishSuspend` 延时 150→600ms，等编排走完再摘。
3. **解锁的完整定稿（hold 280 / fade 90，别再改回去）**：`hands-off`（全程不动、交给系统）虽然彻底压住壁纸，
   但实测 keyguard 根要到 **+533ms** 才 INVISIBLE，而桌面窗口 **+205ms** 就上屏 —— 我们的封面会多压住桌面
   **约 330ms**，正是用户以前抱怨的「上滑进桌面卡一下」。所以正确节拍是：
   **0~280ms 完全不动（不透明、压死系统拉起的壁纸窗口）→ 280ms 起 90ms 快速淡出（此时桌面已上屏，
   渐显出来的是桌面而不是壁纸）→ 370ms 摘层 GONE**。前段「不动」是「提前画好直接覆盖」，
   后段「快速交接」避免挡桌面；两者缺一不可。淡出必须**前景与背景一起淡**（旧版只淡背景、
   前景在 `suspend()` 里单独淡，两个节拍对不齐）。摘层用 `hideSceneAfterUnlock`（Handler 兜底，
   不挂动画回调），且 **`suspend()` 不许 cancel 它**——否则会在桌面留一层「alpha=0 仍 VISIBLE」挡触摸。
   自证日志：`unlock cover removed at t=+Nms after goingAway; native[...]`。

4. **事故：上一轮两处编辑被 IDE 旧缓冲区覆盖**（`LockScreenOverlay.java` 在 Android Studio 里开着，
   外部保存把盘上文件写回旧内容）。表象是「装了新包行为没变」——实际跑的是半套代码。
   **验证手段**：改完必 `grep` 逐项核对盘上内容再构建；装机日志要能自证新行为（如 `hands off` 日志行）。
   红线「不要并行编辑此文件」再+1：**测试前不要在 IDE 里保存这个文件**。

### 手电筒 / 相机转场：改 MIUI 那层遮罩的背景（**不新增视图**）

**现象**：点左下角手电筒或右下角相机时，转场过程中会看到**原生壁纸**一闪（进入与退出两个方向都会）。

**根因（日志 + 离线 dex 解析双证）**：MIUI 快捷方式转场时 `com.android.keyguard.shortcut.MiuiShortcutController`
在 `onFullscreenAnimationStart` 分支调 `KeyguardPanelViewController.hideWindowViewByOccludedAnim()`，
而后者本体只有一句 `Folme.useAt(notificationShadeWindowView).state().to(0f, hideEase)` —— 把整个
`NotificationShadeWindowView`（窗口标题 `NotificationShade`，**type=2040**）alpha 动到 0。我们的背景层
（挂窗口根 index 0）与前景层（挂锁屏根）都在这扇窗里，于是一起消失，**露出最底层的系统壁纸 surface**。
退出方向是同一件事反过来（shade 的 alpha 淡回来）。注意：**那段时间 `MeloLock` 一行日志都没有** ——
不是我们的代码在动。

**做法**：`ShortcutAnimBackdrop` 只做一件事 —— 把 MIUI 自己动画窗里**本来就每帧在画**的那层全屏遮罩的
background 换成我们的封面。

真机实测结构（OS3 16.03，`dumpsys`＋探针确认）：

```
Window{miui_keyguard_shortcut  type=2017(TYPE_STATUS_BAR_SUB_PANEL)}     ← 按窗口标题认
 └─ FrameLayout 1080x2400                                              ← 窗口内容视图
     └─ ShortcutOccludedAnimView 1080x2400                             ← MIUI 插件类（按类名认）
         ├─ View 1080x2400 bg=ColorDrawable                            ← **我们改它的 background**
         └─ ScaleShortcutImageView 289x289                             ← 图标放大（不动）
```

- 认窗口：钩 `WindowManagerImpl.addView`（`WindowManagerGlobal.addView` 在本 ROM 上签名对不上）按标题匹配；
- 认那层：在 `ShortcutOccludedAnimView` 子树里取「不是 ImageView、且 background 是 ColorDrawable」的子 View
  （图标是 `ScaleShortcutImageView`，289×289，不会被选中）；
- 重设时机：窗口加入时 + `onFullscreenAnimationStart`（进入）与 `onUnoccludedAnimationStart`（退出），
  各带一次 +150ms 延后重试（MIUI 铺尺寸/加子视图比回调晚一点）；
- 背景内容与 `LockScreenOverlay.applyBackdrop()` 对齐：风格 0 封面＋遮罩原样 / 1 遮罩 ×0.55 / 2 纯色；
  封面**缩到 1/32 再拉满**（廉价模糊，等效软掉），**封面＋遮罩预合成到一张 1/4 分辨率位图**交给 `BitmapDrawable`。

**为什么这样就不卡**：这层是 MIUI 自己的，**每帧本来就在画**；我们只是把「一张纯色铺满」换成「一张位图铺满」，
每帧绘制成本相同（都是 1 次全屏填充）。前一版之所以卡，是**新增了一层自己的视图**，那层每帧都要重画全屏内容
（`RenderEffect` 版实测转场 1 秒内连续 25 帧 41~74ms ≈ 20fps；换成廉价模糊后中位仍 38→33ms）。

**顺带白拿的三个好处**（前一版踩过的坑在结构上不存在了）：
- **显隐不用我们管**：那层归 MIUI，它自己显示/隐藏/移除窗口，我们的背景跟着走 —— 不会「忘记撤层盖住相机」；
- **不会压着 app**：我们没加任何自己的层，最多是替换了 MIUI 要画的东西；
- **失败关闭**：认不出窗口/找不到那层/取不到封面 → 什么都不做，退化成「和原生一样」（最多照旧露一下壁纸）。

**诊断日志**：`[scrim] rev=<修订串>`（**装机后先看这行**，历史上有过「源码与 APK 都是新版但进程跑旧 dex」的事故）/
`[scrim] scrim background replaced: 1080x2400|bitmap` / `[scrim] backdrop built: style=0 270x600 color=ff111827 scrim=150` /
`[scrim] scrim view not found yet`（MIUI 结构变了就会看到这行，功能静默失效但不影响原生行为）。

**已知边界**：相机**冷启动**会把 SystemUI 主线程整块堵住（实测 3.5s / 5.1s / 5.3s，`gfxinfo` 里是一个几千毫秒的单帧），
那段时间屏幕是冻住的 —— **与模块无关**（没有本功能时同样存在），本功能也治不了它。

### 切歌的空窗期：保住上一帧，不撤层


真机日志显示，换歌时 App 会先**摘掉 metadata 里的封面 bitmap、只留 URI**，约 300ms 后才补齐新封面；这段时间内会话也可能短暂不合格（playbackState 为空或非 PLAYING）。三条旧的处理会把这个过渡态当成「播放停了」：

| 旧行为 | 后果 |
| --- | --- |
| 封面 URI 解码失败就 `lastReady = null` 并下发 `onMedia(null)` | 后续 refresh 全部落到「无缓存」，最后被判成无会话 → 撤层 |
| 会话一不合格立刻下发 `onMedia(null)` | `render()` 走 `restore("render-no-session")`，**露原生锁屏** |
| 会话不合格时把 `current` 置空（注销回调） | 新封面到达时没人唤醒，只能等下一次「活动会话变化」事件 |

现在统一在 `MediaSource` 里收敛处理：

1. **封面解不出来 ≠ 播放停了**（SystemUI 常常读不到 App 给的 `content://` 权限或私有文件）：保留上一帧继续显示，超时也一样保留，等 App 补齐；
2. **会话失去给 1500ms 宽限期**（`SESSION_GRACE_MS`）：期间继续交出上一帧；真的暂停另有分支保持画面；
3. **空窗期按包名继续跟踪会话**（日志 `Empty gap detected; keep tracking …`），保住 `MediaController` 回调，新封面一到就立刻刷新；
4. **超时之后也不再撤层（2026-10-07）**。旧实现在 `render()` 的 `snapshot == null` 分支里只认「仍在播放」，否则 `restore("render-no-session")` 把**整个场景**撤掉 → 原生锁屏连原生壁纸一起原样露出（实测 18:13:14 撤层、18:13:35 才重建，那时用户已经回到桌面了，中间整段锁屏都是原生界面）。现在改成**锁屏上只要有最后一帧（`shown != null`）就保留场景**，撤层的理由只剩三条：不在锁屏（`render-keyguard-unlocked`）、模块关闭、以及**从来没拿到过一帧**。随之删掉整套 `artworkFallback` 机制（它 2.5s 后的 `restore("artwork-timeout")` 也是执行者之一）。
   **行为取舍（用户已确认）**：音乐 App 被彻底关掉时，锁屏会一直留着最后一帧封面，而不是退回原生锁屏 —— 与既有的「暂停不撤层」是同一套哲学。能走到那一行就说明 keyguard 此刻是锁着的（桌面/解锁路径在上面已经 `restore`），所以不会把场景漏到桌面上。

对应日志：`Session unavailable … keeping last frame up to 1500ms` / `Empty gap detected` / `Media ready from bitmap title=<新歌>`；命中第 4 条新规时会看到 `render skipped: last-frame-kept`。**验收标准：切歌全程不应出现 `restore reason=render-no-session` 与 `create()`。**

### 播放器页 ↔ 通知页的切换动效（共享元素）

两页之间有两个"播放器"：自绘的播放器卡片，和通知列表里的原生媒体通知卡。以前 `showNotifications()`
只是 `show(notifications)` 让通知**瞬间满不透明出现**，而播放器还在跑 220ms 的淡出 → 两者并列，
就是用户看到的"通知先出来、播放器还没消失"。

现在改成一次**共享元素的位移过渡**：

1. **`measureSwapOffset()`**：取通知列表里第一张高度够大的卡片（通常是原生媒体卡）与自绘播放器卡片的
   屏幕坐标差，**上限 `dp(96)`**；没完成布局时回退 `-dp(52)`。
   上限是后加的：实测这个偏移很容易量到 600px 以上，按原值让卡片飞过去会**穿过时钟区域**再回来，
   看起来就是「时间闪一下」。而且**测量值每次不同**（真机日志展开 `-605px` vs 返回 `-228px`），
   所以展开时量一次存进 `lastSwapOffset`，返回时复用它——两个方向必须对称。
2. **展开**：播放器卡沿该位移滑向目的地，`alpha → 0`、`scale → 0.94`（220ms）；封面走 35% 位移（180ms）；
   通知栈以 `alpha 0 → 1`、18% 位移**延迟 70ms** 淡入（210ms），期间给通知栈开硬件层
   （`beginNotificationsLayer()`，300ms 后 Handler 兜底拆除）。
3. **返回**：通知**直接收起，不做淡出**；播放器从目的地滑回原位伴 `scale 0.94 → 1`（延迟 40ms、240ms）。

三个"看起来不对"的坑：

- **返回时不要淡入 `foreground`**（真机反馈「切回播放器时间闪一下」的**真凶**）：前景（含时钟）
  在通知页一直是显示的，旧代码把它从 `alpha 0` 淡入 180ms，等于让时钟先消失再回来。只让封面和
  卡片滑进来就够。
- **返回时通知不做淡出**：淡出层正好盖在时钟区域上，那一层任何合成抖动看起来都是「时间在闪」。
- **`bringToFront()` 不要每帧调**：pre-draw 守卫每帧调用它会让窗口根每帧 `requestLayout`，
  是切页掉帧的实打实来源。现在走 `ensureOnTop()`，只有确实不在最上层时才动 z 序。

另一个独立的泄漏（用户反馈"桌面左下拉会露出原屏保时间"）：`suspended`（场景实例保留但置 GONE）期间，
系统在**桌面下拉通知栏**时会把原生锁屏时钟重新显示出来。所以 suspended 分支里**继续按住原生时钟层**
（`hideNativeClockLayers`），但**绝不碰壁纸层**（解锁动画要靠它，隐藏会露黑底）。

日志：`Page swap: to notifications, shared offset=<n>px` / `Page swap: back to player, shared offset=<n>px`。
**两个值应该一致**（有缓存）；一直等于回退值（-52dp 附近）说明测量没生效。

### 改配置后必须重启 SystemUI

这是本项目最容易误判成 bug 的地方：**Hook 代码是注入 SystemUI 进程执行的，装完新 APK 不重启 SystemUI，跑的还是旧代码。**

2026-10-06 实测案例：外观页改完字号/粗细/封面缩放后，锁屏上「只有圆角生效，其他都不行」。查下来 `content query` 两条通道都返回了正确的新值，问题在于 SystemUI 进程启动时间早于 APK 安装时间，仍在跑不认识新键的旧覆盖层代码——旧代码只读 `corner_radius_dp`，所以正好只有圆角生效。

排查这类问题先看两条命令：

```bash
# 进程启动时间是否晚于 APK 安装时间
adb -s 1b3a7d8 shell "ps -A -o PID,ETIME,NAME | grep -i com.android.systemui"
# 覆盖层每次创建都会打印本次实际取到的参数，有这一行才说明跑的是新代码
adb -s 1b3a7d8 logcat -d | grep "Elements: clock="
```

**本机怎么重启 SystemUI**（2026-10-06 实测，从 adb 就能做，不需要 root）：

```bash
adb -s 1b3a7d8 shell am crash com.android.systemui    # ← 有效：SystemUI 会立刻以新 PID 重启
adb -s 1b3a7d8 shell am force-stop com.android.systemui   # 无效：返回成功但进程不死（HyperOS 保护）
adb -s 1b3a7d8 shell su -c 'killall com.android.systemui' # 不可用：su 未放行 shell（SukiSU）
```

`am crash` 会让 SystemUI 进程崩掉并自动重启，实测 PID 立刻变化、模块重新注入。重启后确认这三行出现，说明模块已重新注入：`SystemUI root constructor hook installed` → `Keyguard root attached` → `Module enabled=true`。重启后确认 `Keyguard root attached` / `Module enabled=true` 出现，说明模块已重新注入。

**顺序很重要：先装 APK，再重启 SystemUI。** 装之前重启等于白重启。

### 时间字体的圆润与粗细

文字没有几何圆角，Android 也没有「圆角字体」API。实测本机 `MiSansVF.ttf` 与 `MiSansLatinVF.ttf` 只有 `wght` 一个可变轴，`fonts.xml` 里也没有 `sans-serif-rounded`，所以「圆润度」只能靠换字体实现。

做法是往 APK 里内置两个 OFL 开源圆体数字字体，`圆润` 下拉提供三档：

| 档位 | 字体 | 来源 | 文件 |
| --- | --- | --- | --- |
| 0 直角 | 系统字体（MiSans / sans-serif） | 系统 | 无 |
| 1 中等圆 | Quicksand（可变，wght 300–700） | Google Fonts，OFL-1.1 | `assets/fonts/clock_round_1.ttf`，122 KB |
| 2 很圆 | Baloo 2（可变，wght 400–800） | Google Fonts，OFL-1.1 | `assets/fonts/clock_round_2.ttf`，667 KB |

锁屏时间只渲染 `0-9` 和 `:`，所以这两个字体不覆盖中文也不影响显示，中文仍走系统字体。**粗细**用可变字体的 `wght` 轴实现，是 100–900 连续可调（`Paint.setFontVariationSettings`），档 0 走系统字体的 `wght`，档 1/2 走内置字体的 `wght` 区间。

字体由 SystemUI 进程在首次需要时从模块 APK 的 `assets` 解出、写入自己的缓存目录再 `Typeface.createFromFile`（`LockScreenOverlay.roundTypeface()`），每个档位全进程只解一次；任何失败都会静默退回系统字体并打日志，绝不会因为字体问题撤掉沉浸页。

为避免把 HyperIsland 的更新内容带入本项目，当前配置端已关闭启动时环境统计，并在最终 APK 中移除 `INTERNET` 权限。HyperIsland 的更新检查、预设云端下载和外部资源链接源码仍随迁入代码保留，但当前四页不会调用；APK 不声明安装权限，也不会自动安装其他 APK。

- 播放中的标准 MediaSession 提供标题、歌手、封面及上一首、暂停、下一首操作；支持封面 Bitmap 和 URI。无播放会话、封面无效、URI 读取失败时恢复原生锁屏。
- 封面显示在中间，同一封面经 `RenderEffect` 模糊并加暗色遮罩作为背景。顶部用 `TextClock` 显示时间，下方显示播放控制。
- 有媒体时收起原生时钟及通知栈；`展开通知` 按钮可恢复原生通知区域，`返回播放器` 可收起。原生解锁与紧急操作视图没有被移除，底部区域留给系统交互。
- **解锁时覆盖层淡出后保留实例**（`LockScreenOverlay.suspend()` / `resume()`），不销毁场景。锁屏重新出现时直接复用同一批视图，亮屏即显示、零重建。只有在模块关闭、媒体不可用、锁屏根视图分离时才真正销毁。
- 0.1.2 增加锁屏态门禁：仅在系统报告锁屏、屏幕交互中、媒体会话和封面有效时创建覆盖层；并以 `MeloLock` 标签记录钩子、视图适配和开关状态。
- 应用内开关默认关闭，保存在模块私有配置中；SystemUI 通过模块的只读配置接口读取，不需要“修改系统设置”权限。后台钩子仍须先在 Vector 中启用并以 `SystemUI` 为唯一作用域。

## 构建与安装

使用 JDK 21、Android SDK 36 和项目自带的 Gradle Wrapper。

```bash
./gradlew.bat :app:assembleDebug      # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat :app:assembleRelease    # 发布包：app/build/outputs/apk/release/app-release.apk
```

### 发布签名

Release 用仓库根的 `melolock-release.keystore`（PKCS12，10 年有效）签名，口令放在 `keystore.properties`。**这两个文件都在 `.gitignore` 里，绝不进仓库**——密钥与口令丢了就无法再给同一个应用升级，请自行另存备份。

```properties
STORE_FILE=melolock-release.keystore
STORE_PASSWORD=<口令>
KEY_ALIAS=melolock
KEY_PASSWORD=<口令>
```

`app/build.gradle.kts` 只在 `keystore.properties` 存在时才创建 `release` signingConfig；没有它时 release 构建照样能跑（回退到 debug 签名），但**那样的包不能上传 Release**。正式签名目前是 v1 + v2 + v3 全签，证书指纹：

```
SHA-1:   2B:73:26:5B:F5:0D:BA:65:76:A7:75:8C:55:23:40:CD:C5:4E:C1:F7
SHA-256: 65:7C:4D:30:52:BC:13:18:98:1E:65:F6:7E:8B:0A:DD:DE:E6:72:01:DB:AC:92:A1:86:F2:E8:58:69:53:D0:FC
```

校验方式：`apksigner verify --print-certs -v app/build/outputs/apk/release/app-release.apk`。

**Release 不开混淆**：这是 Xposed 模块，Hook 与 ROM 内部视图都靠类名/方法名字符串定位，R8 收益极小、风险不小。另外迁入的 HyperIsland 多语言资源里有第三方库遗留的 `ExtraTranslation`，所以 `lint.checkReleaseBuilds` 关掉了，否则 release 会被它们拦住。

Windows 上 `:app:dexBuilderDebug` 偶尔会以 `Unable to delete directory ... project_dex_archive` 或 `desugar_graph\\...\\graph.bin (拒绝访问)` 失败：这是杀毒/索引进程仍占用刚生成的 `.dex`，不是代码问题（Kotlin 与 Java 编译此时已通过）。删掉被占用的中间目录后重跑即可，必要时降并发：

```bash
rm -rf app/build/intermediates/project_dex_archive app/build/intermediates/desugar_graph
./gradlew.bat --no-daemon --max-workers=2 :app:assembleDebug
```

增量构建时这两个目录可能残留，建议先清理再判断是否编译失败。

还有一类失败长这样，**和代码无关**：

```
Gradle could not start your build.
> ... FileNotFoundException: C:\Users\<user>\.gradle\caches\journal-1\journal-1.lock (拒绝访问。)
```

原因：`--no-daemon` 的单次 daemon 有时不会退出，一直占着 Gradle 用户目录的 journal 锁。处理方式是用 Gradle 自己的命令停掉它，再删掉残留锁文件：

```bash
./gradlew.bat --stop
rm -f ~/.gradle/caches/journal-1/journal-1.lock
```

安装：

```powershell
adb -s 1b3a7d8 install -r app/build/outputs/apk/debug/app-debug.apk
```

在 Vector 中启用“Hyper MeloLock”，只勾选 `com.android.systemui`，然后按 Vector 提示重启 SystemUI 或设备。首次启动应用时开关应显示关闭；先确认无音乐锁屏、通知、解锁和紧急操作都保持原样，再开启模块开关。之前授予的“修改系统设置”权限不再使用，可以在系统设置中撤销。

## 真机逐步验收

1. **关闭状态**：安装后保持开关关闭。锁屏、解锁、紧急拨号和通知完全使用原生界面。此阶段不要求播放音乐。
2. **单个播放器**：开启开关，使用一个提供标准 MediaSession 的音乐应用播放带封面的曲目。确认时间在上、封面在中、控制在下，背景模糊来自封面；暂停或结束后原生锁屏立即恢复。
3. **通知与安全入口**：有普通通知时确认默认收起、点击“展开通知”能看见通知、点击“返回播放器”能返回。再分别确认手势解锁、密码/指纹入口及紧急操作。
4. **跨播放器**：依次测试 Cool Music 和另一款提供 MediaSession 的播放器，包括上一首/下一首、切歌换封面、无封面、停止播放和多会话切换。
5. **故障日志**：若没有效果或 SystemUI 异常，记录 `adb logcat -d -s LSPosed:* MeloLock:* AndroidRuntime:E` 和 Vector 模块日志；不要继续扩大系统版本适配。

第一次现场验收暴露了开关故障：0.1.0 向 `Settings.System` 写入自定义键时，设备抛出 `IllegalArgumentException: You cannot keep your settings in the secure settings`，导致应用闪退，开关并未开启。0.1.1 改用模块私有配置和只读接口，不再请求 `WRITE_SETTINGS`。已在设备内验证配置开启→只读接口读取→关闭的往返测试，最终状态为 `enabled=0`；更新后已重启设备，SystemUI 进程保持运行。设置页按钮的手动点击与锁屏交互仍待验收。ADB 已确认该 SystemUI 获 `MEDIA_CONTENT_CONTROL` 权限。**锁屏交互及第 2–4 步尚未完成真机验收**，不应将其视为稳定版；仍须核实视图层级、通知入口位置及解锁/紧急操作触控区域。该设备拒绝 ADB shell 注入按键事件（缺少 `INJECT_EVENTS`），这些交互须在设备上手动测试。

第二次现场验收中，应用开关可以开启，但锁屏未变化，音乐播放后立即暂停。系统 `MediaSessionService` 在 2026-10-06 09:33:52 记录了 `callingPackage:com.android.bluetooth` 发出的 `KEYCODE_MEDIA_PAUSE`，之后播放器状态变为 `PAUSED`；这次暂停事件并非模块的自定义按钮调用。需在蓝牙关闭时复测，以区分蓝牙设备/系统服务与模块行为。锁屏覆盖层仍未验收通过。

0.1.2 安装并重启后，开关仍为 `enabled=1`，但当前 logcat 中没有 `MeloLock` 钩子日志，也没有 Vector 加载本模块的记录；同一次启动能看到 Vector 加载其他模块。下一步须核实 Vector 中本模块总开关与 `com.android.systemui` 作用域是否实际生效，再用诊断日志判断根视图和媒体条件。设备同时保持蓝牙开启且有连接，蓝牙关闭对照尚待完成。

用户随后确认：0.1.2 重启后 Vector 中本模块总开关变为关闭，手动打开后作用域仍为 `com.android.systemui`。这足以解释该次启动缺少注入日志。0.1.3 已安装到设备（versionCode 4），将 Xposed 元数据的最低 API 和作用域资源格式调整为设备上已正常加载的 legacy 模块使用的格式；应用内开关仍为 `enabled=1`。每次更新 APK 后都应重新检查 Vector 总开关，避免把未注入误判为视图钩子失败。

0.1.3 经用户重新启用 Vector 模块后由 ADB 重启，设备启动完成。SystemUI 进程日志出现 `Keyguard root attached`、`Keyguard root compatible; observing module switch` 和 `Module enabled=true`，说明模块已经在 SystemUI 的锁屏根视图上执行。启动日志也出现一次 `HyperOSKeyguardRootView` 类查找失败；后续根视图挂载成功，需在后续复测中继续留意该异常是否来自早期类加载时机。检查时 `dumpsys media_session` 显示 0 个会话，蓝牙仍开启，因此尚无法判断封面层能否显示，也未完成蓝牙关闭的暂停对照。用户正在进行带封面播放与锁屏测试。

09:52 再次播放 Cool Music 时，系统将多次 `KEYCODE_MEDIA_PAUSE` 发送给 `moe.ouom.coolmusic`，每次日志的 `callingPackage` 均为 `com.android.bluetooth`；随后 MediaSession 为 `PAUSED`，未出现封面层创建日志。此时 `bluetooth_on=1`。需先关闭蓝牙、保持一首带封面的歌处于 `PLAYING`，再判断 OS 3 视图适配是否成功。

蓝牙断开后用户确认音乐可以正常播放；双设备耳机连接是暂停的原因，暂不处理。带封面曲目处于 `PLAYING` 时，0.1.3 的诊断日志为 `Native views missing: clock=true notifications=false`：媒体层已提供有效封面，适配器却错误地只在 `HyperOSKeyguardRootView` 子树内查找通知栈。参考 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) 的架构后仍以本机日志为准；Android SystemUI 的通知栈也可能与锁屏子树分离。0.1.4 改在同一窗口的根视图里按精确类名查找通知栈，找不到仍保持原生锁屏；增加解锁时的恢复检查，并修正应用内故障恢复文案。没有复制参考项目的源码或资源。

0.1.4 已构建、安装、重新启用 Vector 并由 ADB 重启。SystemUI 日志确认精确 OS3 构建的构造函数钩子已安装，播放带封面的 Cool Music 曲目时出现 `Media overlay created from active session artwork`。ADB 锁屏截图目视确认：顶部时间、大幅封面、同一封面模糊生成的背景、下方标题和播放控制及“展开通知”按钮均显示；原生手电筒和相机图标可见。**通知展开、解锁、紧急操作触控以及第二播放器尚待用户手动验收**，仅可将视觉布局视为通过。

用户对 0.1.4 的进一步验收发现：锁屏原有时钟仍露出，手动展开通知约一秒后被媒体回调收回，切歌时曾短暂返回原生锁屏；同时希望保留系统原生媒体卡片而移除模块绘制的标题、歌手和三枚按钮。已在 0.1.5 源码中修正展开状态不随媒体回调重置、将新时间文字居中、增加 0–48 dp 的封面圆角设置，并在同一会话的下一张 URI 封面读取期间最多保留旧封面 2 秒。若媒体会话无效或新封面加载失败，仍退回原生。0.1.5 还加入只记录视图类名、资源 ID 和可见状态的诊断日志，以定位系统媒体卡片及残留时钟；不会记录通知正文。0.1.5 已构建并安装，应用内开关保持关闭。原生卡片持续播放时 `uiautomator dump` 连续两次因 `could not get idle state` 失败；待用户重新启用 Vector 并重启后用模块日志观察层级。**原生卡片复用和残留时钟修复尚未完成。**

0.1.5 经重启后用户确认“展开通知”可保持在通知页。视图诊断确定：系统原生播放器是通知栈的首个子视图 `com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView`，其中包含 `mi_media_controls`、`media_progress_bar` 和原生动作按钮；普通通知是其后的 `ExpandableNotificationRow`。锁屏主题的大号前景时钟位于独立的 `miui_keyguard_foreground_clock_container`，与 `KeyguardClockContainer` 分开。这解释了旧版隐藏一个时钟容器后仍有残影。

0.1.6 保留并平移原生媒体头至封面下方，仅收起通知栈其他直接子视图；移除模块绘制的歌名、歌手和播放按钮。自定义时间居中，封面圆角可在 APK 中调节。两层原生时钟和主题背景装饰只在沉浸页隐藏，展开通知或退出时恢复；通知入口加在通知窗根层以避免被媒体头遮挡。大量视图树诊断日志已移除。0.1.6 已完整编译并安装，**待 Vector 重新确认启用、重启和真机视觉/触控验收**。

0.1.6 真机重启后，日志曾记录媒体头向封面下方平移，但截图确认 SystemUI 随后把卡片和普通通知重新排回原位，卡片遮挡封面；此版未通过收起页验收。展开页可以看到原生时钟、播放器和通知，并有“返回播放器”入口。0.1.7 将绘制前守卫绑定到当前 `NotificationShadeWindowView` 的视图观察器，并在每次媒体更新时重新检查观察器，以应对锁屏视图重挂载或 SystemUI 覆盖位置。已完整编译并安装，**待重新启用 Vector、重启及复测；如果系统仍覆盖，需放弃直接平移媒体头。**

0.1.7 经用户启用 Vector、重启并复测，原生媒体卡片与普通通知仍覆盖在封面上。SystemUI 日志确认绘制前守卫执行，但媒体头的位移每帧变化，因此直接调整通知栈子视图的位移和可见性不足以控制此构建的布局。现改为在沉浸页临时将原生 `MiuiMediaHeaderView` 移到窗口级容器、放在封面下方，并整体收起原生通知栈；展开通知或退出沉浸页时把原生卡片放回原位置。若卡片的父视图意外变化或移动失败，立即恢复原生锁屏。此改动已编译通过，需重点检查 SystemUI 稳定性、原生卡片触控、通知切换、解锁和紧急操作。用户确认当前 `0.2.0` Compose 应用是预期的并行开发改动；已将包含本次锁屏修复的 `0.2.0` APK 安装到设备，ADB 确认 versionCode 9、配置接口 `enabled=1`。**待确认 Vector 启用、重启和真机验收。**

0.2.0 第一次真机启动时，SystemUI 日志确认有效媒体会话和封面已被发现，却在移动原生 `MiuiMediaHeaderView` 时抛出 `IllegalStateException: The specified child already has a parent`。此 ROM 的通知栈拦截了按对象调用的 `removeView(view)`，使卡片没有脱离原父容器；模块的失败关闭逻辑随即撤掉沉浸层，因此用户只看到原生锁屏且没有页面切换入口。修复改为使用已验证的直接子索引调用 `removeViewAt(index)`，并在加入窗口级容器前检查父视图已清空。若 ROM 仍拒绝移除，则保留可见的沉浸页面并记录日志，不再撤掉封面和入口。修复 APK 已编译通过，**待安装、重启并复测。**

用户确认本机 OS 3 的原生媒体头不能作为可移动组件，要求改为模块自绘、外观贴近原生的播放器卡片。当前实现已移除对 `MiuiMediaHeaderView` 的移动尝试：沉浸页在窗口顶层绘制深色圆角卡片，包含小封面、标题、歌手、上一首、暂停、下一首、进度条和时间；所有媒体数据与控制仍通过通用 `MediaSession` / `MediaController` 获取和发送。通知页继续显示系统原生播放器和通知；两页共用黑色圆角卡片、小封面、文字层级和控制行，减少切换突兀感。沉浸页每 500 ms 根据 MediaSession 的位置、速率和时长更新进度；无有效播放会话或封面时立即恢复原生锁屏。新实现不改 SystemUI 原生播放器的父子关系，Debug 构建已通过，**待安装、重启及真机验收。**

用户已在真机验收“专辑封面 + 自绘播放器”布局：封面、时间、原生风格卡片、播放进度和通知入口均可显示。该验收版本已创建本地 Git 提交 `1ce2c93 feat: add immersive album and media player card`；工作目录原先没有 Git 仓库，因此该提交是新仓库的首个提交，仅包含 `README.md`、`LockScreenOverlay.java` 和 `MediaSource.java`，未纳入并行开发的 Compose 界面文件。截图同时发现底部快捷入口区域露出原壁纸。后续修复在锁屏根视图底部插入一层相同的模糊专辑图与遮罩，位于系统手电筒/相机快捷入口下方；窗口顶层交互场景仍保留底部空间，避免遮挡快捷入口和解锁手势。Debug 构建已通过，**待安装、重启及真机验收。**

用户确认包含三段布局、全屏专辑背景和正常通知页切换的版本可用，已创建第二个本地 Git 提交 `e976aec feat: complete immersive layout and notification transition`。在此提交之后，通知切换改为 220 ms 的重叠过渡：恢复系统通知页时，自绘播放器卡片上移 52 dp 并淡出，背景场景同时淡出，通知页在下方出现；返回播放器页时卡片自上方滑回。切歌时，如果 `MediaSession` 仍报告播放状态但新封面元数据暂时为空，模块保留现有封面和背景最多 2.5 秒，等待新封面回调，避免短暂露出系统原壁纸；超时或播放停止仍恢复原生锁屏。Debug 构建已通过，**待安装、重启及真机验收。**

## 故障恢复

优先在模块应用中关闭开关。无法操作应用时，从已授权的电脑停用模块应用，随后重启设备；恢复前保持停用：

```powershell
adb -s 1b3a7d8 shell pm disable-user --user 0 io.github.melolock
adb -s 1b3a7d8 reboot
```

设备已验证 `pm disable-user` 可以停用本应用；修复后可用 `adb -s 1b3a7d8 shell pm enable io.github.melolock` 重新启用。若 SystemUI 无法正常工作，也可在 Vector 中停用本模块的 `SystemUI` 作用域并重启；必要时通过恢复模式停用 Vector 模块。模块不改系统 APK、壁纸或播放器数据。再次测试前保存 SystemUI/Vector 日志。该 ROM 已拒绝 0.1.0 文档中的 `settings put system` 以及 `pm clear`，都不能作为恢复手段。

## 许可证与参考

本仓库按 AGPL-3.0 发布。仅参考 HyperMusicCover 的产品思路，没有复制其源码、资源或钩子；其澎湃 OS 4 适配未用于本项目。Vector 的 legacy Xposed API 用作 `compileOnly` 依赖，不打包到 APK。

**2026-10-08 补充**：本次对同类项目做了一轮源码级调研（HyperMusicCover / HyperChanger / HyperGlow CN+），产出见 [docs/RESEARCH-lockscreen-approaches.md](./RESEARCH-lockscreen-approaches.md)。调研属于**读码取经**，产品代码中没有引入它们的任何源码；调研文档里出现的少量代码片段均为**带 `文件:行号` 出处的引用**，不是本项目的实现。

三个参考项目各自的许可证不同，将来若要移植具体实现必须分别核对合规（` HyperChanger` 有一个陷阱，见下）：

| 参考项目 | 许可证 | 备注 |
| --- | --- | --- |
| `zyl6932/HyperMusicCover` | **AGPL-3.0** | 与本项目同为 AGPL，移植需同等署名 |
| `ColdP/HyperChanger` | **Apache-2.0** | 许可证与第一层不一致：它内嵌的 `hypermusiccover/` 包是自含水的项目移植而来的 **AGPL** 代码。**照搬该包要按 AGPL 处理，不能因为仓库顶层写着 Apache-2.0 就当作 Apache 代码用** |
| `aodianjun/com.aodianjun.hyperglow.cnplus` | **GPL-3.0** | 上游 `amarinne/hyperglow` 同为 GPL-3.0 |

应用标识：应用名 `Hyper MeloLock`（`values` / `values-zh` 的 `app_name`，其他语言回落到英文），包名与 applicationId 为 `io.github.melolock`，Gradle 根项目名 `Hyper MeloLock`。

**2026-10-06 做过一次整体改名**（原 `io.github.hypermusicscape.lock` / `Hyper Music Scape Lock`）：包目录与 7 个 Java 文件的 `package`、`applicationId`、`Config.PACKAGE`（`AUTHORITY` 与 `URI` 由其派生）、`assets/xposed_init`、Manifest 的 provider authorities、日志 tag（覆盖层 `HyperMusicScapeLock` → `MeloLock`，配置端 → `MeloLock[App]`）以及文档全部同步。**改名后是一个全新的应用**：不会原地覆盖升级旧的 `io.github.hypermusicscape.lock`，Vector 里会出现两个模块；必须卸载旧模块应用并重新启用本模块、重新勾选 `com.android.systemui` 作用域，旧配置数据也不会继承。仓库目录名仍是 `Hyper Music Scape Lock`（工作区路径未动）。启动器图标源图为 `docs/images/icon.png`（已复制一份到 `app/src/main/res/drawable-nodpi/ic_hmsc.png` 作为应用图标，两份 MD5 一致），它不是自适应图标，部分启动器可能加自己的遮罩。

**首页大标题字号**：Miuix `TopAppBar` 把大标题字号写死为 `MiuixTheme.textStyles.title1`（默认 32sp）且没有参数可传，`LocalTextStyles` 又是 internal。为此 `CollapsingPage` 新增 `largeTitleFontSize`：非空时用同一个 `ThemeController` 再开一层 `MiuixTheme`，只替换 `title1`，颜色与深浅色模式不受影响（`HyperIslandTheme` 通过 `LocalThemeController` 暴露控制器）。`Hyper MeloLock` 只有 12 个字符，32sp 下已能单行，所以首页**不再传这个参数**；机制保留，将来名字变长或想要更紧凑的标题时可用。

**启动更新检查已禁用**：本模块在 Manifest 里移除了 `INTERNET`，HyperIsland 原本的启动期更新检查必然失败并弹「检查更新失败」，已在 `AppShell` 中移除该调用。

**滑条点击行为**：HyperIsland 的 `PreferenceSlider` 把点击当作手动输入入口（弹对话框）。本模块的调参滑条通过新增的 `allowManualInput = false` 让点击轨道直接跳到位。默认值 `true`，HyperIsland 自身页面行为未变。

**第三方字体许可**：`app/src/main/assets/fonts/` 下的 `clock_round_1.ttf`（Quicksand）与 `clock_round_2.ttf`（Baloo 2）来自 Google Fonts，按 SIL Open Font License 1.1 授权，可随应用一起分发。OFL 要求分发时附带许可证全文并保留字体名称，**对外发布前需要把 OFL-1.1 全文一并放进仓库并在应用内可查看**（当前尚未加入，只在文档里记录）。

## 后续

先完成上面的真机验收并修复观察到的问题，再增加其他系统版本的适配器和对应构建门禁。

界面部分待办：

1. 2026-10-06 的首页/音乐应用/外观/开发者四页改版已构建通过并确认进入 APK（APK 内可见 `enabledAppCount`、`OverviewStatusGrid`、`已开启应用`、`待填写` 等新增符号），**但尚未做真机视觉验收**：安装时设备 `1b3a7d8` 已从 ADB 掉线。
2. 重新连接设备后按上面的安装命令刷入，重点看：状态卡点击切换是否可靠（这是模块唯一的开关入口）、两张数据卡的数字是否与「音乐应用」页勾选状态一致、数据卡点击跳页是否正确、暗色主题下绿色/红色状态卡对比度。
3. 作者署名与三个外链常量留在 `LockScreenPages.kt` 末尾的 `TODO(作者信息)` 处，填上后对应行会自动从「待填写」置灰恢复为可点击。
4. 中文文案目前硬编码在这四个页面里，补多语言时需抽到 `strings.xml`。

5. 2026-10-06 第二轮：外观页加入三元素编辑器（时间/封面/播放器的大小·圆角·间距，时间的粗细·颜色·字体圆润），应用名与图标换成 Hyper MeloLock，开发者填 `zuige` / `zuige66`，修复首页状态卡关掉后点不回来的 bug。构建通过、已装到 `1b3a7d8`，**锁屏实际效果待验收**：改完要灭屏再亮屏才生效，需确认时间三档字体是否真的换了字形、粗细是否连续可辨、封面宽高不同时的裁切、以及播放器卡片改尺寸后控件是否被压变形。

6. `LockScreenOverlay.java` 在 2026-10-06 18:00 前后被本会话之外改动过（加入了播放器卡入场动画、封面超时回退、`deferArtworkFallback`）。本轮改动是在那份内容之上叠加的，两边的改动都保留了。**同一个文件不要并行编辑**，否则会互相覆盖。

7. 2026-10-06 第二轮收尾（已提交 `f344d75 app页面配置`）：应用名改 `Hyper MeloLock`、音乐应用页改为列出全部应用并申请应用列表权限、滑条改为点击跳位、禁用启动更新检查、首页系统信息加 `Build.*` 兜底并对失败打日志。

8. 2026-10-06 第三轮（**已实拍验收**）：① 首页大标题降到 22sp，`Hyper MeloLock` 单行放下；② Manifest 补声明 `com.android.permission.GET_INSTALLED_APPS`，进入「音乐应用」页实测弹出 MIUI 授权框；③ 系统信息卡恢复真值（系统版本 `OS3.0.303.0.WNKCNXM`）；④ 启动时不再弹「检查更新失败」。

   **仍未验收**：锁屏三元素调参（需要重启 SystemUI，见上文「改配置后必须重启 SystemUI」）。「Xposed 框架」一行在 legacy 模块下固定显示「未知」——`XposedPrefsSyncApp` 依赖 libxposed 的 service 绑定，legacy 模块拿不到；设备上也没找到可识别的框架管理器包（`pm list packages | grep -i vector/lsposed` 无结果），所以暂时无法用包名版本号兜底。这不算 bug，但要显示真值需要换读取方式。

7. 2026-10-06 熄屏唤醒防闪：不再在 `ACTION_SCREEN_OFF` 时移除已经渲染的沉浸层，也不会因为熄屏期间的媒体回调撤掉它；`ACTION_SCREEN_ON` 先立即恢复缓存场景，再刷新 `MediaSession`。解锁、关闭模块、锁屏根视图分离，或亮屏后确认没有有效会话时仍恢复原生锁屏。该修复已完成 Debug 构建，待真机验证首次唤醒是否消除原生锁屏的一秒闪现。

8. 2026-10-06 时间裁切修复：时间字号为 91 时，用户把自定义容器高度设为 100 dp，圆体/粗体字形的实际绘制高度超过容器，导致底部被裁掉；布局剩余空间不能参与该文本控件测量。现移除时间的锁定比例、宽度和高度设置，时间始终 `MATCH_PARENT × WRAP_CONTENT`，外观页只保留字号、粗细、圆润、颜色和“距顶部”。通知入口的底部边距从 125 dp 调整为 78 dp，移动到系统底部快捷入口上方。Debug 构建通过，待真机验收。

9. **2026-10-08 同类项目源码调研（只读，未改产品代码）** —— 产出 `docs/RESEARCH-lockscreen-approaches.md`。核心结论三条：

   - **发现一条我们没走过的路径**：HyperMusicCover / HyperChanger 是在 **`com.miui.miwallpaper` 进程**里 hook GL 上传点（`ImageWallpaperRenderer` 的 `lambda$onSurfaceCreated$0`）把专辑图换成壁纸纹理，而不是在 SystemUI 里叠加 View。理由是 MIUI 的时钟液态玻璃与通知卡模糊**采样的是壁纸窗口，SystemUI 的 View 树永远采样不到**——也就是说我们「把背景层提升到 `windowRoot`」在结构上补不了这个洞。
   - **本机已证实这条路可行所需的一切符号都存在**（`[机]`）：`com.miui.miwallpaper` 包与 `MiuiKeyguardPictorialWallpaper` 锁屏壁纸窗口存在；扒 dex 确认 `com.miui.miwallpaper.opengl.ImageWallpaperRenderer` 及其 `$WallpaperTexture` 存在；目标方法字符串逐字存在且带 D8 混淆后缀 `lambda$onSurfaceCreated$0$com-miui-miwallpaper-opengl-ImageWallpaperRenderer`（**按精确名 `getDeclaredMethod` 会落空，必须模糊匹配 lambda**）；`getTextureDimensions`、`AnimImageWallpaperRenderer`、`KeyguardImageEngineImpl` 也都在。
   - **SystemUI 侧的挂载点候选得到补名单**：本机 `resources.arsc` 里 `keyguard_translation_info`、`keyguard_background_layer`、`keyguard_foreground_layer`、`keyguard_clock_container`、`keyguard_root_view` **全部存在**（注意这些名字不在 dex 里，要在 arsc 中搜）。`keyguard_background_layer` 本身就在时钟栈之后，可作为「不用把层提升出 keyguard 根」的候选。

   调研文档给出了分级建议（A 立即做 / B 先验证 / C 不要照搬），其中**不建议**的有：迁移到 libxposed API 102（我们是 Vector legacy，收益划不来）；在 SystemUI 内跑 Compose（所谓 `CoverCompose.java` 其实是纯 Bitmap+Canvas 合成，名字骗人）；抄歌词子系统；用 MIUI 的渐变私有模糊（已知会 RenderThread SIGSEGV）。**代码改动尚未开始，等 zuige 决策后再动手。**

10. **2026-10-08 解锁节奏「两头堵」的根因分析**（承接第 9 条，详见调研文档「五之二、专题」一節）—— 用户反馈解锁时「早掀盖就看到原生壁纸，晚掀盖就看不到桌面入场动效」。结论：**这不是参数没调准，是当前架构下的固有矛盾，调参无解。**

   - **根因**：我们封面之下还活着一层「原生锁屏壁纸窗口」，它比我们活得久。不 lift 时锁屏根被系统置 `INVISIBLE`、封面硬消失；lift 时我们自己掐 `HOLD/FADE` 淡出，一淡就露出还没退完的原生壁纸。**两头只能选一头。**
   - **系统侧真正在做的是**：解锁时执行 `updateKeyguardWallpaperState(show=false, anim=true)`，让**锁屏壁纸窗口**自己跑约 345ms 的退场动画（+340ms 开始 → +685ms 结束 → +742ms 切到桌面壁纸窗口）。该符号已在 `WallpaperOS3.apk` 的 dex 中核实存在。
   - **即**：我们是在用「自己一层 View 的 alpha 动画」去模拟「系统壁纸窗口的退场动画」，两条时间线永远对不齐——这正是 `UNLOCK_COVER_HOLD_MS` 从 130→240→280→760 反复调都不对的真正原因。
   - **换纹理为何能解**：封面成为壁纸后，它由**系统**用原生动画带走，时间线变成同一条，背景层与「掀盖时机」整体消失，中间态不再露出另一张壁纸。
   - **本机前提已核实 `[机]`**：`flag_lock_wallpaper_type=image`、锁屏壁纸为单张静态图 → **不是画报轮播，不会和系统自动换图打架**（`MiuiKeyguardPictorialWallpaper` 只是窗口类名，不代表启用画报）。
   - **新增已知风险**：本机壁纸是**景深/抠图壁纸**（`wallpaper_matting_support=1`、`supportSubject: true`、存在 `*_MASK.jpg`），换纹理时若忽略 mask 可能导致主体抠图异常。
   - **诚实标注**：HMC / HyperChanger **并未**宣称解决解锁节奏（它们仍保留自己的延迟释放逻辑），所以「换纹理能根治」是**基于机制的推断**，不是别人的实测结论。
   - **下一步建议**：不动 `LockScreenOverlay`，先做一个只验证「能否把锁屏壁纸换成纯品红」的最小探针（独立入口、结论拿到即删）。**待 zuige 拍板。**

11. **2026-10-08 换纹理可行性探针（`WallpaperTexProbe`，WTP-1）已编写并装机，等作用域勾选** —— zuige 拍板「可以，继续」后动手。

   - **新增文件**：`app/src/main/java/io/github/melolock/WallpaperTexProbe.java`（独立 Xposed 入口，AGENTS.md 探针约定：结论拿到后源码与 `xposed_init` 清单一并删除）。**未触碰 `LockScreenOverlay.java`**。
   - **入口与作用域**：`xposed_init` 追加 `io.github.melolock.WallpaperTexProbe`；`res/values/xposed_scope.xml` 追加 `com.miui.miwallpaper`。
   - **探针行为**：对三个候选渲染器（`opengl.ImageWallpaperRenderer`、`container.openGL.KeyguardAnimImageWallpaperRenderer`、`container.openGL.KeyguardStreamAnimImageWallpaperRenderer`）逐个独立 try/catch 地 hook「参数带 Bitmap 的方法 / `lambda$onSurfaceCreated$` 前缀 lambda / `getBitmap`」；**日志全打**（含非 keyguard 命中，便于摸清本机真实路径），**替换只动类名含 `Keyguard` 的实例**（桌面壁纸绝不动，否则用户回不到桌面）；替换方式是**在原图的副本上画品红**（尺寸/配置不变，避免 GL 矩阵源矩形错位，无需 hook `getTextureDimensions`）。
   - **构建与自证**：`assembleDebug` 通过；装前逐 dex 搜串确认 `WallpaperTexProbe` / `WTP-1` / 候选类名都在 APK 里；设备上原包是 `DEBUGGABLE`，debug 直接覆盖安装成功；`am force-stop com.miui.miwallpaper` 重启壁纸进程（新 PID 10446）。
   - **当前卡点**：`logcat` 全量搜 `WTP` = **0 条**，而 SystemUI 侧 MeloLock 日志正常（57 条）——**Vector 管理器里还没勾选 `com.miui.miwallpaper` 作用域**（`xposed_scope.xml` 只是声明，不勾选不注入；已写进 AGENTS.md 约定）。**待 zuige 在手机上勾选作用域后再次 force-stop 壁纸进程复测。**
   - **成功判据（唯一）**：锁屏亮屏看到整屏品红。同时日志应出现 `WTP hooked ...` / `WTP hit ... self=... keyguard=true` / `WTP replaced ...`。看不到品红但有 `WTP hit ... keyguard=false` → 本机锁屏壁纸不走这些类的 Keyguard 实例，按日志迭代下一版探针。

12. **2026-10-08 探针验证成功 + 重启作用域支持壁纸进程** —— 前者证实了换纹理路线的核心假设，后者是基建补齐。

   **探针结果（全部 `[机]` 实测）**：zuige 在 Vector 勾选 `com.miui.miwallpaper` 作用域后，force-stop 壁纸进程，日志一次命中：
   - hook 安装：`opengl.ImageWallpaperRenderer#lambda$onSurfaceCreated$0$com-miui-...`（参数 `[Bitmap]`，**前缀匹配成功**）、`container.openGL.KeyguardAnimImageWallpaperRenderer#getBitmap`、`KeyguardStreamAnimImageWallpaperRenderer#getBitmap` 三个全挂上；
   - **本机锁屏壁纸的真实渲染路径**：`KeyguardAnimImageWallpaperRenderer.getBitmap()`（**不是** `ImageWallpaperRenderer` 的 Keyguard 子类）→ 每次亮屏/壁纸重建都会取图，尺寸 `1440x3200`（壁纸文件尺寸 ≠ 屏幕 1080x2400，印证 HMC 注释——直接用原图副本画色不会错位）；桌面走 `DesktopAnimImageWallpaperRenderer`，探针正确跳过（`keyguard=false`）；
   - 替换生效：`WTP replaced result/arg 1440x3200` 多次。**结论：换纹理路线成立**，正式方案（专辑图→壁纸纹理）可以开工；锁屏壁纸是静态图（非画报轮播）的前提此前已核实。

   **重启作用域扩展**（zuige 要求）：
   - `Config.java` 新增 `ACTION_RESTART_WALLPAPER` + `WALLPAPER_PACKAGE`（与 SystemUI 通道同构：配置端发显式广播 → 注入的 hook 自杀 → 系统重绑 → Vector 重新注入）；
   - `WallpaperTexProbe` 里 hook `WallpaperService#onCreate`（沿父类链找 `onCreate`，`thisObject` 即 Context）注册 receiver，static flag 防重复；
   - `RestartScopeDialog` 的 `RestartScopeTargets` 新增 `com.miui.miwallpaper`（root 路径，`am force-stop`）；字符串 `wallpaper_process`（英文/中文，其余语言回落默认）；
   - `LockHomePage` 无 root 分支同时广播 SystemUI 与壁纸进程两条。
   - **端到端验证**：`rev=WTP-2` 装机后，`am broadcast -a io.github.melolock.action.RESTART_WALLPAPER -p com.miui.miwallpaper` → `killing wallpaper process`（PID 16973）→ 新进程 18696 重新加载 `WTP-2`。**无 root 重启壁纸进程链路完整可用。**

13. **2026-10-08 封面壁纸化正式实现（WCV-1）落地** —— 用户确认看到整屏品红后开工。探针 `WallpaperTexProbe` 按约定删除（源码 + `xposed_init` 条目），由两个正式类替代：

   - **壁纸进程侧 `WallpaperCover.java`**（新入口，登记进 `xposed_init`）：
     - hook 实测命中的 `KeyguardAnimImageWallpaperRenderer#getBitmap`：after 里若有封面缓存则 `setResult(缓存位图)`——**每次返回同一引用**（GL 上传去重红线）；无缓存/被清除 → 原样返回（原生壁纸，fail closed）；
     - 目标位图尺寸**动态记录**自 getBitmap 的原生返回值（本机 1440x3200），不硬编码；
     - 广播接收（`ACTION_WALLPAPER_COVER`）：JPEG 解码 → 黑底 cover-crop 铺满 → 换引用；解码/合成在单线程 worker，失败清缓存回退原生；
     - 无 root 重启通道（`ACTION_RESTART_WALLPAPER` 自杀 receiver）从探针移植进来。
   - **SystemUI 侧 `WallpaperCoverPush.java`**（由 `HookEntry` 在 keyguard 根 attach 时安装，独立 try 包裹，**未触碰 `LockScreenOverlay`**）：
     - 自己起一个 `MediaSource` 实例（不侵入现有媒体层），封面 Bitmap 引用去重（引用没变不发）；
     - 压缩到最长边 1080、JPEG q85（~百 KB，走 Binder extra，避开 SELinux 文件权限坑）后发显式广播；
     - `ACTION_SCREEN_ON` 时用缓存的最近快照补发（壁纸进程重启丢缓存 → 亮屏补上；补发前壁纸就是原生，无残缺态）。
   - **配置策略**：壁纸进程读不到模块配置（SELinux），开关与封面全由 SystemUI 侧决策随广播下发；壁纸侧零配置依赖。
   - **装机验证**：`assembleDebug` 通过；dex 自证 `WCV-1`/`WallpaperCoverPush` 在、`WallpaperTexProbe` 已移除；装机 + `am crash com.android.systemui` + `am force-stop com.miui.miwallpaper` 后日志确认双侧就位（`WCV renderer hook installed` / `broadcast channel armed` / `WallpaperCoverPush installed`）。
   - **待人工验收**：播放音乐 → 锁屏亮屏，应看到锁屏壁纸变成专辑封面；切歌 → 下次亮屏壁纸跟随；关模块/无会话 → 回退原生壁纸。**解锁动效是否随之解决（本方案的核心目标）待实测**。已知边界：锁屏显示期间切歌，新封面要等下次亮屏/壁纸重建才上墙（未做主动重绘请求）。

9. 2026-10-06 解锁滑动背景裂缝修复：此前同一张模糊专辑图分别绘制在锁屏根视图和通知窗根视图；解锁手势期间两个根视图由 SystemUI 分别做位移/淡出动画，底部会暴露原壁纸。现删除通知窗根视图里的重复模糊图和遮罩，只保留锁屏根视图内的单一全屏背景层；窗口顶层仅放封面、播放器和通知入口。普通通知继续由守卫隐藏。Debug 构建已通过，待真机滑动解锁验收。

10. 2026-10-06 播放中壁纸与时间页修复：通知页过去调用完整视图恢复，连同系统壁纸层与系统时钟一起恢复，因此播放时可见原壁纸，且该构建的系统时钟会丢失小时。通知页现只临时恢复通知栈，继续隐藏原壁纸、原生前景和两组系统时钟；模块自己的 `TextClock` 留在通知页上方。其后验证发现等待 `ACTION_USER_PRESENT` 会让覆盖层残留到桌面，因此撤回该延迟撤层策略：`isKeyguardLocked()` 变为 false 时立即恢复原生界面，根视图分离仍为第二道清理。为阻止主题在布局动画中动态加入第二个时间或壁纸层，守卫现在遍历锁屏根视图并隐藏所有时钟类及 `wallpaper`/`keyguard_background` 标识的原生层。Debug 构建已通过，待真机验收通知页、上滑解锁和无媒体回退。**其中「`isKeyguardLocked()` 变为 false 时立即恢复原生界面」这条策略已在第 13 条被「淡出 + 保留实例」取代——第 12 条的日志证明它正是上滑露壁纸一秒的直接原因。**

11. 2026-10-06 冷启动观察：已通过 ADB 对设备 `1b3a7d8` 执行重启并清空后抓取日志。启动完成约 48 秒后，SystemUI 才挂入锁屏根视图；在此之前 Launcher/个人助理窗口已开始附着，所以会短暂出现无状态栏的桌面。SystemUI 随后报告主线程约 2.5 秒的延迟，堆栈落在系统自己的 `MiuiIslandMediaViewHolder` / 通知栈初始化；同时 Qualcomm 电话与 IMS 服务因 telephony service 尚未就绪反复重启。模块日志显示锁屏根钩子安装、根视图附着且 `enabled=true`，crash 缓冲区没有 SystemUI 或模块进程崩溃。结论是“桌面 → 黑屏 → 仅壁纸 → 黑屏 → 锁屏”由系统开机阶段的窗口和服务排序造成；LSPosed 模块只有 SystemUI 创建后才能运行，不能提前接管这段过程。后续应以模块开/关的两次冷启动时序对照评估其额外影响，避免为遮挡启动闪屏而在更早的系统层强行加覆盖层。

12. 2026-10-06 桌面通知栏与切歌过渡修复：`KeyguardManager.isKeyguardLocked()` 在本机已解锁后下拉通知栏的短窗口内仍可能返回 true，导致锁屏根视图中的模块时间和背景进入桌面通知栏。现以 `ACTION_USER_PRESENT` 作为解锁周期边界，在下一次 `SCREEN_OFF` 前强制隐藏/暂停场景，媒体回调只预建不可见视图，不能再把锁屏组件恢复到桌面通知栏。通知入口从 HyperOS 锁屏根移动到模块自己的 `FrameLayout` 并固定在底部 10 dp，避免根布局在动画中忽略 gravity 后落到左上角；位置低于充电文案。切歌数据层不再以 `MediaController` 对象是否相同决定是否保留缓存，新的 MediaSession 或 URI 封面均会继续显示上一首完整沉浸页，待下一张封面解码成功才原子切换；5 秒超时或解码失败才回退原生。Debug APK（`0.2.0` / versionCode 9）已构建并安装，待重启 SystemUI 后验收桌面下拉、入口位置和跨会话切歌。

13. 2026-10-06 通知页空白与时间消失修复：`hideNativeClockLayers(root)` 的递归范围意外包含模块的 `foreground`，把自绘 `TextClock` 当成系统时钟隐藏，因此播放器页会只剩封面和卡片。现递归遇到模块自己的前景/背景即停止，并且每次进入播放器页或通知页都会明确恢复自绘时钟可见。通知页此前仅按进入沉浸前的原始 visibility 恢复通知栈；该 ROM 有时原始状态就是 `INVISIBLE`，于是出现只剩模糊背景的空白页。现通知页及其 pre-draw 守卫都会明确设通知栈为 `VISIBLE`，最终 `restore()` 仍会回到初始状态。Debug 构建通过后已安装到 `1b3a7d8` 并重启；日志确认锁屏根已附着、模块 `enabled=true`。启动检查时无活跃媒体会话，故原生锁屏保持显示，待播放带封面曲目后进行真机验收。

12. 2026-10-06 闪屏诊断日志（第四轮，**本轮只加日志、不改行为**）。用户真机复现确认第 7 条的「熄屏唤醒防闪」与第 9 条的「解锁滑动背景裂缝」**都没消除**对应现象：① 上滑解锁时仍有一秒露出壁纸；② 快速熄屏再亮屏时先显示原生锁屏约一秒。为精确定位触发路径，补了以下日志：

    - `LockScreenOverlay.restore()` 改成 `restore(String reason)`，所有调用点标注原因（`predraw-keyguard-unlocked` / `user-present` / `switch-off` / `destroy` / `render-*` / `artwork-timeout`）；撤层前打印一行状态快照 `fg/bg/shown/expanded/scene/attached/interactive/keyguard`。
    - pre-draw 守卫检测到「前景仍存活但 `isKeyguardLocked()` 已翻 false」时打一次 `Pre-draw: keyguard unlocked while overlay alive`（有一次性标志，不会每帧刷屏）。
    - `render()` 的每个早退分支走 `skip(reason)` 去重日志，可在日志里直接看到「为什么没渲染」。
    - `MediaSource.refresh()` 打印「从查询会话到真正出图」的耗时（毫秒），并区分三条路径：bitmap 直出 / 缓存帧命中 / URI 异步解码，用于判断亮屏后的一秒是花在异步取封面还是别处。
    - `HookEntry` 打印锁屏根视图的 attach/detach 时间点与可见性，用于判断场景是否为 `destroy()` 销毁。

    **抓取方式**：`adb -s 1b3a7d8 logcat -c` 后复现问题，再 `adb -s 1b3a7d8 logcat -v time | grep MeloLock`。注意覆盖层跑在 `com.android.systemui` 进程，**配置端 App 进程的日志里不会有这些行**——按包名 `io.github.melolock` 过滤只会看到配置界面自己的日志。

    同时订正上文「改完需要灭屏再亮屏一次」：自熄屏唤醒防闪改动后 `ACTION_SCREEN_OFF` 不再撤层，场景存活时灭屏再亮屏不会重建。

13. 2026-10-06 解锁闪屏修复：**淡出 + 保留实例（已构建、已安装，待真机验收）**。第 12 条的诊断日志抓到了 5 轮完整「熄屏 → 亮屏 → 解锁」，把两个现象定为同一根因：

    - **上滑解锁露壁纸一秒**：解锁瞬间 `keyguardGuard` 检测到 `isKeyguardLocked()` 翻 false，立即 `restore()`。日志显示此时 `interactive=true`（屏幕亮着）、`attached=true`（根视图没分离）、`scene=true`（场景完整）——是**守卫主动撤层**，不是根视图分离。而 `restore()` 里的 `restoreChangedViews()` 会把原生壁纸层恢复 VISIBLE，系统解锁动画却还要跑 80~190ms（`USER_PRESENT` 才到），空档期就是壁纸。
    - **亮屏先见原生锁屏约一秒**：是上一条的衍生——每次解锁都销毁场景，下次亮屏只能 `create()` 重建，实测 126~145ms，叠加屏幕物理亮起到广播送达的系统延迟，观感即「先原生锁屏再变音乐版」。**反证**：日志里有一轮没经过解锁，`SCREEN_OFF kept=fg=true` → `SCREEN_ON fg=true` 直接复用，完全不闪。

    改法：新增 `suspended` 状态。解锁时 `suspend()` 把覆盖层淡出 180ms 到 `GONE` 并保留全部视图实例，随后 `restoreChangedViews()` 把原生层交还系统；判定为 suspended 时 pre-draw 守卫完全不介入（不隐藏原生层、不 bringToFront），媒体回调也不接管界面。锁屏重新出现时 `resume()` 恢复可见性并直接 `showMusic()`，跳过 `create()`。仅在模块关闭、媒体不可用（`snapshot == null`）、锁屏根视图分离时才真正 `restore()`。

    日志新增 `suspend reason=...` / `suspend done; scene kept for reuse` / `resume reused scene ...`，`state()` 增加 `suspended=` 字段，验收时看这几行即可判断是否走的复用路径。

14. 2026-10-06 暂停保留 + 熄屏预建（第 13 条的补完）。真机验证第 13 条时发现两条遗漏的路径：

    - **暂停时不该销毁**：原实现里暂停会让 `snapshot == null` → `restore()` 撤层，于是「暂停后锁屏」被原生界面顶替，下次亮屏又要重建。现在 `MediaSource` 区分「会话还在但暂停」与「会话真的消失」：前者交出最后一帧（`Snapshot.playing = false`，`speed` 归零让进度条停在暂停处），后者才回退原生锁屏。同时 `current` 在暂停时仍指向该会话，否则会注销 controller 回调、用户点播放后锁屏不更新。中间键改为播放/暂停切换，图标跟随 `playing`。
    - **「桌面播歌 → 熄屏 → 亮屏」从来没有场景可复用**：在桌面上 `render()` 一直 `skip("keyguard-unlocked")`，场景根本没建过，`suspend/resume` 救不了这条路径。现在 `ACTION_SCREEN_OFF` 会主动触发一次媒体刷新，`render()` 在 `!isInteractive()` 时调 `preCreate()`——屏幕已灭、用户看不见，正好把场景建好并置 `GONE` + `suspended`，亮屏时由 pre-draw 第一帧 `resume()` 秒显。

    新增日志：`Scene pre-created while display off; will resume on wake`、`Session paused in Xms; keeping last frame`。`render()` 抽出 `applySnapshot()` 供正常渲染与预建共用。

15. 2026-10-06 暂停误判 + 解锁残留。真机日志暴露第 14 条的两个漏洞：

    - **暂停被误判成「无会话」，整个场景被销毁**：暂停识别原先依赖 `lastReady`，而切歌失败（日志里的 `Artwork decode failed in 81ms`）会把 `lastReady` 清空，此后暂停就认不出来 —— `refresh()` 走 `onMedia(null)`，`render()` 落到 `render-no-session` 把覆盖层撤掉，锁屏直接掉回「壁纸 + 原生时钟 + 原生媒体通知卡」。改为遍历会话时记住第一个「允许的包 + 有 metadata + 非播放」的会话，不依赖 `lastReady` / `current`。
    - **解锁后前景组件残留桌面**：`suspend()` 原先把隐藏动作挂在 `ViewPropertyAnimator.withEndAction` 上，但解锁时窗口正在切换，动画回调可能根本不推进，前景层就永远保持可见。改为「淡出只是视觉效果，真正的隐藏由 `main.postDelayed(finishSuspend, 150)` 兜底」——`finishSuspend` 走 Handler，不依赖动画回调；`resume()` / `restore()` 会取消这个待执行回调。

    另外诊断日志的 tag 已随整体改名变为 `MeloLock`。

16. 2026-10-06 解锁残留治本：**前景层改挂锁屏根视图** + **播放即预建**。用户截图显示解锁瞬间「壁纸已出、前景组件（大时钟/大封面/播放器卡片/通知按钮）完整残留」——根因是背景层挂在锁屏根视图（`HyperOSKeyguardRootView`），被系统解锁动画直接带走；而前景层挂在窗口根视图，不跟系统动画走，只能等我们自己的淡出，过程完全不同步。改为把 `foreground` 与 `notificationButton` 也挂到锁屏根视图：系统退场动画把整个锁屏根（背景+前景+按钮）一起带走，各层消失时机完全同步。通知栈仍在窗口根，展开通知时自然盖在锁屏根之上。`restore()` 的 removeView 判据同步改为锁屏根。

    同时实现「播放即预建」：`render()` 在解锁态（桌面）收到有效媒体快照且场景不存在时，直接 `preCreate()` 把场景建好并置 GONE + `suspended`——**一点播放歌曲，锁屏场景就绪**，之后锁屏/熄屏/亮屏都不再走 `create()`。`pre-draw` 守卫在 suspended 且未锁屏时完全不介入，桌面上不会误显示。

17. 2026-10-06 **左侧下拉改为直接堵手势** + `thisObject` 事故修复。状态检测路线两次被真机证伪（通知栈可见性、`notification_panel` 可见性），改为在通知面板（`id/notification_panel`）的触摸入口挂 Xposed 钩子，沉浸场景显示期间把左半屏 `ACTION_DOWN` 返回 false。第一版漏了 `hook.thisObject != leftShadePanel` 判定：面板类没覆写 `dispatchTouchEvent`，`getMethod` 拿到的是框架 `View` 的实现 → 等于给 SystemUI **所有 View** 装钩，把播放器上曲/播放暂停与「展开通知」按钮一起吃掉。修复后只在面板实例上生效。开关 `Config.BLOCK_LEFT_SHADE`（默认开），每次手势现读。

18. 2026-10-06 **切歌不再退回原生锁屏**。真机日志显示换歌时 App 会先摘掉 metadata 里的封面 bitmap、只留 URI（且是 `https://`，`ContentResolver` 读不了 → `FileNotFoundException`），约 300ms 后才补齐；期间会话也可能短暂不合格。旧代码三条路径一起撤层：解码失败清空 `lastReady`、会话一不合格立刻下发 null（→ `restore("render-no-session")`）、置空 `current` 注销回调。改为**失败/超时保留上一帧** + **1500ms 宽限期** + **空窗期按包名重新挂回会话**。

21. 2026-10-07 **上滑解锁不再露原生壁纸**。用户反馈上滑解锁时会看到原生壁纸。加探针（`unlockWatch`，suspend 后每 30ms 采样一次锁屏根视图，持续 1500ms）后实测三次复现，得到决定性数据：

    ```
    t=+36ms   root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[VISIBLE alpha=1.00] bg=[VISIBLE]
    t=+131ms  root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[VISIBLE alpha=0.07]  bg=[VISIBLE]
    t=+221ms  root=[INVISIBLE attached=true alpha=0.00 ty=0.00] fg=[GONE]                bg=[GONE]
    ```

    **根因不是原先猜的那样**：① 系统**没有**给整棵树做「跟随的退场动画」——它在 `suspend()` 触发**之前**就把 `HyperOSKeyguardRootView` 直接置 `INVISIBLE + alpha 0`，且全程 `ty=0.00`（无位移）；挂在它内部的任何层都没有过渡窗口，父控件 alpha 归零即一同消失，因此「让系统动画带走我们的层」这条思路**不成立**。② 用户看到的也**不是** `restoreChangedViews()` 交还的那个锁屏壁纸层（它还在 `alpha=0` 的容器里，根本不可见），而是**桌面底下那张系统壁纸**——keyguard 一撤就露出来了。③ 我们自己的 120ms 淡出（`fg alpha 1.00 → 0.07`）是**白淡**：期间父控件已不可见。④ 本次 `USER_PRESENT` 在 suspend 后仅 16ms 到达（旧记录的 80~190ms 来自另一批采样），不是关键变量。

    **改法**：把 `background`（模糊封面底图 + 遮罩）从 `root.addView(background, 0, ...)` 挪到 **`windowRoot.addView(background, 0, ...)`** —— 挂窗口根最底层、位于 keyguard 根**之外**。keyguard 被系统撤走时这一层留在原地，下面露出的就是沉浸背景而不是桌面壁纸；收尾仍由 `suspend` / `finishSuspend` 置 `GONE`，不会残留到桌面。`restore()` 改为按实际父容器移除。**前景层（`foreground`）必须留在锁屏根**跟着系统走，否则会退回「壁纸已出、组件还在」的旧问题。

    **排查教训**：真机 logcat 行格式是 `I/MeloLock(6507):`（**标签后跟 PID，不是冒号**），`grep "MeloLock:"` 会漏掉全部有效行，据此误判过「模块没运行」，并连带让用户白勾 Vector 作用域、白重启一次设备。正确过滤是 `grep -E "/MeloLock\("`。另外 `adb logcat > file` 走块缓冲、不实时落盘，抓现场要用 `adb logcat -d -v time -t 'MM-DD HH:MM:SS.mmm'` 一次性 dump。

19. 2026-10-06 **播放器页 ↔ 通知页共享元素切换**，并修掉三个真机反馈：① 展开通知后左下拉仍能拉出通知（`sceneShowing()` 不该带 `playerSceneVisible`）；② 桌面下拉露出原屏保时间（suspended 期间要继续按住原生时钟层，但不碰壁纸层）；③ 切回播放器时间闪一下（真凶是返回时把含时钟的 `foreground` 从 alpha 0 淡入）。附带：位移上限收到 96dp 且展开/返回复用同一值、`bringToFront()` 换成 `ensureOnTop()`（避免每帧 `requestLayout`）。

20. 2026-10-07 **首个 Release**：仓库推送到 <https://github.com/zuige66/Hyper-MeloLock>，v0.2.0 正式签名 APK（v1+v2+v3）发布到 [Releases](https://github.com/zuige66/Hyper-MeloLock/releases/tag/v0.2.0)。补 `.gitignore`（签名密钥、`.workbuddy/`、临时截图）。发布用 token 只活在临时文件里，用完即删（**并且应当在 GitHub 上吊销**）。

22. 2026-10-07 **重启作用域列表改为本模块自己的作用域，并在点开前先探 root**。首页右上角刷新图标（`MiuixIcons.Refresh`，`actionDescription` 用 `restart_scope`）原本直接弹出 `RestartScopeDialog`，存在两个问题：① 列表是迁入的 HyperIsland 硬编码 5 项（`com.android.systemui` / `com.milink.service` / `com.android.settings` / `com.xiaomi.xmsf` / `com.android.providers.downloads`），**后四项本模块根本没有 hook**；② root 判定发生在「点了确定之后」——`RestartScopeService.restart()` 走 `Runtime.exec("su")`，失败才显示 `restart_root_required`，没 root 时列表照样弹出来，用户只能白点一次。

    改为：`RestartScopeService` 新增 `hasRoot()`（开一个 `su` 跑 `id`，以 `waitFor() == 0` 判定），`OverviewPage` 的 `onAction` 在协程里先调用，**有权限才 `showRestartDialog = true`，否则 Toast `restart_root_required`**。同时 `RestartScopeDialog` 的 `targets` 用 `stringArrayResource(R.array.xposed_scope)` 与内置 `RestartScopeTargets` 求交集，**只列 Manifest 里真正声明过的作用域**——当前 `xposed_scope.xml` 只有 `com.android.systemui` 一项，将来往该文件加一项列表会自动跟上。注意 `hasRoot()` 会触发 root 管理器（SukiSU）的授权弹窗。

23. 2026-10-07 **未勾选音乐应用则不启用沉浸锁屏**。`Config.packageAllowed()` 原本是 `raw.isEmpty() || (!NONE.equals(raw) && allowedPackages(context).contains(packageName))`，即**未设置＝允许全部**：用户刚装好、还没在「音乐应用」页做任何选择时，任何 App 的 MediaSession 都能拉起覆盖层。现在空值与哨兵 `NONE` 一律 `return false`，**必须显式勾选某个播放器包名才放行**。配套把 `enabledAppCount()` 的 `-1`（原「允许全部」返回值）去掉，改为直接返回 `selectedPackages(context).size()`；首页「已开启应用」数据卡因此不再需要区分「全部 / 0」。

24. 2026-10-07 **重启小圈点了没反应** —— 根因是 `su` 在本机根本不可用。用户反馈首页右上角刷新图标点击多次毫无反应。定位过程：

    ```
    adb shell ls -l /system/bin/su /system/xbin/su /system_ext/bin/su /vendor/bin/su   → 全部 No such file
    adb shell ls -l /data/adb/ksu/bin/su                                                → Permission denied
    adb shell su -c id                                                                  → su: inaccessible or not found
    ```

    `RestartScopeService` 原实现是 `Runtime.exec("su")` 配一个**没有超时**的 `waitFor()`。在这种环境下 `exec` 要么抛 IOException、要么启动后卡在等一个永远不会响应的 root 管理器，`waitFor()` 无限期阻塞在 IO 线程上 → 协程永不返回 → 既没有弹作用域列表也没有 Toast，表现就是「点了完全没反应」。

    **改法（两条）**：① `su` 统一走 `SU_TIMEOUT_MS = 3000` 超时（`waitFor(3000, MILLISECONDS)`，超时或失败一律 `process.destroy()`），并打日志 `RestartScope: hasRoot=`，避免再次出现「静默卡死」。② **新增一条不需要 root 的重启通道**：`Config.ACTION_RESTART_SYSTEMUI` 广播——配置端发现无 root 时发一条 `setPackage("com.android.systemui")` 的**显式**广播，模块 `LockScreenOverlay` 用 `Context.RECEIVER_EXPORTED` 注册（显式广播才能穿过 `NOT_EXPORTED`），收到后 `Process.killProcess(Process.myPid())` 自杀重启，与 `am crash com.android.systemui` 等价但不需要 root 也不需要 adb。有 root 时仍走原来的作用域列表（su 命令）。Toast 文案用新增的 `restart_scope_requested`（「已请求重启系统界面」）。

25. 2026-10-07 **音乐应用页两个默认项**：① `showSystemApps` 默认由 `true` 改 `false`——列表里绝大多数是系统组件，默认打开只会让用户找不到自己装的播放器；② `loadMusicSelection` 去掉 `unrestricted = packages.isEmpty()`，改为恒 `false`，**默认任何应用都不勾选**，与第 23 条 `Config.packageAllowed` 的语义对齐。「全部应用」仍作为可切换选项保留在界面上，但不再是默认值。

26. 2026-10-07 **开发者页底边硬边 + 外观页泛蓝**：同一处根因。`AnimatedAboutBackground` 内部用 `BlendMode.DstIn` 画竖直渐隐蒙版（白 → 透明），而 `LockAboutPage` 复用它时**漏了上游 `AboutPage` 的那句 `compositingStrategy = CompositingStrategy.Offscreen`**。没有独立图层时 `DstIn` 会拿整块 surface 当混合目标：一方面蒙版底边压不住、留下一条硬边（用户看到「底部又蓝又黑」），另一方面 `HorizontalPager` 的相邻页（外观页）也被染上渐变色（用户看到「右半部分泛蓝」）。**照搬上游组件时，`graphicsLayer { compositingStrategy = Offscreen }` 必须一起搬**，否则 `BlendMode` 类绘制会越界。

27. 2026-10-07 **外观参数（含「背景样式」三项）改完不生效** —— 场景跨锁屏周期复用带来的回归。`create()` 是**唯一**读取外观配置的地方（元素参数、`overlayStyle/Color/Alpha`、`cornerRadiusDp`），而解锁后场景不再销毁、只 `suspend()` 保留实例等 `resume()` 复用（见第 20 条），于是 `create()` 一辈子只跑一次 → 改什么都不会重读，只能重启 SystemUI。

    改法：**记指纹 + 复用前比对**。`create()` 收尾算一份 `appearanceSignature`（元素参数 + 背景样式/颜色/强度 + 封面圆角，SystemUI 侧一次 `Config.elementValues()` 查询 + 三次 `readInt`），`resume()` 里比对：不同就 `restore("appearance-changed")`，再用保留下来的同一个 `MediaSource.Snapshot` 现场 `render()` 重建（只多一次 `create`，约 100ms）。重建**必须 `main.post()` 到下一帧**：`resume()` 站在 pre-draw 回调里，当场 `addView` 会让这一帧的绘制和紧随的 layout 互相打断。

    配套把「纯色沉浸」做出区别：三档原本只差模糊半径（28 / 18 / 0），18dp 与 28dp 肉眼几乎分不出来，用户会以为「没生效」。现在 `style == 2` 直接**不铺封面**，改为在遮罩下面加一层 `overlayColor` 的纯色，背景就是一整块色值。界面上「背景样式」的 summary 也写成三档各自的效果。

28. 2026-10-07 **「展开通知 / 返回播放器」入口与系统底部快捷栏对齐**。这两个图标属于 MIUI 的 `com.miui.keyguard.shortcuts`，**id 在网上传的名字对不上**，所以先打一次视图树清单再决定判据（`adb shell uiautomator dump /sdcard/ui.xml` 后 `adb pull`；屏幕熄灭时抓不到 keyguard，要在锁屏亮着时抓）。真机清单（1080×2400）：

    ```
    keyguard_shortcut_container[1080x289 @0,2111]
      keyguard_shortcut_layout[1080x289 @0,2111]
        shortcut_view_left_layout[289x289 @0,2111]    ← 手电筒
        shortcut_view_right_layout[289x289 @791,2111] ← 相机
    keyguard_indication_text_bottom[160x54 @460,2230]
    ```

    即底部 289px 高的带子，中心 y=2255（与底部提示文案中心 2257 基本一致）。实现上 `findShortcutAnchor()` **按 id 优先**（依次试 `shortcut_view_left_layout` / `shortcut_view_left` / `keyguard_shortcut_container` / `keyguard_shortcut_layout`，资源在 `com.android.systemui` 与 `miui.systemui.plugin` 两个包里都试一遍），加上**按几何特征兜底**（可见、32~120dp 见方、落在窗口底部 22% 带子里、中心点横向贴边 22% 之外）。对齐结果：入口 `bottomMargin` 由固定 `dp(10)` 改为 `height - anchorCenterY - 入口高度/2`，实测由 26px 变 79px，中心正好落在 2255。

    两个坑：① **必须在真的站在锁屏上时才量**——场景经常是在桌面或灭屏时 `preCreate()` 好的，那一刻 keyguard 底部什么都没有（`keyguardLocked() && interactive()` 两个条件都过才量）；② `showMusic()` 每次媒体回调都会走（播放时约每秒一次），所以**尝试次数要封顶**（`entryAligned` 成功后直接返回，失败最多 5 次，诊断清单只打前 3 次）。

    注：这两个快捷图标所在的层在窗口根里，和我们的 `background` 一样；`background` 插在它们**下面**（`windowRoot.addView(background, 0)`），所以背景不会盖住手电筒/相机。

29. 2026-10-07 **解锁时「沉浸式壁纸停顿一下」（时有时无）**。背景层为修「上滑露壁纸」挂在**窗口根**，不会随锁屏根被系统带走；而真机采样显示锁屏根在 `t=+36ms` 就已经 `INVISIBLE / alpha=0`，我们却要等 `finishSuspend`（`+150ms`）才把背景 `GONE`。中间那 100 多毫秒里屏幕上是**一张静止不动的模糊封面** —— 这就是那一下停顿；系统退场动画时长每次都不同，所以时有时无。改法：`suspend()` 里让背景跟前景**同节奏淡出 120ms**（硬隐藏仍由 `finishSuspend` 兜底），`resume()` / `finishSuspend` 复位 `alpha=1f`。**注意 `resume()` 必须把背景 alpha 复位**，否则锁屏重现时背景是透明的。

30. 2026-10-07 **「读不到配置」≠「模块被关掉」**。`Config.enabled()` 在 SystemUI 侧靠 ContentProvider 读，查询**偶发**失败（配置端正在写盘、Provider 被整理）时旧代码 catch 后 `return false`，于是「改一次外观」就有一小段概率把模块判成关闭 → `restore()` 撤场景 → 用户看到「改完没反应 / 改完锁屏上什么都没有」，而且时好时坏。改为新增 `Config.enabledOrNull()`：**读不到返回 `null`**；`updateSwitch()` 与 `render()` 收到 `null` 时**保持现状不动**（读到 `false` 才真的撤层，失败关闭原则不变）。同一轮把三档背景样式做出区别（见第 27 条）：`深色玻璃` blur 28dp 遮罩原样、`浅色玻璃` blur 14dp 遮罩×0.55、`纯色沉浸` 不铺封面铺纯色 —— 之前只差 28/18dp 模糊，肉眼等于没差。

31. 2026-10-07 **解锁卡顿 + 「息屏后快速解锁闪原生壁纸」**（用户反复锁解锁后抓日志定位）：

    ```
    17:39:41.547  SCREEN_OFF kept=… suspended=true interactive=false
    17:39:41.576  resume reused scene        ← 熄屏动画里就 resume（屏幕正在黑）
    17:39:42.089  SCREEN_ON
    17:39:43.449  suspend reason=predraw-keyguard-unlocked
    ```

    **卡顿**：`unlockWatch` 探针每 30ms 采样一次、**每次解锁打 50 行 logcat**，实测把采样间隔拖成 87~157ms（12 次超过 60ms）——logcat 写入是同步的，这个诊断本身就在制造用户看到的卡顿。它给出的结论早已拿到（系统把 keyguard 根直接置 `INVISIBLE` + `alpha 0`、全程无位移），**整个删掉**；现象定位靠 `suspend reason=` 与 `native layers handed back at t=+Nms` 两行就够。

    **闪原生壁纸**：第 27 条那版「外观变了就整场重建」的路径在 `restore()` 之后**提前 return，没走到 `showMusic()`**，而隐藏原生壁纸层/时钟层的动作正在 `showMusic()` 里 —— 重建那一百来毫秒里原生壁纸是可见的。改法是把外观指纹**拆成两份**：

    - `backdropSignature`（背景样式 / 遮罩颜色 / 遮罩强度 / 封面圆角）→ 只调 `applyBackdrop()` **就地更新三层背景，不动视图树**（背景的纯色层与遮罩层提为字段 `solidFill` / `baseScrim`）；
    - `elementSignature`（三元素参数）→ 才 `restore("elements-changed")` 并在下一帧 `render()` 重建。

    顺带收益：改背景样式不再重建场景，锁屏重现时直接生效，也不会再有露出原生壁纸的那一瞬。

    还有一个周期性卡顿源：媒体层每 2 秒交一次快照（带进度），而 `applySnapshot()` 每次都
    `setImageBitmap(snapshot.art)` —— **给 ImageView 重设同一张 bitmap 也会触发重绘**，
    等于每 2 秒强制重渲染一次**全屏模糊层**。改为用 `shownArtwork` 比对，只在封面真换了才重设，
    文本与进度照旧每次更新（很便宜）。

32. 2026-10-07 **「亮屏时快按两下开机键，息屏后没再亮起来」**。用户反复操作后抓日志，事实是：
    失败那次 `SCREEN_OFF` 之后 **72 秒内完全没有 `SCREEN_ON` 广播**，模块侧无崩溃、无撤层、
    SystemUI PID 全程未变 —— **第二次按键根本没被系统受理，不是模块吞了键**
    （亮屏再点一次会亮，说明通路本身没问题；快按两下时按键落在熄屏动画窗口里被系统丢弃，
    这类现象在原 ROM 上也常见）。

    但日志里确实暴露了**一处 ours 的无用功**：熄屏后 **49ms** 就出现了
    `resume reused scene … interactive=false` —— 屏幕已经全黑，pre-draw 守卫却在那条
    「屏幕正要黑」的窗口里把两层重新置为可见并 `bringToFront`（触发整棵窗口根 relayout）。
    第二次按键恰好也落在同一个窗口里。改法：守卫的 suspended 分支加 `interactive()` 条件，
    **屏幕还黑着就不 resume**。亮屏秒显不受影响——它靠的是 `SCREEN_ON` 广播与亮屏后第一帧
    pre-draw，两者都在屏幕亮起之后。

    另外给 `SCREEN_ON` 补了 `offFor=Nms`（熄屏到亮屏的毫秒数），下次这类问题可以直接和用户的
    按键节奏对账，不用再靠猜。

33. 2026-10-07 深夜 ~ 10-08 凌晨 **解锁时闪「一帧原生壁纸」——两处根因，一次说清**。

    用户给了一张解锁瞬间的截图：屏幕上是**一张清晰的原生壁纸**，我们的界面只剩一层淡影。
    抓日志（23:43:47 一次真实解锁，以系统 `keyguardGoingAway` 为 0 点）后真相很干净：

    ```
      +47ms  wms.showSurfaceRobustly …ImageWallpaper        ← 桌面壁纸窗口被拉起
     +127ms  wms.showSurfaceRobustly …Launcher              ← 桌面窗口上屏
     +148ms  KeyguardService Starts IRemoteAnimationRunner  ← 系统解锁动画**开始**
     +340ms  updateKeyguardWallpaperStateAnim anim=true     ← MIUI 开始收锁屏壁纸
     +540ms  （我们的 pre-draw 守卫发现 keyguard 不锁了）
     +685ms  updateKeyguardWallpaperStateAnim onAnimationFinished ← 动画**结束**
     +742ms  WallpaperWindowToken{lock} isVisible=false / {desktop} isVisible=true ← 换壁纸落地
    ```

    **根因 ①（时序盯错了对象）**：前几版一直在猜「桌面窗口什么时候上屏」（+111~205ms），
    从 hold 130/240 一路调到 280/90，总还闪。因为真正该对齐的不是桌面窗口，而是
    **系统自己的解锁退场动画有多长**——它从 +148ms 跑到 +685ms。我们 280ms 就开始淡、370ms 撤层，
    正好落在动画中间，露出的就是系统正在展示的原生壁纸。
    **改法**：`UNLOCK_COVER_HOLD_MS = 760`（越过动画结束 +685ms 与换壁纸落地 +742ms）、
    `UNLOCK_COVER_FADE_MS = 160`，约 920ms 摘层。

    **根因 ②（时间线之外，更要命的结构问题）**：`suspend()` 里原本有一句
    `main.removeCallbacks(finishCoverFade)`，它把「压到 760ms 再淡」那条链路**当场掐掉**，
    于是实际撤层时间由 `finishSuspend` 的 600ms 决定 —— 参数怎么调都不会生效。
    **改法**：有解锁信号时**绝不** cancel `finishCoverFade`；`finishSuspend` 退化成兜底，
    延时按「剩余保持期」动态计算（`HOLD + FADE + 150 - elapsed`），保证永远落在淡出之后；
    并在 `finishSuspend` 开头加「链路已收尾就直接 return」，避免谁收的尾看不出来。
    同时把 `restoreCoverIfStillLocked` 兜底从写死的 800ms 改成 `HOLD + FADE + 300`，
    否则它会在淡出中途把不透明封面又贴回去。

    **顺带修掉「手电筒/相机图标消失」**：同一处 z 序问题。上一轮为了盖住原生壁纸，把封面插到了
    锁屏根**最顶层**，而底部快捷栏（`KeyguardBottomAreaView` → `keyguard_shortcut_container`）
    也在锁屏根里 → 图标被这层不透明封面压住（还能点进去，但看不见）。
    **改法**：锚点改成「原生锁屏壁纸层」，插在它后面一位。真机普查确认（`keyguardRoot children`
    + `ancestorChain` 两行诊断）：

    ```
    HyperOSKeyguardRootView#keyguard_root_view[7]
      ├─ [0] KeyguardPanelView#keyguard_panel_view
      │        └─ [0] FrameLayout#keyguard_background_layer   ← 原生锁屏壁纸
      ├─ [1] FrameLayout  ← 我们的 background  ✅ 壁纸之上
      ├─ [2] MiuiKeyguardStatusBarView#keyguard_header
      ├─ [4] KeyguardBottomAreaView#keyguard_bottom_area
      │        └─ [5] FrameLayout#keyguard_shortcut_container  ← 手电筒/相机图标
      └─ [6] FrameLayout  ← 我们的 foreground
    ```

    两条 z 序约束（**原生壁纸之上、底部快捷栏之下**）由「紧跟 `keyguard_background_layer`」一条锚点
    同时满足；`nativeBackgroundLayer` 不是锁屏根直接子视图（挂在 `panel_view` 里），所以使用
    `indexOfChild` 判断父容器、失败才退到第 1 位。另加 `ensureBackgroundOrder()`，在 `resume()` /
    `applyPreShow()` 里按需纠正 z 序（顺序对就不动视图树）。

    **踩坑**：改完后 `./gradlew.bat :app:assembleDebug` 报 `compileDebugJavaWithJavac UP-TO-DATE`、
    `packageDebug UP-TO-DATE`，装上去的还是上一版 —— 源码明明已改（`grep` 确认过）。
    强制办法：删掉 `app/build/intermediates/javac` 再构建；**装完务必验证 dex**：
    解包 APK 逐个 `classes*.dex` 搜特征字符串（本轮搜 `ancestorChain`，命中在 `classes16.dex`）。

34. 2026-10-08 凌晨 **上面第 33 条改完，用户复测「进桌面还是有原生壁纸，跟之前一样」——真正的根因是结构，不是时间**。

    日志显示我们自己的链路执行得**无懈可击**：

    ```
    00:11:37.363  Unlock signalled; cover stays opaque for 760ms
    00:11:37.729  suspend reason=predraw-keyguard-unlocked
    00:11:38.410  unlock cover removed at t=+1047ms
                  native[keyguardRoot=I0.00 wallpaper=I1.00 nativeFg=I1.00 clock=I1.00 …]
    ```

    封面全程 `cover=V1.00`（VISIBLE 且 alpha 1），760ms 才开始淡、1047ms 才摘层。
    **但 `keyguardRoot=I0.00`** —— 系统在这趟解锁里把**整棵 `HyperOSKeyguardRootView`
    置成了 INVISIBLE + alpha 0**。我们的两层都挂在锁屏根**里面**：父控件一被隐藏，子视图
    **连绘制都不参与**，把自己设成 `VISIBLE / alpha 1` 也救不回来。

    这就是 hold 从 130 → 240 → 280 → 760 怎么调都无效的原因：**不是时间不对，是我们压根没被画出来**。

    **改法：解锁时把封面挪出锁屏根**（`liftCoverToWindowRoot()`）。在 `keyguardGoingAway` 这一刻
    （系统动画 +148ms 才开始）把不透明封面从锁屏根挪到**窗口根、紧贴锁屏根之上**；挪出去之后就不受
    锁屏根可见性的影响，掀盖时机才真正由我们掌握。

    - 两个操作（remove + add）在**同一个主线程消息**里完成，不存在「两边都没有」的那一帧；
      此刻屏幕内容不变（仍是同一张不透明封面）。
    - 解锁期间底部快捷栏图标本来就在随锁屏根一起退场，被压住没有副作用。
    - 重新锁屏时由 `ensureBackgroundOrder()` 把它**挂回**锁屏根，回到「壁纸之上、快捷栏之下」，
      否则快捷栏图标会被压住。

    **改完之后用户复测：方向对了，原生壁纸看不到了** —— 但暴露了新的观感问题（见第 35 条）。

35. 2026-10-08 凌晨 **「不闪了，但滞留在专辑壁纸很久，看不到桌面入场动效，观感有点卡」**。

    这是第 34 条那版参数**自己造成的**：桌面入场动效是 +148~685ms，而我们压到 +760ms 才让开，
    等于把**整段桌面动效都挡在盖子后面**。用户看到的是「音乐界面定住 0.7 秒，然后桌面已经站好了」。

    **改法：从「压住再一次性让开」改成「跟着系统动画做交叉溶解」。**

    | 参数 | 上一版 | 这一版 |
    |---|---|---|
    | `UNLOCK_COVER_HOLD_MS` | 760 | **200** |
    | `UNLOCK_COVER_FADE_MS` | 160 | **460** |
    | 插值器 | 默认（加减速） | **`LinearInterpolator`** |

    淡出窗口 200~660ms 覆盖系统动画（148~685ms）的后 2/3，用户能一路看到桌面渐显；
    前 200ms 仍保持不透明，避开动画开头「桌面还没完全合成」的那一段。
    插值器必须显式设线性：`ViewPropertyAnimator` 默认是加减速曲线，中段掉得比桌面渐显更快，
    反而更容易在中间态露出壁纸。

    **这两个常数是一对可以左右手的旋钮**（已写进 javadoc）：早淡/快淡 → 更看得到桌面动效，
    但中间态可能混进壁纸（交叉溶解下最多约 25% 混合，是软的、不闪）；晚淡/慢淡 → 更不可能露壁纸，
    但越像「定住一下才进桌面」。

    **排查顺序要改**：这类「露原生壁纸」问题，**先看父容器还活不活，再看自己的可见性/alpha**。
    `nativeCensus()` 里的 `keyguardRoot=` 一项就是为此存在的，它比 `cover=V1.00`（只说明我们
    自己的层还在、可见）更能说明问题。本轮补了三个采样点（信号 +0ms / 淡出起点 +760ms /
    摘层 +920ms）来定位系统到底在什么时候把锁屏根藏起来。
