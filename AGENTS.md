# AGENTS.md

## 项目约定

- 完成代码改动后同步更新相关 Markdown 文档。**`README.md` 只做面向使用者的项目介绍**（简介、功能、截图、适配、安装、构建、许可），实现细节 / 排查手册 / 变更日志写进 `docs/DEVELOPMENT.md`，工程约定写进本文件。改到截图或图标时同步 `docs/images/`。
- 本项目是 Java Android + Vector/LSPosed 模块，配置端已迁入 HyperIsland 的 Kotlin/Compose/Miuix `app` 源码；修改配置字段时必须同时检查 `Config.java`、`ConfigProvider.java`、Compose 页面和 SystemUI 侧读取逻辑。
- 锁屏覆盖层默认失败关闭：找不到目标 SystemUI 视图或媒体数据无效时恢复原生界面。
- 界面只做「复用 HyperIsland 原版组件 + 换数据源」，不新写样式；同名卡片直接提升 `OverviewPage.kt` 里的实现为 `internal` 共享，禁止复制第二份。**照搬上游组件时要把配套的 `graphicsLayer` 一起搬**：凡是内部用 `BlendMode`（尤其 `DstIn`/`SrcIn` 蒙版）的绘制，必须带 `compositingStrategy = CompositingStrategy.Offscreen`，否则会拿整块 surface 当混合目标——表现为蒙版底边留硬边、颜色染到相邻页面（2026-10-07 开发者页/外观页已踩过）。
- `LockScreenOverlay.java` 由 Hook 注入 SystemUI 进程：任何改动都必须失败关闭（异常退回原生锁屏），并且**不要与其他会话/人工编辑并行改这个文件**。
- **全屏封面（`background`）的 z 序只有一个正确位置：锁屏根里、紧跟原生锁屏壁纸层 `keyguard_background_layer`**。真机普查（`keyguardRoot children` + `ancestorChain` 两行诊断）确认锁屏根 `HyperOSKeyguardRootView` 的结构是：`[0] KeyguardPanelView`（内含 `[0] keyguard_background_layer` 原生壁纸）、`[2] keyguard_header`、`[4] KeyguardBottomAreaView`（内含 `keyguard_shortcut_container`＝手电筒/相机图标）、`[6]` 我们的 foreground。两条约束**必须同时满足**：① 在原生壁纸**之上**（否则原生壁纸重显就盖住我们，用户看到「只有一张原生壁纸」）；② 在底部快捷栏**之下**（否则图标被不透明封面压住，表现是「图标不见了但还能点进去」）。锚点用「壁纸层的父容器 + 紧跟其后」，不要用写死的下标。
- **排查「露原生」先看父容器还活不活，再看自己的可见性/alpha**（2026-10-08 血的教训）：系统解锁时会把**整棵锁屏根 `HyperOSKeyguardRootView` 置 INVISIBLE + alpha 0**，挂在他里面的层**连绘制都不参与** —— 把自己设成 `VISIBLE / alpha 1` 完全没用（hold 从 130 调到 760 全部无效，就是这个原因）。`state()` 里的 `cover=V1.00` 只说明我们自己的层还在，说明不了它有没有被画出来；要看 `nativeCensus()` 里的 `keyguardRoot=`。**因此解锁信号一到就必须把不透明封面 lift 到窗口根**（`liftCoverToWindowRoot()`），重新锁屏时再挂回锁屏根；两者配套，缺一不可。
- **解锁时「掀盖时机」要对齐的是系统解锁退场动画的长度，不是桌面窗口上屏的时刻**（2026-10-07 踩了四轮）：真机时间线为 `keyguardGoingAway` → +127ms 桌面窗口合成（`showSurfaceRobustly …Launcher`）→ +148ms 系统动画开始（`KeyguardService IRemoteAnimationRunner`）→ +685ms 动画结束（`updateKeyguardWallpaperStateAnim onAnimationFinished`）→ +742ms 换壁纸落地。参数见 `UNLOCK_COVER_HOLD_MS`。另外 `suspend()` **绝不能** cancel `finishCoverFade`，否则参数怎么调都不生效（链路被当场掐掉）。
- **`UNLOCK_COVER_HOLD_MS` 的下限 ≈ 150ms，这是硬红线**（2026-10-08 踩过）：桌面窗口要到 **+127ms** 才合成完毕，早于它让开露出的就是还没盖住桌面的原生壁纸窗口（试过 100/280，用户反馈「更容易出现原生壁纸」，已 revert）。**想更看得到桌面入场动效，只能减 `UNLOCK_COVER_FADE_MS`（更快淡完），不能再减 HOLD**；淡出必须用 `LinearInterpolator`（默认加减速曲线中段掉得比桌面渐显更快，反而更容易露中间态）。
- 新增锁屏可调参数时：键名与默认值加到 `Config.java` 的 `ELEMENT_DEFAULTS`，Provider 走 `/elements` 的 key/value 通道，**不要**再去改 `ConfigProvider` 的列投影。
- **首页的真身是 `LockScreenPages.kt` 的 `LockHomePage`，不是 `page/home/OverviewPage.kt`**：后者只提供共享卡片组件（`OverviewStatusGrid` / `OverviewInfoCard` / `OverviewAlertCard` / `HomeOverviewState`），`OverviewPage` 这个 Composable **没有任何调用点、是死代码**。改首页（含右上角按钮）必须改 `LockHomePage`；改 `OverviewPage` 真机上不会有任何反应（2026-10-07 已踩过一次）。
- **装机后必须自证版本，别假设「装了就生效」**（2026-10-07 踩过：源码与设备上的 APK 都是新版、逐 dex 搜字符串确认过，但新起的 SystemUI 进程跑的仍是**上一版** dex，整轮验证白做）。做法：每个入口在 `handleLoadPackage` 里打一行 `rev=<修订串>`（`ShortcutAnimBackdrop` 已这么做），**先看到新 rev 再让人复现**。注意 `ProtectionDomain.getCodeSource()` 在 SystemUI 进程里返回 **null**（模块 dex 由 `InMemoryDexClassLoader` 加载），只能用手工修订串；要离线确认设备上装的是哪一版：`pm path` → `adb pull base.apk` → python 逐个 dex 搜特征字符串。
- **改别人窗口里的东西时，优先「改它已有图层的 background」，不要「插一层自己的视图」**（2026-10-07 血泪）：插自己的视图＝新增一份「每帧都要重画的全屏内容」，实测把转场那 1 秒拖到 ~20fps（用户反馈「很卡」）；而改它已有图层的背景成本不变（都是一张图铺满），并且**显隐归宿主管**——「忘记撤层盖住 app」这类坑在结构上不存在。
- **Xposed 入口不止一个**：`app/src/main/assets/xposed_init` 现在有 `HookEntry`（锁屏覆盖层主入口）、`ShortcutAnimBackdrop`（手电筒/相机转场：把 MIUI 动画窗那层全屏遮罩的 background 换成我们的封面）与 `WallpaperTexProbe`（**临时探针**，rev=WTP-2，2026-10-08：在 `com.miui.miwallpaper` 进程验证「能否把锁屏壁纸纹理换成纯品红」，作用域已加进 `xposed_scope.xml`）。增删入口必须同步这个清单。**临时诊断探针要做成独立入口并只登记在清单里，结论拿到后源码与清单一并删掉**——既不跟并行会话抢文件，也不把观测成本留在产品里。
- **新增 Xposed 作用域 ≠ 生效**（2026-10-08 踩过）：往 `xposed_scope.xml` 加包名只是「模块声明」，**Vector 管理器里不实际勾选就不会注入**。判别方法：目标进程重启后，在 logcat 里找该入口的 `rev=` 行；一行都没有＝没注入（先查管理器勾选，别急着查代码）。本例：`WallpaperTexProbe rev=WTP-1` 在 force-stop `com.miui.miwallpaper` 后零出现，SystemUI 侧 MeloLock 日志正常（57 条）——就是作用域没勾。
- **钩子别按类名去找 MIUI 插件类**：`com.miui.keyguard.shortcuts.*` 是插件化动态加载的，在 SystemUI 基础 `param.classLoader` 里 `findClassIfExists` 全部 NOT FOUND；要动插件窗口就按**窗口标题**认（`WindowManagerImpl.addView`；本 ROM 上 `WindowManagerGlobal.addView` 签名对不上）。`com.android.keyguard.*`（含 `shortcut.MiuiShortcutController`）在 SystemUI 自己那边，可以直接钩。
- **钩子别用 `param.classLoader` 找模块自己的类**：legacy Xposed 下模块类由模块自己的 ClassLoader 加载，字符串查找会 `ClassNotFoundException`；要钩自己人就用类字面量或反射读字段。
- **撤销/回滚这类「一次动很多文件」的 git 操作，做完立刻 `git status` 复核**：2026-10-07 `git revert` 后出现过 `app/` 整树 ~300 项被删（我这条命令只动 4 个文件，疑并行会话所致），恢复命令是 `git checkout -- app`（HEAD 里全在，10 秒恢复）。
- **要钩 ROM 的方法时，「日志文案 ≠ 方法名」**（2026-10-07 踩过）：日志里 `onStartedWakingUp` / `handleNotifyWakingUp` 只是 `Log` 的字符串，按它们精确匹配会让四个候选类全部落空（`KeyguardViewMediator` 真机上根本没有 `handleNotifyWakingUp` 这个方法）。**正确做法**：用 dex 解析找「引用了该字符串的方法」（见 DEVELOPMENT.md 的 DEX 小节），拿到真名后再钩；或者按**名字模糊匹配**（如小写含 `wakingup`）+ 覆盖匿名内部类与接口 default 方法，并把**实际挂上的方法名**打进日志。
- **构建偶发 `BUILD FAILED` 不要先怀疑代码**（2026-10-07 多次）：改动后第一次构建可能失败（与本机杀毒/索引锁 dex 同源），**直接重跑一次，通常 1~2 分钟就过**；真正的编译错误会在 `^e:` / `.java:N: 错误` 里明确给出文件名与行号，先看错误落在谁的文件上。报告里出现 `desugar_graph/**/graph.bin (拒绝访问)` 时，删掉 `app/build/intermediates/{desugar_graph,project_dex_archive}` 再跑。
- **亲眼确认「改动的确进了 APK」再装机**（2026-10-07 踩过）：改完源码后构建可能报 `compileDebugJavaWithJavac UP-TO-DATE` + `packageDebug UP-TO-DATE`，**装上去的还是上一版**（`grep` 确认源码已改也没用）。强制办法：删掉 `app/build/intermediates/javac` 再构建；装之前先解包 APK 逐个 `classes*.dex` 搜本轮新增的特征字符串，命中才算数。
- **锁屏封面不只有「在 SystemUI 里叠 View」这一条路**（2026-10-08 参考项目调研，详见 `docs/RESEARCH-lockscreen-approaches.md`）：同类模块的做法是进 **`com.miui.miwallpaper` 进程**，在 GL 上传点把专辑图替换成壁纸纹理。动机是 **MIUI 的时钟液态玻璃与通知卡模糊采样的是壁纸窗口，SystemUI 的 View 树永远采样不到**——我们「把背景层提升到 `windowRoot`」在结构上补不了这个洞。这条路的全部前置符号已在本机核实存在（见调研文档）。**动手前先读那份文档**，别在 SystemUI 视图树里继续堆层。
- **要钩的方法是 lambda 时，名字会被 D8 改成带 `$com-厂商-类` 后缀的形式**（2026-10-08 核实）：本机 dex 里的真名是 `lambda$onSurfaceCreated$0$com-miui-miwallpaper-opengl-ImageWallpaperRenderer`，而参考项目仓库里写的是 `lambda$onSurfaceCreated$0`。**按精确名 `getDeclaredMethod` 必然落空**，要按名字前缀匹配 `getDeclaredMethods()` 遍历，并把实际挂上的名字打进日志。
- **查资源 id / 布局名要在 `resources.arsc` 里搜，不要在 dex 里搜**（2026-10-08 踩过）：dex 中只有编译后的 id 常量，`keyguard_translation_info` 这类名字只在 arsc 中出现。本机 `MiuiSystemUI.apk` 的 arsc 里 `keyguard_translation_info` / `keyguard_background_layer` / `keyguard_foreground_layer` / `keyguard_clock_container` / `keyguard_root_view` 均存在。脚本见调研文档附录。
- **参考合规：别因为仓库顶层许可证就放心照搬**（2026-10-08 核实）：HyperMusicCover 是 **AGPL-3.0**、HyperGlow CN+ 是 **GPL-3.0**、HyperChanger 顶层是 **Apache-2.0** 但其 `hypermusiccover/` 包源自 AGPL 的 HyperMusicCover。**要看被搬的那个包本身的许可证，而不是仓库根目录那份**。
- **装完 APK 必须重启 SystemUI 才会加载新的 Hook 代码**，顺序是先装再重启。**无 root 重启法**：`adb -s 1b3a7d8 shell am crash com.android.systemui`（实测有效，SystemUI 崩掉后自动重启、PID 立刻变化；`am force-stop` 无效、`su` 从 adb 不可用）。锁屏行为异常时先比 `ps` 里 SystemUI 的 ETIME 和 APK 安装时间，再看 `logcat | grep "Elements: clock="` 有没有出现——没有就说明跑的还是旧代码。

