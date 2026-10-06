# 沉浸音乐锁屏（实验版）

独立的 Android Vector/LSPosed 模块；不修改 Cool Music 或其他播放器。只给 `com.android.systemui` 注入一个锁屏视图适配器。媒体发现、封面和播放控制使用 Android `MediaSessionManager` / `MediaController`。当前仅为指定真机构建开放，默认关闭。

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

- **首页**：对齐 HyperIsland 首页版式 —— 顶部大标题加刷新按钮，第一行是「方形激活卡（大号对勾底纹）」加右侧两张数据卡，下面是（按需出现的）适配告警卡、系统信息卡、链接卡和使用说明卡。
  - 状态卡点击即切换模块总开关，标签在 `已激活` / `未激活` 之间切换。
  - 左数据卡为「已开启应用」，取 `Config.enabledAppCount()`：未设置时显示 `全部`（表示允许所有播放器），全部取消时显示 `0`，否则是勾选数量；点击跳到「音乐应用」页。
  - 右数据卡为「封面圆角」，取 `Config.cornerRadiusDp()`；点击跳到「外观」页。
  - 告警卡仅在 `Config.deviceSupported()` 为假（设备构建指纹不在已验证列表内）时出现，与 `HookEntry` 的加载门禁同一判据。
  - 系统信息卡四行：系统版本、应用版本、Xposed 框架、设备型号。前三行中 Xposed 框架依赖 Vector/LSPosed 服务绑定（`XposedPrefsSyncApp.awaitReady()`）；legacy 模块拿不到服务时显示 `未知`。
- **音乐应用**：对齐 HyperIsland「应用」页 —— 搜索栏加应用行（图标、名称、包名、开关），整行点击也可切换。列表扫描声明 `MediaBrowserService` 的已安装应用；未设置表示允许全部，取消勾选后落成显式白名单，全部取消后锁屏不再接管任何播放器。
- **外观**：设置封面圆角、深色/浅色/纯色背景、遮罩颜色和强度。配置通过只读 `ContentProvider` 同步给 SystemUI。
- **开发者**：开发者与联系方式、项目链接、开源说明。**作者署名与全部外链当前留空**，页面显示「待填写」并置灰不可点击。

四页共用的卡片来自 HyperIsland 原版实现：`OverviewPage.kt` 里原先私有的 `StatusGrid` / `StatusCard` / `StatCard` / `InfoCard` / 告警卡已提升为 `internal` 的 `OverviewStatusGrid` / `OverviewStatusCard` / `OverviewStatCard` / `OverviewInfoCard` / `OverviewAlertCard`，只把标题与数值参数化，视觉与交互代码未改动。HyperIsland 自己的首页（`OverviewPage`）改为调用同一批组件，因此不存在第二份样式实现。音乐应用页的行样式沿用 HyperIsland `AppsPage` 的 `Card` + `BasicComponent` 组合，图标复用 `InstalledAppsRepository` 的缓存与解码逻辑。

AppShell 的根分页把 `isActive` 传给首页，首页在重新可见时重读配置，因此从其他页改完设置回到首页，数据卡会刷新。

原有 Java/Xposed 锁屏适配链路保留；Compose 页面通过公开的 `Config` API 与同一 ContentProvider 配置同步。`Config.java` 新增 `selectedPackages()`、`allPackagesDisabled()`、`enabledAppCount()`、`deviceSupported()` 四个只读辅助方法，用于区分「未设置（允许全部）」与「已全部取消」两种空集，没有改动任何配置键或读写语义。构建环境已升级到 AGP 9.3.1、Gradle 9.5、Kotlin/Compose 2.4.10。

界面文案现状：这四个页面沿用原有实现，标题与说明文本直接写中文，只有导航栏、系统信息行和链接行使用 `strings.xml` 资源；`待填写` 也是硬编码中文。后续要补多语言时需要一并抽到资源。