## 仓库与发布

- **远端**：`git@github.com:zuige66/Hyper-MeloLock.git`（分支 `main`，SSH 免密已通）。本机没有 `gh` CLI、也没有 GitHub token，**创建 Release / 上传附件必须走 GitHub 网页或 API。**
- **签名**：`melolock-release.keystore`（PKCS12）+ `keystore.properties` 都在仓库根，**已被 `.gitignore` 排除，绝不能提交**；丢了就无法给同一个应用升级，务必另存备份。`app/build.gradle.kts` 只在 `keystore.properties` 存在时才建 `release` signingConfig，否则 release 回退到 debug 签名（那种包不能发 Release）。
- **构建发布包**：`./gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`；校验 `apksigner verify --print-certs -v <apk>`。Release **不开混淆**（Xposed 模块靠类名/方法名字符串定位），并且关闭了 `lint.checkReleaseBuilds`（迁入的 HyperIsland 多语言资源有第三方库遗留的 `ExtraTranslation`）。
- **版本号**：`app/build.gradle.kts` 的 `versionCode` / `versionName`，发版时改这里并打同名 tag。
- **不要提交**：`local.properties`、`.workbuddy/`、临时调试截图 `hmsc-*.png`、任何 `*.keystore` / `*.jks`。**要提交的展示图放在 `docs/images/`**（`icon.png` 为应用图标，供 README 引用）。

## 调试与验证方法论（真机日志驱动）

**取数据**

- 先抓全量再分析：`logcat -d -v time -s MeloLock:V`，过滤用 `grep -E "/MeloLock\("`（**标签后是 PID 不是冒号**），滤噪 `grep -v "Media ready from bitmap"`。
- **量化代替人眼**：日志存成文件，用脚本按每行时间戳算**相邻采样间隔**，>60ms 的就是主线程被占住的时刻，再对照那一刻在跑什么逻辑。靠这招定位到「诊断探针自己打 50 行 logcat 造成卡顿」。
- **视图树 dump 拿真 id/坐标**：`adb shell uiautomator dump /sdcard/ui.xml` + `adb pull`（**必须锁屏亮着，息屏抓不到 keyguard**）。别信网上流传的 id 名字。
- **直接问 Provider 读配置真值**：`adb shell content query --uri content://io.github.melolock.config/state`。
- 状态机日志用 `restore(reason)` + `skip(reason)` 去重；**「某段一条日志都没有」也是关键证据**（通常＝改错文件 / Composable 没被调用）。
- 给事件加**可对账的时间戳**（如 `SCREEN_ON offFor=Nms`），才能把系统事件和用户操作节奏对上。
- **分清事实与推测**：日志能证明什么、不能证明什么，要说清楚（例：「72 秒内无 SCREEN_ON ⇒ 按键未被系统受理」，而不是「大概是我们吞了」）。

**定位**

- 先分清「没生效」是**真没生效**还是**生效了但看不出来**（三档样式只差 28→18dp 模糊 = 用户说没生效，其实分不出差别）。
- 引入「状态跨周期复用」后要立刻想到：**唯一读配置的地方要加指纹比对**，否则配置永远读不到第二次。
- 指纹**分级**：会改视图树的（元素参数）才重建；纯参数（背景样式/遮罩）就地更新。分级顺带能消掉「重建窗口里露出原生层」这类副作用漏洞。
- 怀疑卡顿先查自己的诊断代码（logcat 同步写、每帧日志）；再查重复的重活（同一 bitmap 重设也会重绘 → 全屏 RenderEffect 重渲染）。
- 审计所有**提前 return 的分支**，看是否跳过了必须的动作（隐藏原生层、复位 alpha、重新测量）。

**红线（违反过 / 都会咬人）**

1. **不要在 pre-draw 回调里改视图树**（`addView`/`bringToFront`）→ 排队到下一帧。
2. **不要每帧 `bringToFront()`** → 只在确实不在最上层时动（`ensureOnTop()`）。
3. **不要在每帧路径里打 logcat**（同步写）→ 主线程被占，实测把采样间隔拖成 87~157ms。
4. **临时诊断探针用完必须删**；「只打日志」不是零成本。
5. **不要把「读不到」当成「关闭 / 为空 / 没勾选」** → 显式返回「未知」（`enabledOrNull`），保持上一次状态。
6. **不要只挂 `ViewPropertyAnimator` 回调做隐藏**，必须 `Handler.postDelayed` 兜底（解锁时动画回调可能不推进）。
7. **不要在屏幕还黑/未亮时做重活** → 判 `interactive()`；熄屏动画窗口里的 relayout 会挤压按键/唤醒时机。
8. **不要给 ImageView 重设同一张 bitmap** → 用引用比对去重。
9. **不要在 suspend 期间碰原生壁纸层**（解锁动画靠它，隐藏会露黑底）；时钟层则要继续按住。
10. **新增的早退/重建分支要逐个核对副作用**，别只管主路径。
11. **不要与其他会话/人工并行编辑 `LockScreenOverlay.java`**。
12. **构建必须加 `timeout`**：Windows 杀毒锁 dex 会让构建挂 3 小时才 FAIL。
13. **改动前先 `git commit`**（见下方事故记录）；**改完同步三处文档**。

**验证闭环**：装 APK → 重启作用域（`am crash` 或模块广播）→ 让用户复现 → 读日志对账 → 提交。
设备拒绝 ADB 注入输入事件，UI 交互只能人工；SELinux 拦 shell 写 app data，配置只能由用户在 App 里改。

## 最近完成

- **熄屏快速亮屏露原生锁屏（2026-10-07）** —— 日志显示模块**全程没撤过层**（`fg/bg=true`），问题在可见性时序：`suspend()` 之后场景是 `GONE`，而 `resume()` 只能等 `SCREEN_ON` 广播（**送达时面板已经亮了**）或亮屏后第一帧 pre-draw，中间那几十~两百毫秒就是原生锁屏。
  - **改法：暗期预显。** `SCREEN_OFF` 之后再等 `SCREEN_OFF_PRESHOW_DELAY_MS = 220ms`（**面板黑透之后**）执行 `preShowForWake`，把可见性提前摆回可见 —— 亮屏第一帧即我们的界面；若亮屏后 keyguard 没锁则 `SCREEN_ON` 分支走 `restore("wake-unlocked")` 收回。
  - **两个刻意的取舍**：① 必须等 220ms —— `SCREEN_OFF` 广播送达时面板还在跑熄屏动画，那会儿改可见性会跟动画抢（这正是之前被迫给守卫加 `interactive()` 闸的原因）；② **只摆可见性、不调 `resume()`** —— `resume()` 会走 `showMusic()` 并启动进度 ticker，熄屏期间每 500ms 唤醒一次，纯耗电。
  - **诊断补强（关键）**：`state()` 现在带 `cover=` / `front=`（`V1.00` 可见且不透明 / `G0.00` 已移除 / `-` 对象不存在）。**排查「露原生」只看 `fg/bg=true` 不够** —— 那只说明对象还在，真正决定露不露的是可见性；这次的日志就是先靠它排除了"模块撤层"这条假设。
  - **踩坑复现**：把 `preShowForWake` 写成字段初始化器里的 lambda，用**简单名**引用后面声明的字段（`foreground`/`background`/`main`）会被 javac 判「非法前向引用」→ 按本项目规矩改**方法引用 `this::runPreShowForWake`**。
- **解锁节奏对齐原生：改用系统侧解锁信号（2026-10-07，B+）** —— 上一轮只砍掉了 120ms 淡出，实测大头还在：**pre-draw 守卫比 `keyguardGoingAway` 晚 277~491ms**，而桌面窗口 `wms.showSurfaceRobustly` 在 `keyguardGoingAway` 后 **111~205ms** 就已可见。于是「桌面已可见却被盖子盖住 120~367ms」+「前景层随 keyguard 根先走、只剩一块纯色约 230ms」——用户原话「看到纯色壁纸然后才进入桌面」「解锁没有原生快」。
  - **做法**：`HookEntry#hookUnlockSignal` 挂 **`com.android.systemui.statusbar.policy.KeyguardStateControllerImpl#notifyKeyguardGoingAway`**（SystemUI 进程内，**不用扩作用域**；AOSP 里 `KeyguardViewMediator#keyguardGoingAway` 就是调它通知所有 Callback，同一时刻）。实测我们的回调 **45.699 比系统的 `keyguardGoingAway, transition` 45.709 还早 10ms**，守卫要到 46.182。拿到起点后背景层改成 **Hold 130ms → Fade 240ms**（取自实测的 111~205ms 窗口），桌面从盖子底下渐显。
  - **找不到方法怎么办**：本机 `KeyguardViewMediator` 类存在但**没有任何 `keyguard*` 方法** → 改为**按方法名匹配、不猜签名**，并打诊断 `none on <类> (methods=N, GoingAway*=…)`。**定位真实方法名的捷径**：`adb shell grep -a -o -E '[A-Za-z_]*GoingAway[A-Za-z_]*' /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk | sort | uniq -c`。装不上就只打日志、回退旧的 pre-draw 时序（失败关闭）。
  - **⚠️ 坑：`notifyKeyguardGoingAway` 会跟着息屏/Doze 一起发，不是只在真解锁时发。** 抓到过一次：信号之后 3ms 就是 `render skipped: display-off`，而锁屏还在 —— 盖子被撤掉，锁屏重现时就只剩原生壁纸。所以 `onUnlockStarting()` 里的 `interactive()` 闸不能省，另加 `restoreCoverIfStillLocked`（+800ms 回头核对：`suspend()` 没被叫到就说明不是解锁，把盖子放回去）。