为避免把 HyperIsland 的更新内容带入本项目，当前配置端已关闭启动时环境统计，并在最终 APK 中移除 `INTERNET` 权限。HyperIsland 的更新检查、预设云端下载和外部资源链接源码仍随迁入代码保留，但当前四页不会调用；APK 不声明安装权限，也不会自动安装其他 APK。

- 播放中的标准 MediaSession 提供标题、歌手、封面及上一首、暂停、下一首操作；支持封面 Bitmap 和 URI。无播放会话、封面无效、URI 读取失败时恢复原生锁屏。
- 封面显示在中间，同一封面经 `RenderEffect` 模糊并加暗色遮罩作为背景。顶部用 `TextClock` 显示时间，下方显示播放控制。
- 有媒体时收起原生时钟及通知栈；`展开通知` 按钮可恢复原生通知区域，`返回播放器` 可收起。原生解锁与紧急操作视图没有被移除，底部区域留给系统交互。
- 0.1.2 增加锁屏态门禁：仅在系统报告锁屏、屏幕交互中、媒体会话和封面有效时创建覆盖层；并以 `HyperMusicScapeLock` 标签记录钩子、视图适配和开关状态。
- 应用内开关默认关闭，保存在模块私有配置中；SystemUI 通过模块的只读配置接口读取，不需要“修改系统设置”权限。后台钩子仍须先在 Vector 中启用并以 `SystemUI` 为唯一作用域。

## 构建与安装

使用 JDK 17+、Android SDK 36 和 Gradle 9.4.1。执行 `./gradlew :app:assembleDebug`（Windows 为 `./gradlew.bat :app:assembleDebug`），产物为 `app/build/outputs/apk/debug/app-debug.apk`。若 SDK 未自动找到，设置 `ANDROID_HOME`。

Windows 上 `:app:dexBuilderDebug` 偶尔会以 `Unable to delete directory ... project_dex_archive` 或 `desugar_graph\\...\\graph.bin (拒绝访问)` 失败：这是杀毒/索引进程仍占用刚生成的 `.dex`，不是代码问题（Kotlin 与 Java 编译此时已通过）。删掉被占用的中间目录后重跑即可，必要时降并发：

```bash
rm -rf app/build/intermediates/project_dex_archive app/build/intermediates/desugar_graph
./gradlew.bat --no-daemon --max-workers=2 :app:assembleDebug
```

增量构建时这两个目录可能残留，建议先清理再判断是否编译失败。

安装：

```powershell
adb -s 1b3a7d8 install -r app/build/outputs/apk/debug/app-debug.apk
```

在 Vector 中启用“沉浸音乐锁屏”，只勾选 `com.android.systemui`，然后按 Vector 提示重启 SystemUI 或设备。首次启动应用时开关应显示关闭；先确认无音乐锁屏、通知、解锁和紧急操作都保持原样，再开启模块开关。之前授予的“修改系统设置”权限不再使用，可以在系统设置中撤销。

## 真机逐步验收

1. **关闭状态**：安装后保持开关关闭。锁屏、解锁、紧急拨号和通知完全使用原生界面。此阶段不要求播放音乐。
2. **单个播放器**：开启开关，使用一个提供标准 MediaSession 的音乐应用播放带封面的曲目。确认时间在上、封面在中、控制在下，背景模糊来自封面；暂停或结束后原生锁屏立即恢复。
3. **通知与安全入口**：有普通通知时确认默认收起、点击“展开通知”能看见通知、点击“返回播放器”能返回。再分别确认手势解锁、密码/指纹入口及紧急操作。
4. **跨播放器**：依次测试 Cool Music 和另一款提供 MediaSession 的播放器，包括上一首/下一首、切歌换封面、无封面、停止播放和多会话切换。
5. **故障日志**：若没有效果或 SystemUI 异常，记录 `adb logcat -d -s LSPosed:* HyperMusicScapeLock:* AndroidRuntime:E` 和 Vector 模块日志；不要继续扩大系统版本适配。