- **沉浸层「压住桌面」的时机修正：三处改动（2026-10-07）** —— 用户反馈「解锁进桌面有时候会卡、偶尔看到原生壁纸」。日志量化后确认**两件事都不是「盖子漏了」**：
  1. **「卡」＝我们的不透明层盖在已经可见的桌面上。** 系统侧与模块侧的时间戳都在日志里，四点直接对齐：`keyguardGoingAway` → 桌面窗口 `wms.showSurfaceRobustly Window{com.miui.home/Launcher}` 只差 **+92~184ms**，而模块 `suspend()` 要等到 **+301~318ms** 才触发（pre-draw 守卫在等窗口重绘），之后又走 120ms 背景淡出 + 150ms `finishSuspend` 兜底 ⇒ 桌面已上屏后还被多盖 **152~411ms**（`native layers handed back` 实测分布 **+151 / +151 / +152 / +160 / +176 / +216 / +219 / +238 / +298 / +386 / +420**）。改法：**`suspend()` 里背景层直接 `GONE`，不淡出** —— `suspend()` 只在 `keyguardLocked()==false` 时触发，那一刻解锁事务已在跑、桌面已可见，撤层不会露任何壁纸。**别再为了「好看」往 `suspend()` 里加淡出。**
  2. **「偶见原生壁纸」＝会话掉线回退（fail-open），不是盖子漏。** 切歌时媒体会话消失超过 `SESSION_GRACE_MS(1500ms)` → `render()` 走 `restore("render-no-session")` 撤掉**整个场景** ⇒ 原生锁屏连原生壁纸一起原样露出（实测 18:13:14 撤层、18:13:35 才重建，那时用户已回到桌面）。改法：**锁屏上只要有最后一帧（`shown != null`）就绝不撤层**，撤层理由收窄为「不在锁屏（`render-keyguard-unlocked`）/ 模块关闭 / 从没拿到过帧」。连带**删掉整套 `artworkFallback`**（它 2.5s 后的 `restore("artwork-timeout")` 也是执行者）；**取舍（用户已确认）**：音乐 App 被彻底关掉时锁屏留着最后一帧，不退回原生锁屏。
  3. **息屏时 12/16 次白跑 `resume()`**：守卫那行的 `interactive()` 在 SCREEN_OFF 刚发生时**仍返回 true**（模块自己打的日志就是 `interactive=true`），判据拦不住 → 每次息屏白跑一趟 `showMusic()`（含底部快捷栏全树扫描 + 一行超长日志）。新增时间闸 `RESUME_AFTER_SCREEN_OFF_MS = 500`。
  - **验证判据（全走日志，不需要录屏）**：`suspend reason=` 与 `wms.showSurfaceRobustly …com.miui.home…` 的差值应收到 **92~184ms**；`restore reason=render-no-session` 应为 **0**；`SCREEN_OFF` 后紧跟 `resume reused scene` 应从 **12/16 降到 ~0**。
  - **踩坑**：同一轮消息里对**同一个文件**并行发多个 Edit 会互相覆盖（后写者赢，工具仍报 success）→ **一处文件的多处改动必须串行，且每步 grep 复查**。
  - **设备包型**：迭代期设备上装的是 **debug**（`pkgFlags=[ DEBUGGABLE …]`），release 签名装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）→ **用 `assembleDebug` 覆盖安装**，避免卸载重装（会丢模块开关 + 要重新在 Vector 勾作用域）。
- **「亮屏快按两下开机键，息屏后没再亮起来」（2026-10-07）**：日志里失败那次 `SCREEN_OFF` 后 **72 秒内没有任何 `SCREEN_ON`**，模块无崩溃/无撤层/SystemUI PID 未变 → **第二次按键是被系统丢弃的，不是模块吞键**（亮屏再点一次能亮，说明通路没问题；快按两下时按键落在熄屏动画窗口内）。不过日志也暴露一处 ours 的无用功：熄屏后 **49ms** 就 `resume reused scene … interactive=false` —— 屏幕已全黑，守卫却在「正要黑」的窗口里把两层重新置可见 + `bringToFront`（整棵窗口根 relayout），第二次按键恰好也落在同一窗口。改法：守卫 suspended 分支加 `interactive()` 条件，屏幕还黑就不 resume（亮屏秒显靠 `SCREEN_ON` 广播与亮屏后第一帧 pre-draw，不受影响）。另给 `SCREEN_ON` 补 `offFor=Nms`，这类问题以后能直接和按键节奏对账。
- **解锁卡顿 + 息屏后快速解锁闪原生壁纸（2026-10-07）**：
  1. **删掉 `unlockWatch` 探针**。它每 30ms 采样、每次解锁打 50 行 logcat，实测把采样间隔拖成 87~157ms（12 次 >60ms）——**logcat 写入是同步的，诊断本身在制造卡顿**。它要验证的结论早已拿到（系统把 keyguard 根直接置 `INVISIBLE`+`alpha 0`、全程无位移）。**教训：临时探针用完必须删，「只打日志」不等于零成本**；定位现象靠 `suspend reason=` 与 `native layers handed back at t=+Nms`。
  2. **外观指纹拆两份**：背景类（样式/遮罩/圆角）→ `applyBackdrop()` **就地更新三层背景、不动视图树**（`solidFill` / `baseScrim` 因此提为字段）；三元素类（尺寸/字号/间距）→ 才 `restore("elements-changed")` + 下一帧重建。旧写法只有一个指纹、改了就整场重建，而那条路径 `restore()` 后**提前 return 跳过了 `showMusic()`**——隐藏原生壁纸层的动作正在 `showMusic()` 里，于是重建那一百来毫秒原生壁纸可见（用户看到的「息屏后快速解锁闪一下」）。
- **解锁停顿 + 配置读取容错 + 背景样式分档（2026-10-07）**：
  1. **解锁时「沉浸式壁纸停顿一下」（时有时无）**：背景层挂在窗口根（为了盖住桌面壁纸），却要等 `finishSuspend`（+150ms）才 `GONE`，而锁屏根在 +36ms 就已 `alpha=0` —— 中间那张**静止的模糊封面**就是停顿。现在 `suspend()` 里背景跟前景同节奏淡出 120ms，硬隐藏仍由 `finishSuspend` 兜底；**`resume()` 必须复位 `background.alpha=1f`**。
  2. **`Config.enabled()` 把「读不到」当成「关了」**：SystemUI 侧配置靠 ContentProvider 读，偶发查询失败时旧代码 `return false` → 模块被判成关闭、场景被 `restore()` 撤掉，表现为「改完外观没反应 / 锁屏上东西没了」且时好时坏。新增 `Config.enabledOrNull()`（读不到返回 `null`），`updateSwitch()` 与 `render()` 遇到 `null` **保持现状不动**；读到 `false` 才撤层。
  3. **三档背景样式做出区别**：`深色玻璃` blur 28dp / 遮罩原样，`浅色玻璃` blur 14dp / 遮罩 ×0.55，`纯色沉浸` 不铺封面铺纯色。原先只差 28 与 18dp 模糊，肉眼等于没差，用户会认为「调了不生效」。
- **外观改完不生效 / 底部入口对齐 / 渐变背景漏色（2026-10-07）**：
  1. **外观参数（含「背景样式」三项）改完不生效** —— 场景跨锁屏周期复用带来的回归：`create()` 是唯一读配置的地方，而解锁后场景只 `suspend()` 保留、`resume()` 复用，于是 `create()` 一辈子只跑一次。改法：`create()` 收尾算 `appearanceSignature`（元素参数 + 背景样式/颜色/强度 + 封面圆角），`resume()` 比对，不同就 `restore("appearance-changed")` 再用保留的快照重建（**必须 `main.post` 到下一帧**，`resume()` 站在 pre-draw 里不能当场 `addView`）。「纯色沉浸」顺带做出区别：`style == 2` 不铺封面、改铺一层 `overlayColor` 纯色（原来三档只差模糊半径，肉眼分不出来）。
  2. **底部「展开通知 / 返回播放器」入口与手电筒/相机对齐** —— 那两个图标属于 MIUI 的 `com.miui.keyguard.shortcuts`，id 与网上流传的名字对不上。做法是先 `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull` 抓真机视图树（**屏幕熄灭时抓不到 keyguard，必须锁屏亮着抓**），看清后再写判据：真机是 `keyguard_shortcut_container` 里挂 `shortcut_view_left_layout` / `shortcut_view_right_layout`（均 289×289，顶边 y=2111，中心 2255）。实现按 id 优先（两个 resource 包都试）+ 几何兜底；**只在 `keyguardLocked() && interactive()` 时量**（场景常在桌面/灭屏时预建，那会儿底部没有东西），**尝试次数封顶**（`showMusic()` 每秒都会走到）。
  3. **开发者页底边硬边 + 外观页泛蓝** —— 同一处根因：`AnimatedAboutBackground` 用 `BlendMode.DstIn` 画渐隐，照搬时漏了上游的 `compositingStrategy = CompositingStrategy.Offscreen`。**结论：复用上游 Compose 组件时，凡是带 `BlendMode` 的 `graphicsLayer`，`Offscreen` 必须一起搬**，否则蒙版会拿整块 surface 当目标，既留硬边又会染到 `HorizontalPager` 的相邻页。
- **音乐应用「全选」+ 开发者页重做 + 统一新图标（2026-10-07）**：
  1. **全选开关**：音乐应用页新增 `PreferenceSwitch("全选")`，作用范围＝**当前列表**（受搜索与「显示系统应用」过滤影响），勾上＝批量加入这些包名，再点一次＝批量移除；`checked` 由 `filtered.all { selection.isSelected(...) }` 推导，不是独立状态。
  2. **开发者页（`LockAboutPage`）参照上游 `AboutPage` 重做**：整屏滚动列表 + 顶部 hero（图标/应用名/版本号），上滑时 `backgroundAlpha = 1 - offset/389dp` 淡掉、`logoProgress = (offset - 0.25·hero)/0.35·hero` 让 hero 缩小淡出，列表内容看起来「盖上来」。为此把 `AboutPage.kt` 的 `AnimatedAboutBackground` / `rememberAboutAnimationTime` / `animatedGradientColors` 从 `private` 提升为 `internal` 共享（同包，不复制样式）。hero 的逐帧渐变动画只在 `isActive`（`AppShell` 传 `currentPage == 3`）时跑。开发者卡片：圆形头像 `res/drawable-nodpi/dev_avatar.jpg`（`Crop` + `CircleShape`，**不要用上游那张 `about_developer_avatar.jpg`**）+ `zuige` + `@zuige66`，整卡点击开 GitHub；**没有的功能（Telegram 讨论 / 备份恢复 / 检查更新 / 引用 / 隐私政策）一律 `enabled = false` 灰度**，为此给 `SettingsActionWithArrow` 补了 `enabled` 参数。
  3. **图标统一**：新封面 `Hyper MeloLock.png`（1024×1024，薰衣草紫配色）替换 `drawable-nodpi/ic_hmsc.png`（启动图标 + 开发者页 hero）、`docs/images/icon.png`（README）；同时把 `drawable/ic_launcher.xml`（旧蓝色矢量，仍被 `OnboardingPage` 与 `KeepIslandHook` 引用）删掉，改成 `drawable-nodpi/ic_launcher.png`，所有图标引用统一到同一张图。
- **⚠️ 事故与教训（2026-10-07，务必看）**：一条 `cp … && cp … && git rm … && cp …` 的复合命令执行后，**`app/src/main/` 整个子树（296 个条目）从磁盘上消失**（`app/build`、`docs/` 完好，未提交的 4 个源文件改动全部丢失，只能凭上下文重写）。`find` 全盘也没找到副本。**对策**：① **做任何批量/复合文件操作前先 `git commit`**，别让有价值的改动静置在未提交状态；② 二进制资源复制优先用 PowerShell 工具的 `Copy-Item`，不要用 Bash 的 `cp && … && rm` 长链；③ 万一中招：`git reset -q && git checkout -- app/src/main` 可完整恢复（恢复后与 HEAD 零差异已验证）。
- **重启按钮改到真正的首页（2026-10-07）**：上一轮把 root 预检 + 无 root 广播重启加在了 `OverviewPage`，但那个 Composable 没有调用点，`AppShell` 的首页是 `LockHomePage`，右上角的圆圈是它的「刷新状态」（`onAction = { refreshToken++ }`）——所以改完点了照样没反应，抓日志时 App 进程一条 `MeloLock` 都没有。现在逻辑搬到 `LockHomePage`，`OverviewPage` 退回上游原样（同一份逻辑不保留两个版本）。音乐应用页补一行诊断日志 `Music apps: N selected by default`，并改掉与新默认值矛盾的说明文案。**自测无 root 通道**：`adb -s 1b3a7d8 shell am broadcast -a io.github.melolock.action.RESTART_SYSTEMUI -p com.android.systemui` → SystemUI PID 30613→10033，可用（不需要 UI 点击，也不依赖 root）。
- **重启小圈修复 + 默认不勾选（2026-10-07）**，三件事：
  1. **点击无反应的根因**：`RestartScopeService` 用 `Runtime.exec("su")` + 无超时的 `waitFor()`。本机实测 `adb shell su -c id` 返回 **`su: inaccessible or not found`**（`/data/adb/ksu/bin/su` 存在但 Permission denied），也就是说 SukiSU 环境下 App 侧 `su` 根本不可用——`exec` 要么抛 IOException、要么挂起等一个永不响应的 root 管理器，`waitFor()` 卡死 → 既无弹窗也无提示，表现就是点了没反应。现在 `su` 统一走 `SU_TIMEOUT_MS = 3000` 超时（`waitFor(timeout, MILLISECONDS)` 后 `destroy()`），并打日志 `RestartScope: hasRoot=`。
  2. **无 root 也能重启作用域**：新增 `Config.ACTION_RESTART_SYSTEMUI` 广播通道——配置端发现无 root 时发一条 `setPackage("com.android.systemui")` 的显式广播，模块（`LockScreenOverlay`）用 `RECEIVER_EXPORTED` 注册接收，收到后 `Process.killProcess(myPid())` 自杀重启。与 `am crash com.android.systemui` 等价但**不需要 root 也不需要 adb**。有 root 时仍走原来的作用域列表（su 命令）。Toast 用新增的 `restart_scope_requested`。
  3. **默认项**：音乐应用页 `showSystemApps` 默认改 `false`（原 `true`，列表里绝大多数是系统组件）；`loadMusicSelection` 去掉 `unrestricted = packages.isEmpty()`，改为恒 `false`——**默认任何应用都不勾选**（与 `Config.packageAllowed` 对齐）。「全部应用」仍是可切换选项，但不再是默认值。