第一次现场验收暴露了开关故障：0.1.0 向 `Settings.System` 写入自定义键时，设备抛出 `IllegalArgumentException: You cannot keep your settings in the secure settings`，导致应用闪退，开关并未开启。0.1.1 改用模块私有配置和只读接口，不再请求 `WRITE_SETTINGS`。已在设备内验证配置开启→只读接口读取→关闭的往返测试，最终状态为 `enabled=0`；更新后已重启设备，SystemUI 进程保持运行。设置页按钮的手动点击与锁屏交互仍待验收。ADB 已确认该 SystemUI 获 `MEDIA_CONTENT_CONTROL` 权限。**锁屏交互及第 2–4 步尚未完成真机验收**，不应将其视为稳定版；仍须核实视图层级、通知入口位置及解锁/紧急操作触控区域。该设备拒绝 ADB shell 注入按键事件（缺少 `INJECT_EVENTS`），这些交互须在设备上手动测试。

第二次现场验收中，应用开关可以开启，但锁屏未变化，音乐播放后立即暂停。系统 `MediaSessionService` 在 2026-10-06 09:33:52 记录了 `callingPackage:com.android.bluetooth` 发出的 `KEYCODE_MEDIA_PAUSE`，之后播放器状态变为 `PAUSED`；这次暂停事件并非模块的自定义按钮调用。需在蓝牙关闭时复测，以区分蓝牙设备/系统服务与模块行为。锁屏覆盖层仍未验收通过。

0.1.2 安装并重启后，开关仍为 `enabled=1`，但当前 logcat 中没有 `HyperMusicScapeLock` 钩子日志，也没有 Vector 加载本模块的记录；同一次启动能看到 Vector 加载其他模块。下一步须核实 Vector 中本模块总开关与 `com.android.systemui` 作用域是否实际生效，再用诊断日志判断根视图和媒体条件。设备同时保持蓝牙开启且有连接，蓝牙关闭对照尚待完成。

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

## 故障恢复

优先在模块应用中关闭开关。无法操作应用时，从已授权的电脑停用模块应用，随后重启设备；恢复前保持停用：

```powershell
adb -s 1b3a7d8 shell pm disable-user --user 0 io.github.hypermusicscape.lock
adb -s 1b3a7d8 reboot
```

设备已验证 `pm disable-user` 可以停用本应用；修复后可用 `adb -s 1b3a7d8 shell pm enable io.github.hypermusicscape.lock` 重新启用。若 SystemUI 无法正常工作，也可在 Vector 中停用本模块的 `SystemUI` 作用域并重启；必要时通过恢复模式停用 Vector 模块。模块不改系统 APK、壁纸或播放器数据。再次测试前保存 SystemUI/Vector 日志。该 ROM 已拒绝 0.1.0 文档中的 `settings put system` 以及 `pm clear`，都不能作为恢复手段。

## 许可证与参考

本仓库按 AGPL-3.0 发布。仅参考 HyperMusicCover 的产品思路，没有复制其源码、资源或钩子；其澎湃 OS 4 适配未用于本项目。Vector 的 legacy Xposed API 用作 `compileOnly` 依赖，不打包到 APK。

## 后续

先完成上面的真机验收并修复观察到的问题，再增加其他系统版本的适配器和对应构建门禁。

界面部分待办：

1. 2026-10-06 的首页/音乐应用/外观/开发者四页改版已构建通过并确认进入 APK（APK 内可见 `enabledAppCount`、`OverviewStatusGrid`、`已开启应用`、`待填写` 等新增符号），**但尚未做真机视觉验收**：安装时设备 `1b3a7d8` 已从 ADB 掉线。
2. 重新连接设备后按上面的安装命令刷入，重点看：状态卡点击切换是否可靠（这是模块唯一的开关入口）、两张数据卡的数字是否与「音乐应用」页勾选状态一致、数据卡点击跳页是否正确、暗色主题下绿色/红色状态卡对比度。
3. 作者署名与三个外链常量留在 `LockScreenPages.kt` 末尾的 `TODO(作者信息)` 处，填上后对应行会自动从「待填写」置灰恢复为可点击。
4. 中文文案目前硬编码在这四个页面里，补多语言时需抽到 `strings.xml`。