- **重启作用域改成本模块自己的作用域 + 点开前先探 root（2026-10-07）**：首页右上角刷新图标原本直接弹出 `RestartScopeDialog`，两个问题：① 列表是迁入的 HyperIsland 硬编码 5 项（systemui / miLink / settings / xmsf / 下载管理），**后 4 项我们根本没 hook**；② root 是「点了确定、命令跑失败才提示」。改为：`RestartScopeService` 新增 `hasRoot()`（开 `su` 跑 `id`），`OverviewPage` 的 `onAction` 先探测，有权限才 `showRestartDialog = true`，否则 Toast `restart_root_required`；`RestartScopeDialog` 的 `targets` 用 `stringArrayResource(R.array.xposed_scope)` 与内置列表求交集，**只列 Manifest 里真正声明过的作用域**（当前只有 `com.android.systemui`；将来往 `xposed_scope.xml` 加一项列表会自动跟上）。注意 `hasRoot()` 会触发 root 管理器授权弹窗。
- **未勾选音乐应用则不启用沉浸锁屏（2026-10-07）**：`Config.packageAllowed()` 原本是 `raw.isEmpty() || ...`，**未设置＝允许全部**，用户还没做任何选择时任何 App 的 MediaSession 都能拉起覆盖层。改为空值与哨兵 `NONE` 一律 return false，**必须显式勾选**。`enabledAppCount()` 同步去掉 `-1`（原「允许全部」语义）改为返回实际勾选数。
- **上滑解锁不再露原生壁纸（2026-10-07）**：用户反馈上滑解锁时出现原生壁纸。加 `unlockWatch` 探针（每 30ms 采样锁屏根视图）实测发现：系统在 **`suspend()` 触发之前**就已经把整个 `HyperOSKeyguardRootView` 置成 `INVISIBLE + alpha 0`（首帧采样 t=+36ms 时 root 即为 `alpha=0.00`，全程 `ty=0.00` 无位移），挂在它内部的层**没有任何过渡窗口**，随父控件 alpha 归零直接消失——所以「让系统退场动画带走我们的层」这条路**不成立**。用户看到的也不是 `restoreChangedViews()` 交还的那个锁屏壁纸层（它仍在 alpha=0 的容器里根本不可见），而是**桌面底下那张系统壁纸**。改为把 `background` 从 `root.addView(..., 0, ...)` 挪到 **`windowRoot.addView(..., 0, ...)`**（窗口根最底层）：keyguard 被撤走时这一层留在原地，露出的就是模糊封面；收尾仍由 `finishSuspend` 置 GONE，不会残留桌面。**前景层必须留在锁屏根**（挂窗口根会「壁纸已出、组件还在」）。
  **排查教训（两条，都已验证）**：① 真机 logcat 行格式是 `I/MeloLock(6507):`——**标签后跟 PID 而不是冒号**，用 `grep "MeloLock:"` 会漏掉全部有效行并误判「模块没运行」（本项目已因此误判过一次，连带误认为需要重勾 Vector 作用域并重整机）；过滤模块日志必须用 `grep -E "/MeloLock\("`。② `adb logcat > file` 走块缓冲、不实时落盘，抓现场要改用 `adb logcat -d -v time -t 'MM-DD HH:MM:SS.mmm'` 一次性 dump。
- **README 改版为项目介绍页（2026-10-07）**：原 README（423 行 / 59KB）整体搬成 `docs/DEVELOPMENT.md`，内容一行未删，只在顶部加了交接说明并把图标路径改到新位置。新 `README.md` 参照 HyperIsland（`1812z/HyperIsland`）的风格重写：居中图标 + 徽章行 + 两列 emoji 特性表 + 效果预览截图 + 适配表 + 安装 + 构建 + 已知限制 + Star History + 许可。新建 `docs/images/`，放 `icon.png`（用 `git mv` 从根目录 `hyper-melolock.png` 移来，与应用图标 `res/drawable-nodpi/ic_hmsc.png` 同 MD5）和 4 张真机截图 `lockscreen-player.jpg` / `lockscreen-notifications.jpg` / `app-home.jpg` / `app-appearance.jpg`。根目录遗留的两张开发期截图 `hmsc-lockscreen-test.png`（早期原型）与 `hmsc-v016.png`（v0.1.6 通知页）未被任何文档引用、样式已过时，**留在原地未处理**且仍被 `.gitignore` 排除。附：本机访问 `github.com` 网页不通（curl 返回 000），但 API 正常，取别人的 README 原文用 `curl -H "Accept: application/vnd.github.raw" https://api.github.com/repos/<owner>/<repo>/readme`。
- **首个 Release（2026-10-07）**：仓库推送到 GitHub（SSH `git@github.com:zuige66/Hyper-MeloLock.git`），补 `.gitignore` 排除签名密钥 / `.workbuddy/` / 临时截图；用新建的 `melolock-release.keystore`（PKCS12，v1+v2+v3 全签，SHA-1 `2B:73:26:5B:F5:0D:BA:65:76:A7:75:8C:55:23:40:CD:C5:4E:C1:F7`）构建 v0.2.0 release APK。**Gradle 坑**：Kotlin DSL 里 `java.util.Properties` 会解析失败（`Unresolved reference 'util'`），必须在文件顶部 `import java.io.FileInputStream` / `import java.util.Properties`；release 还会被 lint 的 `ExtraTranslation`（values-ar 里的 `androidx_startup`，来自迁入资源）拦下，需 `lint { checkReleaseBuilds = false }`。
- 三个真机反馈问题（同一轮）：① **展开通知后左下拉仍能拉出通知、原生锁屏时钟与我们的时钟重叠**——`sceneShowing()` 里带了 `playerSceneVisible`，而展开通知时它被置 false → 拦截整段失效（日志：展开期间一条 `Left-shade gesture consumed` 都没有）。判据改为「场景存在 ∧ 未 suspend ∧ 锁屏周期 ∧ 前台可见」，**不看当前是哪一页**。② **桌面左下拉露出「原屏保的时间」**——`suspended`（场景保留但 GONE）期间系统在桌面下拉 shade 时会把原生锁屏时钟重新显示，故在 suspended 分支继续 `hideNativeClockLayers(root)`，**绝不碰壁纸层**（解锁动画要靠它）。③ **切回播放器时间闪一下**——真凶是 `showMusic()` 在 `animateIn` 分支把**整个 `foreground`（含时钟）从 `alpha 0` 淡入 180ms**，而时钟在通知页一直显示着，等于先消失再回来。返回时不再淡入前景；同时通知**不再做淡出**（淡出层盖在时钟上，任何合成抖动都表现为时间闪烁）。另外把 `bringToFront()` 换成只在确实不在最上层时才动的 `ensureOnTop()`（每帧 `requestLayout` 是掉帧源），并把共享位移**上限从 220dp 收到 96dp + 展开时测量一次缓存复用**（真机两个方向量到 -605px / -228px，卡片会穿过时钟区域再从别处回来）。
- 播放器页 ↔ 通知页切换动效（真机反馈"通知先出来、播放器还没消失"）：根因是 `showNotifications()` 里 `show(notifications)` 让通知**瞬间满不透明出现**，而播放器还在跑 220ms 淡出。改为**共享元素位移过渡**——`sharedOffsetY()` 取通知列表第一张大卡片与自绘播放器卡片的屏幕坐标差（夹 ±220dp）当作目的地；展开时播放器沿位移滑走 + `alpha→0` + `scale→0.94`，通知延迟 70ms 反向淡入（同向运动）；返回时反向重演。两个坑：① pre-draw 守卫每帧按 `expanded` 硬设可见性，返回时若不设 `swappingPages` 闸门会把淡出瞬间掐断，兜底必须用 Handler（**不能挂 `ViewPropertyAnimator` 回调**，解锁期间可能不推进）；② `restore()` 与 `finishPageSwap` 都要复位通知栈的 `alpha/translationY`，否则下次 `show()` 出来是全透明的。日志 `Page swap: … shared offset=<n>px`，offset 一直是回退值就说明测量没生效。**跟进一次卡顿修复**（"回到播放器时时间卡一下"）：① 收起的 `cover`/`playerCard` 由 `GONE` 改 `INVISIBLE`——`GONE` 会把视角/卡片从布局摘掉，`content` 这棵竖直 LinearLayout 要重 measure/layout 全树（大图 ImageView + ProgressBar 卡片），切回来再 VISIBLE 又来一次，两次全树 layout 正砸在动画首尾帧；② 切换动画期间给通知栈开硬件层（`beginNotificationsLayer()`），避免整棵通知子树每帧重绘把主线程拖住；该层必须只在动画期间存在，`finishPageSwap` / `restore()` 一律 `LAYER_TYPE_NONE` 拆掉。
- 切歌掉回原生锁屏（真机日志定位，整改集中在 `MediaSource`）：换歌时 App 会先摘掉 `METADATA_KEY_ALBUM_ART` 的 bitmap、只留 URI（SystemUI 常常读不到：`content://` 权限或 App 私有文件），约 300ms 后才补齐；这期间会话也可能短暂不合格。三条旧处理把这个过渡态当成「播放停了」——① URI 解码失败就 `lastReady = null` 并下发 null；② 会话一不合格立刻下发 null（→ `restore("render-no-session")`）；③ 不合格时把 `current` 置空注销回调、错过新封面。现改为：**解码失败/超时都保留上一帧**；**失去会话给 1500ms 宽限期**（`SESSION_GRACE_MS`）继续交出上一帧；**空窗期按包名继续跟踪会话**保住回调。另外 `loadArt()` 失败会打印 `scheme://authority` 与异常。日志：`Session unavailable … keeping last frame up to 1500ms` / `Empty gap detected` / `Media ready from bitmap title=<新歌>`。验收：切歌全程不应出现 `restore reason=render-no-session` 与 `create()`。**注意** blank final 字段（`listener`）不能在字段初始化器的 lambda 体里引用，会报「可能尚未初始化变量」，用方法引用（`this::announceLost`）。
- 左侧通知栏下拉 → **改成直接禁用该手势**（已装机能挡左下拉）：在通知面板（`leftShadePanel`，id `notification_panel`）的 `dispatchTouchEvent`/`onInterceptTouchEvent`/`onTouchEvent` 上挂 Xposed 钩子，沉浸场景显示期间对**左半屏的 ACTION_DOWN** 返回 false → 通知栏不展开；右侧控制中心在独立容器 `control_center_container`，不受影响。判断「场景在显示」用模块自身状态（`sceneShowing()`），**不再依赖任何系统视图的可见性**（通知栈可见性、`notification_panel` 可见性两个信号都被真机证伪，见 README）。开关 `Config.BLOCK_LEFT_SHADE`（默认开，外观 → 播放器「锁屏禁止左下拉」），每次手势现读配置。代价：左半屏起始的上滑解锁也会失效。日志 `Left-shade touch block armed` / `Left-shade gesture consumed`。
  - **关键坑（第一版真机事故）**：`NotificationPanelView` 没覆写 `dispatchTouchEvent`，`getMethod` 拿到的是**框架 `View` 的实现**，钩它等于给 SystemUI **所有 View** 装钩。第一版缺 `hook.thisObject != leftShadePanel` 判定 → 左半屏触摸被全吃，**播放器 ◀/播放暂停 与「展开通知」按钮全部失灵**。修复后钩子只在 `thisObject == leftShadePanel` 时生效；`findTouchMethod()` 优先取类自己声明的方法，但退回继承实现是常态，**这个判定不能删**。
  - 同一轮删除了 pre-draw 里的逐帧诊断（`Shade watch:` / `Expansion getters:` / `Window tree:`，每帧读 12 个视图 + 反射 7 个 getter），信号既不可靠又耗 CPU。
- 解锁残留治本（用户截图定位）：**前景层与通知按钮从窗口根改挂锁屏根**。截图显示解锁瞬间「壁纸已出、前景组件完整残留」——背景层挂锁屏根被系统动画带走，前景挂窗口根不跟动画。挂同一容器后系统退场动画把整层一起带走，消失同步。同时实现「播放即预建」：`render()` 在桌面收到有效媒体快照且无场景时 `preCreate()`（GONE + suspended），一点播放场景就绪。`restore()` 的 removeView 判据同步改为锁屏根。
- 暂停误判 + 解锁残留（真机日志定位）：① **暂停被当成「无会话」把场景销毁**——暂停识别原先依赖 `lastReady`，切歌失败会清空它，之后 `refresh()` 就走 `onMedia(null)` → `render-no-session` 撤层，锁屏掉回原生。改为遍历时记住第一个「允许的包 + 有 metadata + 非播放」的会话。② **解锁后前景残留桌面**——`suspend()` 把隐藏挂在 `ViewPropertyAnimator.withEndAction` 上，解锁时窗口切换、动画回调可能不推进。改为 Handler 延时兜底（`finishSuspend`），动画只负责视觉淡出。**排查手法**：`logcat -d -v time -s MeloLock | grep -v "Media ready from bitmap"` 能滤掉每 2 秒的取图噪音，直接看到状态机流转。
- 解锁/亮屏闪屏补完（暂停保留 + 熄屏预建）：① **暂停不销毁**——`MediaSource` 区分「会话还在但暂停」和「会话消失」，暂停时交出最后一帧（`Snapshot.playing=false`、`speed=0`），并且 `current` 继续跟踪该会话以免注销回调后点播放不更新；中间键改成播放/暂停切换。② **熄屏预建**——「桌面播歌 → 熄屏 → 亮屏」这条路径上场景从未存在（桌面上 `render()` 一律 `skip("keyguard-unlocked")`），`suspend/resume` 救不了，现在 `ACTION_SCREEN_OFF` 主动触发一次媒体刷新，`render()` 在 `!isInteractive()` 时 `preCreate()`：趁着屏幕黑把场景建好并置 GONE + `suspended`，亮屏由 pre-draw 第一帧 `resume()` 秒显。`render()` 抽出 `applySnapshot()` 共用。
- 修复上滑解锁闪屏（**淡出 + 保留实例**）：真机抓到的 5 轮「熄屏 → 亮屏 → 解锁」日志证明两个现象同源——解锁瞬间 `keyguardGuard` 见 `isKeyguardLocked()` 翻 false 就 `restore()`，而 `restore()` 会把原生壁纸层恢复 VISIBLE、系统解锁动画却还要跑 80~190ms（`USER_PRESENT` 才到），于是露壁纸；场景被销毁后下次亮屏必须 `create()` 重建 126~145ms，于是「先见原生锁屏」。改为 `suspend()`：淡出 180ms 后**保留视图实例**并置 `GONE`，`suspended` 期间 pre-draw 守卫与媒体回调都不接管界面；锁屏重现时 `resume()` 直接复用同一批视图。只有模块关闭 / 媒体不可用 / 锁屏根视图分离才真正 `restore()`。已构建、已安装，待真机验收。
- ⚠️ **仓库存在并行编辑**：2026-10-06 20:00 前后源码从 `io/github/hypermusicscape/lock/`（TAG `HyperMusicScapeLock`）整体迁移到 `io/github/melolock/`（TAG `MeloLock`），且是在本会话改动之上做的重命名。**动 `LockScreenOverlay.java` 前必须先重新读文件。**
- 「上滑解锁露壁纸一秒」「熄屏再亮屏先见原生锁屏一秒」两个现象真机复现仍未消除（README 第 7、9 条修复无效），本轮**只加诊断日志、不改行为**：`LockScreenOverlay.restore()` 改为 `restore(String reason)` 并打印状态快照、pre-draw 检测到解锁撤层打一次日志、`render()` 每个早退分支走去重 `skip()`、`MediaSource.refresh()` 打印取图耗时（bitmap 直出 / 缓存帧 / URI 异步解码三条路径）、`HookEntry` 打印根视图 attach/detach。抓取用 `adb -s 1b3a7d8 logcat -v time | grep MeloLock`——覆盖层在 `com.android.systemui` 进程，**配置端 App 进程的日志里不会有这些行**。
- 首页大标题字号机制：Miuix `TopAppBar` 的大标题字号写死为 `textStyles.title1`（32sp）且 `LocalTextStyles` 是 internal，因此 `HyperIslandTheme` 暴露 `LocalThemeController`，`CollapsingPage` 新增 `largeTitleFontSize`（用同一 controller 再开一层 `MiuixTheme` 只换 `title1`，不影响颜色/深浅色模式）。改名后 `Hyper MeloLock` 只有 12 字符，32sp 已能单行，首页不再传该参数，机制保留备用。
- 项目整体改名为 **Hyper MeloLock**：`app_name`、Gradle 根项目名、包名与 applicationId（`io.github.hypermusicscape.lock` → `io.github.melolock`，含 7 个 Java 文件的 `package`、`Config.PACKAGE`、`assets/xposed_init`、Manifest provider authorities）、日志 tag（`MeloLock` / 配置端 `MeloLock[App]`）、图标源图已移到 `docs/images/icon.png` 与文档全部同步。**改名后是另一个应用**：不会覆盖升级旧包名，Vector 里要卸载旧模块并重新启用、重新勾选 `SystemUI` 作用域，旧配置不继承。仓库目录名仍是 `Hyper Music Scape Lock`。
- Manifest 补声明 `com.android.permission.GET_INSTALLED_APPS`：**申请未声明的权限系统会直接拒绝、不弹授权框**，这是「音乐应用」页看不到授权框的原因；同时去掉「列表为空才申请」的额外条件，进入该页必申请。
- 首页系统信息对 `SystemInfoProvider` 失败增加 `Build.*` 兜底与日志，实拍已恢复真值。
- 应用名与包名统一为 `Hyper MeloLock` / `io.github.melolock`，图标 `res/drawable-nodpi/ic_hmsc.png`，开发者 `zuige` / GitHub `zuige66`。上一轮改名前的状态已提交 `f344d75 app页面配置`。
- 音乐应用页改为列出**全部已安装应用**（进入时申请应用列表权限，另有「显示系统应用」过滤），由用户自行勾选要接管的播放器。
- 滑条点击行为改为直接跳到点击位置：`PreferenceSlider` 新增 `allowManualInput`（默认 `true` 保持 HyperIsland 原行为，本模块传 `false` 不再弹手动输入框）。
- 禁用启动时的检查更新（`INTERNET` 已被移除，原本必然弹「检查更新失败」）。
- 修复首页状态卡关掉后点不回来：`OverviewStatusCard` 新增 `clickableWhenInactive`（HyperIsland 原行为是未激活不可点）。
- 外观页新增锁屏三元素编辑器，并内置 OFL 圆体数字字体（Quicksand / Baloo 2）支持三档圆润 + 连续粗细。
- 直接迁入 HyperIsland（MIT）的配置端主题、组件、资源和导航，入口替换为：首页、音乐应用、外观、开发者。
- 四个根页面对齐 HyperIsland 版式：首页改成「状态卡 + 两张数据卡 + 系统信息卡 + 链接卡」；作者署名与外链留空显示「待填写」并置灰，位置在 `LockScreenPages.kt` 末尾的 `TODO(作者信息)`。
- 把 `OverviewPage.kt` 私有的 `StatusGrid` / `StatusCard` / `StatCard` / `InfoCard` / 告警卡提升为 `internal` 并参数化标题，HyperIsland 首页与模块首页共用同一批组件。
- `Config.java` 新增 `ELEMENT_DEFAULTS` 与 `elementValues()/elementInt()/elementString()/setElementInt()`；`ConfigProvider` 新增 `/elements` 的 key/value 查询；`LockScreenOverlay.measureElements()` 负责算最终尺寸，新增 `roundTypeface()/applyClockTypeface()` 处理内置圆体字体与 `wght` 粗细。
- `Config.java` 新增 `selectedPackages()`、`allPackagesDisabled()`、`enabledAppCount()`、`deviceSupported()` 只读辅助，用于区分「未设置（允许全部）」与「已全部取消」；未改动任何配置键。
- 保留媒体播放器白名单和锁屏背景外观配置，并通过 ContentProvider 同步。
- 构建已升级至 Gradle 9.5 / AGP 9.3.1，并已用 `./gradlew.bat --no-daemon :app:assembleDebug` 验证 Debug 构建通过、安装到真机并启动验证。
- 已禁用迁入代码的启动统计，并用 Manifest merger 移除最终 APK 的 `INTERNET` 权限，避免访问 HyperIsland 更新/下载服务。
